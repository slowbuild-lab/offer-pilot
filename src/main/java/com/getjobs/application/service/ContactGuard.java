package com.getjobs.application.service;

import jakarta.annotation.PostConstruct;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.SQLException;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.Optional;
import static com.getjobs.application.service.SqliteJournal.*;

/**
 * Contact history keyed by recruiter identity, kept apart from the delivery ledger.
 * Knowing that a recruiter was already contacted must never consume quota or look like a delivery.
 */
@Service
public class ContactGuard {
    static final String CODE = "BOSS_HR_COOLDOWN";
    private static final ZoneId ZONE = ZoneId.of("Asia/Shanghai");
    private static final DateTimeFormatter DAY = DateTimeFormatter.ofPattern("yyyy-MM-dd").withZone(ZONE);
    private final DataSource source;
    private final Clock clock;
    @Autowired public ContactGuard(DataSource source) { this(source, Clock.systemUTC()); }
    public ContactGuard(DataSource source, Clock clock) { this.source=source; this.clock=clock; }
    @PostConstruct public void initialize() {
        transaction(source, c -> {
            execute(c,"CREATE TABLE IF NOT EXISTS boss_contacts (platform TEXT NOT NULL,hr_id TEXT NOT NULL,job_id TEXT NOT NULL,contacted_at INTEGER NOT NULL,PRIMARY KEY(platform,hr_id,job_id))");
            execute(c,"CREATE INDEX IF NOT EXISTS idx_boss_contacts_hr ON boss_contacts(platform,hr_id,contacted_at)");
            return null;
        });
    }
    /** Evaluates the cooldown; empty means the policy is off or the platform is out of scope. */
    public Optional<JobProfile.RuleCheck> evaluate(JobProfile.ContactPolicy policy, JobCandidate job) {
        if (!applies(policy,job)) return Optional.empty();
        return transaction(source, c -> evaluate(c,policy,job));
    }
    /** Same decision, reusing the caller's transaction. Nested BEGIN IMMEDIATE would deadlock SQLite. */
    public Optional<JobProfile.RuleCheck> evaluate(Connection c, JobProfile.ContactPolicy policy, JobCandidate job) throws SQLException {
        if (!applies(policy,job)) return Optional.empty();
        String hrId = job.hrId()==null ? "" : job.hrId().strip();
        if (hrId.isEmpty()) return Optional.of(new JobProfile.RuleCheck(CODE,"HR 冷却期",false,"",job.title()==null?"":job.title(),
                "缺少 HR 稳定标识，无法核验冷却期，已跳过该岗位"));
        var rows = rows(c,"SELECT job_id,contacted_at FROM boss_contacts WHERE platform=? AND hr_id=? ORDER BY contacted_at DESC LIMIT 1",
                job.platform(),hrId);
        if (rows.isEmpty()) return Optional.of(new JobProfile.RuleCheck(CODE,"HR 冷却期",true,hrId,"该 HR 首次接触","HR 冷却期内未接触过该招聘方"));
        long last = ((Number)rows.getFirst().get("contacted_at")).longValue();
        long window = policy.hrCooldownDays().longValue()*86400000L;
        long elapsed = clock.millis()-last;
        String lastJob = String.valueOf(rows.getFirst().get("job_id"));
        String evidence = "上次接触 "+DAY.format(Instant.ofEpochMilli(last))+" 岗位 "+lastJob;
        if (elapsed < window) {
            long leftDays = Math.max(1,(window-elapsed+86400000L-1)/86400000L);
            return Optional.of(new JobProfile.RuleCheck(CODE,"HR 冷却期",false,hrId,evidence,
                    "该 HR 在 "+policy.hrCooldownDays()+" 天冷却期内已接触过，还剩 "+leftDays+" 天"));
        }
        return Optional.of(new JobProfile.RuleCheck(CODE,"HR 冷却期",true,hrId,evidence,
                "已超过 "+policy.hrCooldownDays()+" 天冷却期，可以再次接触"));
    }
    /** Records one contact per (platform, recruiter, job); repeated calls for the same pair overwrite the time. */
    public void record(JobCandidate job) {
        if (job==null || !"boss".equals(job.platform())) return;
        String hrId = job.hrId()==null ? "" : job.hrId().strip();
        String jobId = job.jobId()==null ? "" : job.jobId().strip();
        if (hrId.isEmpty() || jobId.isEmpty()) return; // Never invent an identity to key a cooldown on.
        transaction(source, c -> {
            execute(c,"INSERT INTO boss_contacts(platform,hr_id,job_id,contacted_at) VALUES (?,?,?,?) ON CONFLICT(platform,hr_id,job_id) DO UPDATE SET contacted_at=excluded.contacted_at",
                    job.platform(),hrId,jobId,clock.millis());
            return null;
        });
    }
    public Optional<Long> lastContact(String platform,String hrId) {
        if (platform==null || hrId==null || hrId.isBlank()) return Optional.empty();
        return transaction(source,c -> {
            var found = rows(c,"SELECT contacted_at FROM boss_contacts WHERE platform=? AND hr_id=? ORDER BY contacted_at DESC LIMIT 1",platform,hrId);
            return found.isEmpty() ? Optional.<Long>empty() : Optional.of(((Number)found.getFirst().get("contacted_at")).longValue());
        });
    }
    private static boolean applies(JobProfile.ContactPolicy policy,JobCandidate job) {
        return policy!=null && policy.enabled() && job!=null && "boss".equals(job.platform());
    }
}
