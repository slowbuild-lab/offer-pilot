package com.getjobs.application.service;

import com.getjobs.application.entity.ZhilianJobDataEntity;
import com.getjobs.worker.zhilian.ZhilianConfig;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.sqlite.SQLiteDataSource;
import java.nio.file.Path;
import static org.junit.jupiter.api.Assertions.*;

class ZhilianUnifiedQuotaTest {
    @TempDir Path directory;
    @Test void oldAndNewTasksShareLoweredLimitAndMidnightRechecksReservation() {
        var source=new SQLiteDataSource(); source.setUrl("jdbc:sqlite:"+directory.resolve("z.db"));
        var clock=new DailyQuotaServiceTest.MutableClock();
        var policy=new DailyQuotaService(source,clock); policy.initialize();
        policy.update("zhilian",1);
        var ledger=new ZhilianDeliveryLedger(source,clock); ledger.initialize();
        var config=new ZhilianConfig(); config.setMaxAttemptsPerDay(50); config.setMaxAttemptsPerTask(20);
        String a=ledger.createTask(config), b=ledger.createTask(config);
        var job=new ZhilianJobDataEntity(); job.setJobId("one");
        var first=ledger.reserve(a,job);
        assertEquals(ZhilianDeliveryLedger.Decision.RESERVED,first.decision());
        job.setJobId("two");
        assertEquals(ZhilianDeliveryLedger.Decision.DAY_LIMIT,ledger.reserve(b,job).decision());
        ledger.markSent(first.attemptId()); ledger.confirm(first.attemptId());
        clock.now=java.time.Instant.parse("2026-10-24T16:00:00Z");
        var next=ledger.reserve(b,job);
        assertEquals(ZhilianDeliveryLedger.Decision.RESERVED,next.decision());
        ledger.markSent(next.attemptId()); ledger.unknown(next.attemptId(),"测试未知");
        ledger.finishTask(b,"PAUSED","需处理"); ledger.resolve(next.attemptId(),false);
        String c=ledger.createTask(config);
        assertEquals(ZhilianDeliveryLedger.Decision.DUPLICATE,ledger.reserve(c,job).decision());
    }
}
