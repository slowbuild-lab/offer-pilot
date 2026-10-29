package com.getjobs.worker.utils;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class DeliveryPacingSessionTest {
    @Test void retryAfterAcceptsSecondsDatesAndHugeValuesWithoutShorteningThem(){
        long now=java.time.Instant.parse("2026-10-28T02:00:00Z").toEpochMilli();
        assertEquals(now+60000,DeliveryPacingSession.retryAfter("60",now));
        assertEquals(now+60000,DeliveryPacingSession.retryAfter("Wed, 28 Oct 2026 02:01:00 GMT",now));
        assertEquals(Long.MAX_VALUE,DeliveryPacingSession.retryAfter("99999999999999999999999",now));
        assertEquals(0,DeliveryPacingSession.retryAfter("invalid",now));
        assertEquals(0,DeliveryPacingSession.retryAfter("-10",now));
    }
    @Test void unrelatedDomainsAndStaticAssetsCannotPauseAnAccount(){
        assertTrue(DeliveryPacingSession.critical("boss","https://www.zhipin.com/wapi/zpgeek/job/detail.json"));
        assertTrue(DeliveryPacingSession.critical("zhilian","https://sou.zhaopin.com/api/jobs"));
        assertTrue(DeliveryPacingSession.critical("51job","https://we.51job.com/pc/search"));
        assertTrue(DeliveryPacingSession.critical("liepin","https://api-c.liepin.com/api/job"));
        assertFalse(DeliveryPacingSession.critical("boss","https://zhipin.com.evil.example/api/job"));
        assertFalse(DeliveryPacingSession.critical("boss","https://www.zhipin.com/job/logo.png"));
    }
    @Test void actualRiskAndSuccessMessagesAreSeparated(){
        assertFalse(DeliveryPacingSession.riskText("操作频繁，请稍后再试").isEmpty());
        assertFalse(DeliveryPacingSession.riskText("今日投递达到上限").isEmpty());
        assertFalse(DeliveryPacingSession.riskText("请先登录").isEmpty());
        assertEquals("",DeliveryPacingSession.riskText("投递成功"));
    }
}
