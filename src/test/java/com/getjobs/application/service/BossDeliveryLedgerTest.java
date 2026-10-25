package com.getjobs.application.service;

import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.sqlite.SQLiteDataSource;
import java.nio.file.Path;
import static org.junit.jupiter.api.Assertions.*;

class BossDeliveryLedgerTest {
    @TempDir Path directory;
    SQLiteDataSource source;
    DailyQuotaService policy;
    DailyQuotaServiceTest.MutableClock clock;
    BossDeliveryLedger ledger;
    @BeforeEach void setup() {
        source = new SQLiteDataSource(); source.setUrl("jdbc:sqlite:" + directory.resolve("ledger.db"));
        clock = new DailyQuotaServiceTest.MutableClock();
        policy = new DailyQuotaService(source, clock); policy.initialize();
        policy.update("boss", 1);
        ledger = new BossDeliveryLedger(source, policy, clock); ledger.initialize();
    }
    @Test void oneSlotAllowsTextAndImageButNotSecondJobAndUnknownSurvivesRestart() {
        String task = ledger.createTask(20);
        String id = ledger.reserve(task, "job-1", "Java", "公司", "https://www.zhipin.com/job_detail/job-1.html");
        assertNotNull(id);
        ledger.startAction(id, "CHAT"); ledger.finishAction(id, "CHAT", true);
        ledger.startAction(id, "TEXT"); ledger.finishAction(id, "TEXT", true);
        ledger.startAction(id, "IMAGE"); ledger.finishAction(id, "IMAGE", false);
        assertEquals(1, ledger.summary().get("todayAttempts"));
        assertEquals(1, ledger.summary().get("unknownCount"));
        assertThrows(BossDeliveryLedger.LimitReached.class,
                () -> ledger.reserve(task, "job-2", "Java", "另一公司", ""));
        ledger.finishTask(task, "COMPLETED", "测试结束");
        var restarted = new BossDeliveryLedger(source, policy, clock); restarted.initialize();
        String duplicateTask = restarted.createTask(20);
        assertNull(restarted.reserve(duplicateTask, "job-1", "Java", "公司", ""));
        restarted.finishTask(duplicateTask, "COMPLETED", "无新岗位");
        restarted.resolve(id, false);
        assertNull(restarted.reserve(restarted.createTask(20), "job-1", "Java", "公司", ""));
        assertEquals(1, restarted.summary().get("todayAttempts"));
    }
    @Test void cancellationBeforeAnyExternalActionReleasesSlot() {
        String task = ledger.createTask(20);
        String id = ledger.reserve(task, "job-1", "Java", "公司", "");
        assertEquals(1, ledger.summary().get("reservedCount"));
        ledger.cancelBeforeExternal(id);
        assertEquals(0, ledger.summary().get("reservedCount"));
        assertNotNull(ledger.reserve(task, "job-2", "Java", "公司", ""));
    }
    @Test void intentRecordedButNotClickedCanBeCancelledWithoutCharging() {
        String task=ledger.createTask(20);
        String id=ledger.reserve(task,"one","Java","公司","");
        ledger.startAction(id,"CHAT");
        ledger.cancelActionBeforeClick(id,"CHAT");
        assertEquals(0,ledger.summary().get("todayAttempts"));
        assertEquals(0,ledger.summary().get("reservedCount"));
        assertNotNull(ledger.reserve(task,"two","Java","公司",""));
    }
    @Test void sendAfterMidnightRechecksNewDayQuotaButAttachmentKeepsFirstDay() {
        String task = ledger.createTask(20);
        String id = ledger.reserve(task, "job-1", "Java", "公司", "");
        clock.now = java.time.Instant.parse("2026-10-24T16:00:00Z");
        ledger.startAction(id, "TEXT"); ledger.finishAction(id, "TEXT", true);
        assertEquals(1, ledger.summary().get("todayAttempts"));
        clock.now = java.time.Instant.parse("2026-10-25T16:00:00Z");
        ledger.startAction(id, "IMAGE"); ledger.finishAction(id, "IMAGE", true);
        assertEquals(0, ledger.summary().get("todayAttempts"));
    }
    @Test void concurrentTasksCannotOverbookDailyQuota() throws Exception {
        String a = ledger.createTask(20), b = ledger.createTask(20);
        try (var pool = java.util.concurrent.Executors.newFixedThreadPool(2)) {
            var one = pool.submit(() -> reserveOrZero(a, "one"));
            var two = pool.submit(() -> reserveOrZero(b, "two"));
            assertEquals(1, one.get() + two.get());
        }
    }
    private int reserveOrZero(String task, String job) {
        try { return ledger.reserve(task, job, "Java", "公司", "") != null ? 1 : 0; }
        catch (BossDeliveryLedger.LimitReached e) { return 0; }
    }
}
