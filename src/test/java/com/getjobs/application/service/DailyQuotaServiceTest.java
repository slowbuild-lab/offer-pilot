package com.getjobs.application.service;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.sqlite.SQLiteDataSource;
import java.nio.file.Path;
import java.time.*;
import static org.junit.jupiter.api.Assertions.*;

class DailyQuotaServiceTest {
    @TempDir Path directory;
    static class MutableClock extends Clock {
        Instant now = Instant.parse("2026-10-24T15:59:00Z");
        public ZoneId getZone() { return ZoneOffset.UTC; }
        public Clock withZone(ZoneId zone) { return this; }
        public Instant instant() { return now; }
    }
    @Test void reductionsAreImmediateAndRaisesWaitForNextBeijingDay() {
        var source = new SQLiteDataSource();
        source.setUrl("jdbc:sqlite:" + directory.resolve("quota.db"));
        var clock = new MutableClock();
        var policy = new DailyQuotaService(source, clock);
        policy.initialize();
        assertEquals(20, policy.get("boss").get("effectiveLimit"));
        policy.update("boss", 10);
        assertEquals(10, policy.get("boss").get("effectiveLimit"));
        policy.update("boss", 30);
        assertEquals(10, policy.get("boss").get("effectiveLimit"));
        assertEquals(30, policy.get("boss").get("pendingLimit"));
        clock.now = Instant.parse("2026-10-24T16:00:00Z");
        assertEquals(30, policy.get("boss").get("effectiveLimit"));
        assertThrows(IllegalArgumentException.class, () -> policy.update("boss", 0));
        assertThrows(IllegalArgumentException.class, () -> policy.update("boss", 501));
        assertThrows(IllegalArgumentException.class, () -> policy.get("boss; DROP TABLE x"));
    }
}
