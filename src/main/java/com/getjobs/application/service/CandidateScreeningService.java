package com.getjobs.application.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PostConstruct;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import javax.sql.DataSource;
import java.sql.Connection;
import java.time.*;
import java.time.format.DateTimeParseException;
import java.util.*;
import static com.getjobs.application.service.SqliteJournal.*;

/** Rule evaluations are separate from attempts: storing one never reserves delivery quota. */
@Service
public class CandidateScreeningService {
    private static final ZoneId ZONE=ZoneId.of("Asia/Shanghai");
    private static final int PAGE_SIZE=20;
    private final DataSource source;
    private final Clock clock;
    private final ObjectMapper json=new ObjectMapper();
    @Autowired public CandidateScreeningService(DataSource source) { this(source,Clock.systemUTC()); }
    public CandidateScreeningService(DataSource source,Clock clock) { this.source=source; this.clock=clock; }
    @PostConstruct public void initialize() {
        transaction(source,c -> {
            execute(c,"CREATE TABLE IF NOT EXISTS boss_screenings (task_id TEXT NOT NULL,job_id TEXT NOT NULL,profile_version INTEGER NOT NULL,title TEXT,company TEXT,link TEXT,eligible INTEGER NOT NULL,reason TEXT,checks_json TEXT NOT NULL,evaluated_at INTEGER NOT NULL,PRIMARY KEY(task_id,job_id))");
            execute(c,"CREATE INDEX IF NOT EXISTS idx_boss_screenings_time ON boss_screenings(evaluated_at)");
            return null;
        });
    }
    public void record(String task,long version,JobCandidate job,JobProfile.Match match) {
        if (!"boss".equals(job.platform())) throw new IllegalArgumentException("当前只记录BOSS筛选结果");
        if (job.jobId()==null || job.jobId().isBlank()) return; // Never invent a stable job identity.
        transaction(source,c -> {
            var taskRow=row(c,"SELECT state,profile_version FROM boss_tasks WHERE id=?",task);
            if (!"RUNNING".equals(taskRow.get("state")) || !(taskRow.get("profile_version") instanceof Number n) || n.longValue()!=version)
                throw new IllegalStateException("任务或方案版本已变化，停止写入筛选记录");
            execute(c,"INSERT INTO boss_screenings VALUES (?,?,?,?,?,?,?,?,?,?) ON CONFLICT(task_id,job_id) DO UPDATE SET title=excluded.title,company=excluded.company,link=excluded.link,eligible=excluded.eligible,reason=excluded.reason,checks_json=excluded.checks_json,evaluated_at=excluded.evaluated_at",
                    task,job.jobId(),version,job.title(),job.company(),job.link(),match.eligible()?1:0,match.reason(),json.writeValueAsString(match.checks()),clock.millis());
            return null;
        });
    }
    public Map<String,Object> records(int page,String from,String to,String result) {
        if (page<1 || page>10000) throw new IllegalArgumentException("页码必须为 1 到 10000");
        if (result!=null && !result.isEmpty() && !Set.of("passed","excluded").contains(result)) throw new IllegalArgumentException("筛选结果必须为通过或排除");
        LocalDate start=date(from),end=date(to);
        if (start!=null && end!=null && start.isAfter(end)) throw new IllegalArgumentException("开始日期不能晚于结束日期");
        var args=new ArrayList<Object>(); var where=new StringBuilder(" WHERE 1=1");
        if (start!=null) { where.append(" AND evaluated_at>=?"); args.add(start.atStartOfDay(ZONE).toInstant().toEpochMilli()); }
        if (end!=null) { where.append(" AND evaluated_at<?"); args.add(end.plusDays(1).atStartOfDay(ZONE).toInstant().toEpochMilli()); }
        if (result!=null && !result.isEmpty()) { where.append(" AND eligible=?"); args.add(result.equals("passed")?1:0); }
        try(Connection c=source.getConnection()) {
            c.setAutoCommit(false);
            int total=count(c,"SELECT count(*) FROM boss_screenings"+where,args.toArray());
            var paged=new ArrayList<>(args); paged.add(PAGE_SIZE); paged.add((page-1)*PAGE_SIZE);
            var items=rows(c,"SELECT rowid AS id,task_id,job_id,profile_version,title,company,link,eligible,reason,checks_json,evaluated_at FROM boss_screenings"+where+" ORDER BY evaluated_at DESC,rowid DESC LIMIT ? OFFSET ?",paged.toArray());
            for(var item:items) item.put("checks",json.readValue(item.remove("checks_json").toString(),new TypeReference<List<JobProfile.RuleCheck>>(){}));
            c.commit();
            return Map.of("items",items,"page",page,"pageSize",PAGE_SIZE,"total",total,"hasNext",(long)page*PAGE_SIZE<total,"platform","boss");
        } catch(Exception e) { throw new IllegalStateException("筛选记录读取失败，请检查本机服务与数据库",e); }
    }
    private static LocalDate date(String value) {
        if (value==null || value.isEmpty()) return null;
        try {
            if (!value.matches("\\d{4}-\\d{2}-\\d{2}")) throw new DateTimeParseException("format",value,0);
            LocalDate date=LocalDate.parse(value);
            if (date.getYear()<1970 || date.getYear()>9998) throw new IllegalArgumentException("日期年份必须为 1970 到 9998");
            return date;
        } catch(DateTimeParseException e) { throw new IllegalArgumentException("日期必须是有效的 YYYY-MM-DD"); }
    }
}
