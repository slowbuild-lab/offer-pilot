package com.getjobs.application.service;

import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.sqlite.SQLiteDataSource;
import java.nio.file.Path;
import java.time.*;
import java.util.concurrent.*;
import static org.junit.jupiter.api.Assertions.*;

class DeliveryPacingServiceTest {
    @TempDir Path dir;
    SQLiteDataSource source;
    MutableClock clock;
    DeliveryPacingService pacing;
    static class MutableClock extends Clock {
        volatile long now=Instant.parse("2026-10-28T02:00:00Z").toEpochMilli();
        public ZoneId getZone(){return ZoneOffset.UTC;}
        public Clock withZone(ZoneId zone){return this;}
        public Instant instant(){return Instant.ofEpochMilli(now);}
        void seconds(long seconds){now+=seconds*1000;}
    }
    @BeforeEach void setup(){
        source=new SQLiteDataSource();source.setUrl("jdbc:sqlite:"+dir.resolve("pacing.db"));
        clock=new MutableClock();pacing=new DeliveryPacingService(source,clock,bound->0);pacing.initialize();
    }
    private String sent(String platform,String job){
        var permit=pacing.reserve(platform,job,"岗位","https://example.com/job");
        assertEquals("READY",permit.kind(),permit.message());pacing.markSent(permit.token());return permit.token();
    }
    @Test void fourPlatformsHaveIndependentDefaultsAndStartupIsCancellableWithoutQuota(){
        for(String p:DeliveryPacingService.PLATFORMS){
            assertEquals(120,pacing.policy(p).minIntervalSeconds());pacing.begin(p);
            assertEquals("WAIT",pacing.reserve(p,"job","岗位","").kind());
        }
        assertEquals(0,pacing.records(null,1).get("total"));
        clock.seconds(15);assertEquals("READY",pacing.reserve("boss","one","岗位","").kind());
    }
    @Test void minimumIntervalGlobalSpacingAndSingleGroupAreAllEnforced(){
        String first=sent("boss","one");clock.seconds(30);
        assertEquals("WAIT",pacing.reserve("zhilian","two","","").kind());
        pacing.finish(first,true);
        clock.seconds(30);
        String second=sent("zhilian","two");pacing.finish(second,true);
        assertEquals("WAIT",pacing.reserve("boss","three","","").kind());
        clock.seconds(90);assertEquals("READY",pacing.reserve("boss","three","","").kind());
    }
    @Test void readAndListBudgetsIncludePreviewAndRejectedCandidates(){
        assertEquals("READY",pacing.read("boss",false).kind());
        assertEquals("WAIT",pacing.read("boss",false).kind());clock.seconds(10);
        assertEquals("READY",pacing.read("boss",false).kind());
        assertEquals("READY",pacing.read("boss",true).kind());
        clock.seconds(10);assertEquals("WAIT",pacing.read("boss",true).kind());
    }
    @Test void restartPreservesUnknownCooldownAndDoesNotRetryTheSameJob(){
        sent("boss","one");
        var restarted=new DeliveryPacingService(source,clock,b->0);restarted.initialize();
        assertEquals("UNKNOWN",((java.util.Map<?,?>)((java.util.List<?>)restarted.records(null,1).get("items")).getFirst()).get("state"));
        assertEquals("DUPLICATE",restarted.reserve("boss","one","","").kind());
        assertEquals("PAUSED",restarted.reserve("boss","two","","").kind());
        restarted.resume("boss");
        assertEquals("WAIT",restarted.reserve("boss","two","","").kind());
        clock.seconds(120);assertEquals("READY",restarted.reserve("boss","two","","").kind());
    }
    @Test void cancellationBeforeClickDoesNotSpendQuotaOrCreateUnknown(){
        var permit=pacing.reserve("51job","one","","");pacing.cancel(permit.token());
        assertEquals(0,pacing.used("51job"));assertEquals("READY",pacing.reserve("51job","one","","").kind());
    }
    @Test void windDownNeverReleasesAnUnconfirmedExternalAction(){
        String token=sent("liepin","one");pacing.cancel(token);
        assertEquals(1,pacing.used("liepin"));assertEquals("DUPLICATE",pacing.reserve("liepin","one","","").kind());
    }
    @Test void riskPauseRequiresManualResumeEvenAfterRetryAfterAndAcrossMidnight(){
        pacing.pause("boss","操作频繁",clock.millis()+3600000);clock.seconds(60);
        assertThrows(IllegalStateException.class,()->pacing.resume("boss"));
        clock.seconds(86400);assertEquals("PAUSED",pacing.read("boss",false).kind());
        pacing.resume("boss");assertEquals("READY",pacing.read("boss",false).kind());
    }
    @Test void tenAttemptsTriggerPersistentRest(){
        for(int i=0;i<10;i++){pacing.finish(sent("boss","job-"+i),true);clock.seconds(120);}
        assertEquals("WAIT",pacing.reserve("boss","next","","").kind());
        clock.seconds(900);assertEquals("READY",pacing.reserve("boss","next","","").kind());
    }
    @Test void continuousReadingAlsoRestsAndCannotBeResetByOpeningAnotherTask(){
        pacing.begin("boss");clock.seconds(1800);pacing.begin("boss");
        assertEquals("WAIT",pacing.read("boss",false).kind());clock.seconds(900);
        assertEquals("READY",pacing.read("boss",false).kind());
    }
    @Test void clockRollbackPausesRatherThanBursting(){
        pacing.read("boss",false);clock.seconds(-60);
        assertEquals("PAUSED",pacing.read("boss",false).kind());
    }
    @Test void onlyOneConcurrentReservationWinsAcrossServiceInstances() throws Exception {
        var other=new DeliveryPacingService(source,clock,b->0); // same SQLite, no reinitialization during active operation
        try(var pool=Executors.newFixedThreadPool(2)){
            var barrier=new CyclicBarrier(2);
            var a=pool.submit(()->{barrier.await();return pacing.reserve("boss","a","","").kind();});
            var b=pool.submit(()->{barrier.await();return other.reserve("zhilian","b","","").kind();});
            assertEquals(1,java.util.List.of(a.get(),b.get()).stream().filter("READY"::equals).count());
        }
    }
    @Test void oldQuotaAndAttemptsRemainStricterThanNewPolicy(){
        var quota=new DailyQuotaService(source,clock);quota.initialize();quota.update("boss",1);
        var ledger=new BossDeliveryLedger(source,quota,clock);ledger.initialize();
        String task=ledger.createTask(20);String old=ledger.reserve(task,"old","","","");ledger.startAction(old,"CHAT");
        assertEquals("LIMIT",pacing.reserve("boss","new","","").kind());
    }
    @Test void budgetTighteningIsImmediateAndIncreasingWaitsUntilNextBeijingDay(){
        var original=pacing.policy("boss");pacing.update("boss",1,original.withLimit(1));
        pacing.finish(sent("boss","one"),true);clock.seconds(120);
        assertEquals("LIMIT",pacing.reserve("boss","two","","").kind());
        pacing.update("boss",2,original.withLimit(30));assertEquals(1,pacing.policy("boss").dailyLimit());
        clock.seconds(86400);assertEquals(30,pacing.policy("boss").dailyLimit());
        assertThrows(IllegalStateException.class,()->pacing.update("boss",2,original));
    }
    @Test void reducingAnIntervalDoesNotShortenAnAlreadyCommittedDeadline(){
        pacing.finish(sent("boss","one"),true);var p=pacing.policy("boss");
        pacing.update("boss",1,new DeliveryPacingService.Policy(30,0,p.readIntervalSeconds(),p.pageIntervalSeconds(),p.startupSeconds(),p.restEvery(),p.continuousMinutes(),p.restMinutes(),p.dailyLimit(),p.detailBudget(),p.actionIntervalSeconds()));
        clock.seconds(31);assertEquals("WAIT",pacing.reserve("boss","two","","").kind());
    }
    @Test void readBudgetAndGlobalBudgetActuallyStopAllAdapters(){
        var p=pacing.policy("51job");pacing.update("51job",1,new DeliveryPacingService.Policy(p.minIntervalSeconds(),0,p.readIntervalSeconds(),p.pageIntervalSeconds(),p.startupSeconds(),p.restEvery(),p.continuousMinutes(),p.restMinutes(),p.dailyLimit(),1,p.actionIntervalSeconds()));
        assertEquals("READY",pacing.read("51job",false).kind());clock.seconds(10);
        assertEquals("LIMIT",pacing.read("51job",false).kind());
        pacing.updateGlobal(1,new DeliveryPacingService.GlobalPolicy(30,1));
        pacing.finish(sent("boss","one"),true);clock.seconds(30);
        assertEquals("LIMIT",pacing.reserve("liepin","other","","").kind());
    }
    @Test void increasingAnIntervalAlsoAccountsForSlowConfirmation(){
        String token=sent("boss","one");clock.seconds(60);pacing.finish(token,true);
        var p=pacing.policy("boss");pacing.update("boss",1,new DeliveryPacingService.Policy(240,p.extraDelaySeconds(),p.readIntervalSeconds(),p.pageIntervalSeconds(),p.startupSeconds(),p.restEvery(),p.continuousMinutes(),p.restMinutes(),p.dailyLimit(),p.detailBudget(),p.actionIntervalSeconds()));
        assertTrue(((Number)pacing.persistentState("boss").get("next_delivery")).longValue()>=clock.millis()+240000);
    }
}
