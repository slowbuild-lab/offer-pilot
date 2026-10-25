package com.getjobs.application.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PostConstruct;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import javax.sql.DataSource;
import java.time.Clock;
import java.util.*;
import static com.getjobs.application.service.SqliteJournal.*;

@Service
public class JobProfileService {
    public record Snapshot(long version, JobProfile rules, Long confirmedAt, String materialToken) {}
    private final DataSource source; private final Clock clock;
    private final ObjectMapper json=new ObjectMapper();
    @Autowired public JobProfileService(DataSource source) { this(source,Clock.systemUTC()); }
    public JobProfileService(DataSource source,Clock clock) { this.source=source; this.clock=clock; }
    @PostConstruct public void initialize() {
        transaction(source,c -> {
            execute(c,"CREATE TABLE IF NOT EXISTS job_profiles (version INTEGER PRIMARY KEY AUTOINCREMENT,rules_json TEXT NOT NULL,confirmed_at INTEGER,preview_at INTEGER,preview_json TEXT,material_token TEXT NOT NULL DEFAULT '')");
            execute(c,"CREATE TABLE IF NOT EXISTS job_profile_current (slot INTEGER PRIMARY KEY CHECK(slot=1),version INTEGER NOT NULL)");
            execute(c,"CREATE TABLE IF NOT EXISTS delivery_forbidden (kind TEXT NOT NULL,value TEXT NOT NULL,PRIMARY KEY(kind,value))"); return null;
        });
    }
    public long save(JobProfile rules) {
        return transaction(source,c -> {
            execute(c,"INSERT INTO job_profiles(rules_json) VALUES (?)",json.writeValueAsString(rules));
            return ((Number)row(c,"SELECT last_insert_rowid() AS id").get("id")).longValue();
        });
    }
    public Snapshot get(long version) {
        return transaction(source,c -> snapshot(row(c,"SELECT * FROM job_profiles WHERE version=?",version)));
    }
    public Optional<Snapshot> active() {
        return transaction(source,c -> {
            var found=rows(c,"SELECT p.* FROM job_profiles p JOIN job_profile_current s ON s.version=p.version WHERE s.slot=1");
            return found.isEmpty()?Optional.empty():Optional.of(snapshot(found.getFirst()));
        });
    }
    public Optional<Snapshot> activeFor(String platform) { return active().filter(s -> s.rules().platforms().contains(platform)); }
    public Map<String,Object> state() {
        return transaction(source,c -> {
            var latest=rows(c,"SELECT * FROM job_profiles ORDER BY version DESC LIMIT 1");
            var result=new LinkedHashMap<String,Object>();
            result.put("draft",latest.isEmpty()?null:snapshot(latest.getFirst()));
            var current=rows(c,"SELECT p.* FROM job_profiles p JOIN job_profile_current s ON s.version=p.version WHERE s.slot=1");
            result.put("active",current.isEmpty()?null:snapshot(current.getFirst()));
            return result;
        });
    }
    public List<Map<String,Object>> recordPreview(long version,List<JobCandidate> candidates) { return recordPreview(version,candidates,""); }
    public List<Map<String,Object>> recordPreview(long version,List<JobCandidate> candidates,String materialToken) {
        return transaction(source,c -> {
            var profile=snapshot(row(c,"SELECT * FROM job_profiles WHERE version=?",version));
            if (profile.confirmedAt()!=null) throw new IllegalStateException("已确认版本不可改写，请先保存新草稿");
            for(String platform:profile.rules().platforms()) {
                if (candidates.stream().noneMatch(j -> platform.equals(j.platform()) && j.jobId()!=null && !j.jobId().isBlank())) throw new IllegalStateException("未采集到 "+platform+" 的可靠岗位，不能确认预览");
            }
            var output=new ArrayList<Map<String,Object>>();
            for (var job:candidates) {
                var match=profile.rules().match(job);
                if (isForbidden(c,job)) match=new JobProfile.Match(false,"命中紧急禁止或信息不足以核验禁止项");
                output.add(Map.of("job",job,"eligible",match.eligible(),"reason",match.reason()));
            }
            execute(c,"UPDATE job_profiles SET preview_at=?,preview_json=?,material_token=? WHERE version=?",clock.millis(),json.writeValueAsString(output),materialToken,version);
            return output;
        });
    }
    public void confirm(long version) {
        transaction(source,c -> {
            var stored=row(c,"SELECT * FROM job_profiles WHERE version=?",version);
            if (stored.get("confirmed_at")!=null) throw new IllegalStateException("该版本已经确认，请保存新草稿后操作");
            if (((Number)row(c,"SELECT max(version) AS version FROM job_profiles").get("version")).longValue()!=version) throw new IllegalStateException("草稿已更新，请刷新并确认最新版本");
            if (!(stored.get("preview_at") instanceof Number n) || clock.millis()-n.longValue()>86400000 || clock.millis()<n.longValue()) throw new IllegalStateException("请先完成同版本的有效预览（24 小时内）");
            var snapshot=snapshot(stored);
            if (snapshot.rules().platforms().contains("boss") && snapshot.rules().bossMessage().isBlank()) throw new IllegalStateException("请先填写本人确认的 BOSS 招呼语");
            if (snapshot.rules().platforms().contains("zhilian") && !snapshot.rules().zhilianResumeConfirmed()) throw new IllegalStateException("请先核对智联平台简历并确认");
            if (snapshot.rules().platforms().contains("boss") && snapshot.rules().bossImage() && snapshot.materialToken().isBlank()) throw new IllegalStateException("图片简历未绑定，请重新预览");
            execute(c,"UPDATE job_profiles SET confirmed_at=? WHERE version=?",clock.millis(),version);
            execute(c,"INSERT INTO job_profile_current VALUES(1,?) ON CONFLICT(slot) DO UPDATE SET version=excluded.version",version); return null;
        });
    }
    public List<Map<String,Object>> preview(long version) {
        return transaction(source,c -> {
            Object raw=row(c,"SELECT preview_json FROM job_profiles WHERE version=?",version).get("preview_json");
            return raw==null?List.of():json.readValue(raw.toString(),new TypeReference<List<Map<String,Object>>>(){});
        });
    }
    public void invalidatePreview(long version) {
        transaction(source,c -> {
            if (execute(c,"UPDATE job_profiles SET preview_at=NULL,preview_json=NULL,material_token='' WHERE version=? AND confirmed_at IS NULL",version)!=1) throw new IllegalStateException("请先保存未确认的新草稿");
            return null;
        });
    }
    public void forbid(String kind,String value) {
        if (!Set.of("company","boss","zhilian").contains(kind) || value==null || value.isBlank() || value.length()>300) throw new IllegalArgumentException("禁止目标不合法");
        transaction(source,c -> { execute(c,"INSERT OR IGNORE INTO delivery_forbidden VALUES (?,?)",kind,JobProfile.normal(value)); return null; });
    }
    public void assertAllowed(Snapshot snapshot,JobCandidate job) {
        var result=snapshot.rules().match(job);
        if (!result.eligible()) throw new IllegalStateException(result.reason());
        transaction(source,c -> {
            if (isForbidden(c,job)) throw new IllegalStateException("已加入紧急禁止，停止外发");
            return null;
        });
    }
    public boolean forbidden(JobCandidate job) {
        return transaction(source,c -> isForbidden(c,job));
    }
    private boolean isForbidden(java.sql.Connection c,JobCandidate job) throws java.sql.SQLException {
        if (JobProfile.normal(job.company()).isEmpty() && count(c,"SELECT count(*) FROM delivery_forbidden WHERE kind='company'")>0) return true;
        return count(c,"SELECT count(*) FROM delivery_forbidden WHERE (kind='company' AND value=?) OR (kind=? AND value=?)",JobProfile.normal(job.company()),job.platform(),JobProfile.normal(job.jobId()))>0;
    }
    private Snapshot snapshot(Map<String,Object> value) throws Exception {
        return new Snapshot(((Number)value.get("version")).longValue(),json.readValue(value.get("rules_json").toString(),JobProfile.class),
                value.get("confirmed_at") instanceof Number n?n.longValue():null,value.get("material_token").toString());
    }
}
