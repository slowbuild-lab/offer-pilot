package com.getjobs.application.service;

import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.sqlite.SQLiteDataSource;
import java.nio.file.Path;
import java.time.Instant;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class DeliveryReadServiceTest {
    @TempDir Path dir;
    SQLiteDataSource source; DeliveryReadService reads;
    @BeforeEach void setup() throws Exception {
        source=new SQLiteDataSource();source.setUrl("jdbc:sqlite:"+dir.resolve("read.db"));
        var quota=new DailyQuotaService(source);quota.initialize();
        new BossDeliveryLedger(source,quota).initialize();new ZhilianDeliveryLedger(source).initialize();
        reads=new DeliveryReadService(source);
        try(var c=source.getConnection()) {
            SqliteJournal.execute(c,"INSERT INTO boss_tasks(id,task_limit,state,reason,started_at,profile_version) VALUES ('b',20,'COMPLETED','done',10,3)");
            SqliteJournal.execute(c,"INSERT INTO zhilian_tasks VALUES ('z','{\"confirmedProfile\":{\"version\":4}}',20,50,'PAUSED','verify',20,NULL)");
            for(int i=0;i<21;i++) insert(c,"boss","b","b"+i,"UNKNOWN","2026-10-25T16:00:00Z");
            insert(c,"boss","b","before","UNKNOWN","2026-10-25T15:59:59.999Z");
            insert(c,"boss","b","after","UNKNOWN","2026-10-26T16:00:00Z");
            insert(c,"boss","b","confirmed","CONFIRMED","2026-10-26T08:00:00Z");
            insert(c,"zhilian","z","z1","UNKNOWN","2026-10-26T08:00:00Z");
            SqliteJournal.execute(c,"INSERT INTO boss_actions VALUES ('b0','TEXT','UNKNOWN',1,NULL)");
        }
    }
    private void insert(java.sql.Connection c,String platform,String task,String id,String state,String time) throws Exception {
        SqliteJournal.execute(c,"INSERT INTO "+platform+"_attempts VALUES (?,?,?,?,?,?,?,?,?,?)",id,task,id,"Java","测试公司","https://example.invalid",state,"fixture",Instant.parse(time).toEpochMilli(),"2026-10-26");
    }
    @SuppressWarnings("unchecked") private List<Map<String,Object>> items(Map<String,Object> result) {return (List<Map<String,Object>>)result.get("items");}
    @Test void filtersBeforePaginationWithBeijingInclusiveDateBoundaries() {
        var first=reads.records("boss",1,"2026-10-26","2026-10-26","UNKNOWN");
        assertEquals(21,first.get("total"));assertEquals(true,first.get("hasNext"));assertEquals(20,items(first).size());
        var next=reads.records("boss",2,"2026-10-26","2026-10-26","UNKNOWN");
        assertEquals(false,next.get("hasNext"));assertEquals(1,items(next).size());
        var ids=new HashSet<Object>();items(first).forEach(r->ids.add(r.get("id")));items(next).forEach(r->assertTrue(ids.add(r.get("id"))));
        assertFalse(ids.contains("before"));assertFalse(ids.contains("after"));assertFalse(ids.contains("confirmed"));
        assertEquals(3,items(next).getFirst().get("profile_version"));
        assertEquals("TEXT",((List<Map<String,Object>>)items(next).getFirst().get("actions")).getFirst().get("action"));
    }
    @Test void keepsPlatformsAndConfigPrivateAndHasAnExactLastPage() {
        var z=reads.records("zhilian",1,null,null,"UNKNOWN");assertEquals(1,z.get("total"));assertEquals(4L,items(z).getFirst().get("profile_version"));assertFalse(items(z).getFirst().containsKey("config_json"));
        assertEquals(1,reads.records("boss",1,null,null,"CONFIRMED").get("total"));
        assertEquals(0,reads.records("boss",1,null,null,"RELEASED").get("total"));
        assertEquals(0,items(reads.records("boss",9999,null,null,null)).size());
    }
    @Test void rejectsInvalidFiltersRatherThanSilentlyBroadeningTheQuery() {
        assertThrows(IllegalArgumentException.class,()->reads.records("other",1,null,null,null));
        assertThrows(IllegalArgumentException.class,()->reads.records("boss",0,null,null,null));
        assertThrows(IllegalArgumentException.class,()->reads.records("boss",1,"2026-02-30",null,null));
        assertThrows(IllegalArgumentException.class,()->reads.records("boss",1,"2026-10-27","2026-10-26",null));
        assertThrows(IllegalArgumentException.class,()->reads.records("boss",1,null,null,"UNKNOWN' OR 1=1--"));
        assertThrows(IllegalArgumentException.class,()->reads.records("zhilian",1,null,null,"USER_CONFIRMED"));
    }
    @Test void recentTasksMergeBothLedgersAndReadsHaveNoWrites() throws Exception {
        var tasks=reads.recentTasks();assertEquals("zhilian",tasks.getFirst().get("platform"));assertEquals("boss",tasks.get(1).get("platform"));
        reads.records("boss",1,null,null,null);
        try(var c=source.getConnection()) {assertEquals(24,SqliteJournal.count(c,"SELECT count(*) FROM boss_attempts"));assertEquals("UNKNOWN",SqliteJournal.row(c,"SELECT state FROM boss_actions").get("state"));}
    }
}
