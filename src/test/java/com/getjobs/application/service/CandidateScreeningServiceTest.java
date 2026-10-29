package com.getjobs.application.service;

import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.sqlite.SQLiteDataSource;
import java.nio.file.Path;
import java.time.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class CandidateScreeningServiceTest {
    @TempDir Path directory;
    SQLiteDataSource source; BossDeliveryLedger ledger; long version; String task;
    @BeforeEach void setup() {
        source=new SQLiteDataSource(); source.setUrl("jdbc:sqlite:"+directory.resolve("screening.db"));
        var quotas=new DailyQuotaService(source); quotas.initialize(); ledger=new BossDeliveryLedger(source,quotas); ledger.initialize();
        var profiles=new JobProfileService(source); profiles.initialize();
        version=profiles.save(new JobProfile("开发",List.of("boss"),List.of("Java"),"北京",null,true,List.of(),List.of(),"您好",false,false));
        task=ledger.createTask(20); var config=new com.getjobs.worker.boss.BossConfig(); config.setConfirmedProfile(profiles.get(version)); ledger.bindConfig(task,config);
    }
    private CandidateScreeningService at(String instant) {
        var service=new CandidateScreeningService(source,Clock.fixed(Instant.parse(instant),ZoneOffset.UTC)); service.initialize(); return service;
    }
    private JobCandidate job(String id) { return new JobCandidate("boss",id,"Java开发","隔离公司","北京","15-20K","https://www.zhipin.com/job_detail/"+id+".html","负责销售"); }
    private JobProfile.Match match(boolean eligible) { return new JobProfile.Match(eligible,"fixture",List.of(new JobProfile.RuleCheck("JD","工作内容排除",eligible,"销售","负责销售","fixture"))); }
    @SuppressWarnings("unchecked") private List<Map<String,Object>> items(Map<String,Object> result) { return (List<Map<String,Object>>)result.get("items"); }
    @Test void repeatedJobWithinTaskIsOneEvaluationAndNeverUsesDeliveryQuota() throws Exception {
        var service=at("2026-10-27T16:00:00Z"); service.record(task,version,job("one"),match(false)); service.record(task,version,job("one"),match(true));
        var result=service.records(1,null,null,null); assertEquals(1,result.get("total"));
        assertEquals(1,items(result).getFirst().get("eligible"));
        assertTrue(items(result).getFirst().get("checks").toString().contains("负责销售"));
        assertEquals(0,ledger.summary().get("todayAttempts"));
        try(var c=source.getConnection()) { assertEquals(0,SqliteJournal.count(c,"SELECT count(*) FROM boss_actions")); }
    }
    @Test void beijingDateAndResultAreAppliedBeforeStablePagination() {
        var before=at("2026-10-27T15:59:59.999Z"); before.record(task,version,job("before"),match(false));
        var day=at("2026-10-27T16:00:00Z"); for(int i=0;i<21;i++) day.record(task,version,job("j"+i),match(false));
        day.record(task,version,job("passed"),match(true));
        at("2026-10-28T16:00:00Z").record(task,version,job("after"),match(false));
        var first=day.records(1,"2026-10-28","2026-10-28","excluded"); var second=day.records(2,"2026-10-28","2026-10-28","excluded");
        assertEquals(21,first.get("total")); assertEquals(20,items(first).size()); assertEquals(true,first.get("hasNext"));
        assertEquals(1,items(second).size()); assertEquals(false,second.get("hasNext"));
        var ids=new HashSet<Object>(); items(first).forEach(r->ids.add(r.get("job_id"))); items(second).forEach(r->assertTrue(ids.add(r.get("job_id"))));
        assertFalse(ids.contains("before")); assertFalse(ids.contains("after")); assertFalse(ids.contains("passed"));
    }
    @Test void rejectsInvalidQueriesAndTaskVersionMismatch() {
        var service=at("2026-10-28T00:00:00Z");
        assertThrows(IllegalArgumentException.class,()->service.records(0,null,null,null));
        assertThrows(IllegalArgumentException.class,()->service.records(1,"2026-02-30",null,null));
        assertThrows(IllegalArgumentException.class,()->service.records(1,"2026-10-29","2026-10-28",null));
        assertThrows(IllegalArgumentException.class,()->service.records(1,null,null,"excluded' OR 1=1--"));
        assertThrows(IllegalStateException.class,()->service.record(task,version+1,job("one"),match(true)));
        ledger.finishTask(task,"STOPPED","fixture");
        assertThrows(IllegalStateException.class,()->service.record(task,version,job("two"),match(true)));
    }
    @Test void differentTasksKeepSeparateVersionedResultsAcrossServiceRestarts() {
        var first=at("2026-10-28T00:00:00Z"); first.record(task,version,job("one"),match(false));
        var config=new com.getjobs.worker.boss.BossConfig(); config.setConfirmedProfile(new JobProfileService(source).get(version));
        String another=ledger.createTask(20); ledger.bindConfig(another,config);
        var restarted=at("2026-10-28T01:00:00Z"); restarted.record(another,version,job("one"),match(true));
        assertEquals(2,restarted.records(1,null,null,null).get("total"));
        assertEquals(1,restarted.records(1,null,null,"passed").get("total"));
    }
}
