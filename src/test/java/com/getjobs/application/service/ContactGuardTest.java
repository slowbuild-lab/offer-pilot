package com.getjobs.application.service;

import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.sqlite.SQLiteDataSource;
import java.nio.file.Path;
import java.time.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

/** The cooldown must key on the recruiter identity, not on the job, and must never touch delivery quota. */
class ContactGuardTest {
    @TempDir Path directory;
    SQLiteDataSource source;
    static final JobProfile.ContactPolicy THIRTY_DAYS = new JobProfile.ContactPolicy(30);

    @BeforeEach void setup() {
        source = new SQLiteDataSource();
        source.setUrl("jdbc:sqlite:"+directory.resolve("contacts.db"));
        var quotas = new DailyQuotaService(source); quotas.initialize();
        new BossDeliveryLedger(source, quotas).initialize(); // quota tables exist so we can prove they stay untouched
    }
    private ContactGuard at(String instant) {
        var guard = new ContactGuard(source, Clock.fixed(Instant.parse(instant), ZoneOffset.UTC));
        guard.initialize();
        return guard;
    }
    private JobCandidate job(String jobId, String hrId) {
        return new JobCandidate("boss",jobId,"Java开发","某公司","北京","15-20K","https://www.zhipin.com/job_detail/"+jobId+".html","负责Java开发",null)
                .withHr(hrId,"今日活跃");
    }
    private JobProfile.RuleCheck check(Optional<JobProfile.RuleCheck> value) {
        return value.orElseThrow(() -> new AssertionError("冷却期启用时应当产出检查项"));
    }

    @Test void sameRecruiterOnAnotherJobIsBlocked() {
        at("2026-10-28T02:00:00Z").record(job("job-1","hr-1"));
        var later = at("2026-11-05T02:00:00Z");
        var blocked = check(later.evaluate(THIRTY_DAYS, job("job-2","hr-1")));
        assertFalse(blocked.passed());
        assertEquals(ContactGuard.CODE, blocked.code());
        assertEquals("hr-1", blocked.term());
        assertTrue(blocked.reason().contains("冷却期内"), blocked.reason());
        assertTrue(blocked.evidence().contains("job-1"), blocked.evidence());
    }

    @Test void anotherRecruiterIsNotPunished() {
        at("2026-10-28T02:00:00Z").record(job("job-1","hr-1"));
        var passed = check(at("2026-11-05T02:00:00Z").evaluate(THIRTY_DAYS, job("job-2","hr-2")));
        assertTrue(passed.passed());
        assertTrue(passed.reason().contains("未接触"));
    }

    @Test void missingRecruiterIdentityFailsClosed() {
        var blocked = check(at("2026-11-05T02:00:00Z").evaluate(THIRTY_DAYS, job("job-2",null)));
        assertFalse(blocked.passed());
        assertTrue(blocked.reason().contains("缺少 HR 稳定标识"), blocked.reason());
    }

    @Test void cooldownEndsExactlyAtTheConfiguredWindow() {
        at("2026-10-28T02:00:00Z").record(job("job-1","hr-1"));
        assertFalse(check(at("2026-11-26T02:00:00Z").evaluate(THIRTY_DAYS, job("job-2","hr-1"))).passed());
        assertTrue(check(at("2026-11-27T02:00:00Z").evaluate(THIRTY_DAYS, job("job-2","hr-1"))).passed());
    }

    @Test void disabledPolicyProducesNoCheckAndOutOfScopePlatformIsIgnored() {
        var guard = at("2026-11-05T02:00:00Z");
        assertTrue(guard.evaluate(null, job("job-2","hr-1")).isEmpty());
        assertTrue(guard.evaluate(new JobProfile.ContactPolicy(null), job("job-2","hr-1")).isEmpty());
        var zhilian = new JobCandidate("zhilian","job-3","Java开发","某公司","北京","15-20K","link","负责Java开发");
        assertTrue(guard.evaluate(THIRTY_DAYS, zhilian).isEmpty());
    }

    @Test void recordingIsIdempotentPerJobAndNeverTouchesDeliveryLedger() throws Exception {
        at("2026-10-28T02:00:00Z").record(job("job-1","hr-1"));
        at("2026-10-29T02:00:00Z").record(job("job-1","hr-1"));
        try (var c = source.getConnection()) {
            assertEquals(1, SqliteJournal.count(c,"SELECT count(*) FROM boss_contacts"));
            assertEquals(0, SqliteJournal.count(c,"SELECT count(*) FROM boss_attempts"));
            assertEquals(0, SqliteJournal.count(c,"SELECT count(*) FROM boss_actions"));
        }
        assertEquals(Instant.parse("2026-10-29T02:00:00Z").toEpochMilli(),
                at("2026-10-29T02:00:00Z").lastContact("boss","hr-1").orElseThrow());
    }

    @Test void blankIdentityIsNeverRecorded() throws Exception {
        var guard = at("2026-10-28T02:00:00Z");
        guard.record(job("job-1","  "));
        guard.record(job(" ","hr-1"));
        guard.record(null);
        try (var c = source.getConnection()) {
            assertEquals(0, SqliteJournal.count(c,"SELECT count(*) FROM boss_contacts"));
        }
    }

    @Test void tenDayWindowBlocksOnDayNineAndAllowsOnDayTen() {
        at("2026-10-28T02:00:00Z").record(job("job-1","hr-1"));
        var ten = new JobProfile.ContactPolicy(10);
        assertFalse(check(at("2026-11-06T02:00:00Z").evaluate(ten, job("job-2","hr-1"))).passed());
        assertTrue(check(at("2026-11-07T02:00:00Z").evaluate(ten, job("job-2","hr-1"))).passed());
    }
}
