package com.getjobs.application.service;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.sqlite.SQLiteDataSource;
import java.nio.file.Path;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class JobProfileServiceTest {
    @TempDir Path directory;
    private JobProfile profile() { return new JobProfile("Java求职",List.of("boss"),List.of("Java"),"北京",15000,true,List.of("外包"),List.of("销售"),"您好",false,false); }
    @Test void missingHardInformationSalaryOverlapAndExclusionsNeverPass() {
        var rules=profile();
        assertTrue(rules.match(new JobCandidate("boss","1","JAVA开发","公司","北京市","15-20K·13薪","")).eligible());
        assertFalse(rules.match(new JobCandidate("boss","2","Java开发","公司","北京","12-20K","")).eligible());
        assertFalse(rules.match(new JobCandidate("boss","3","Java开发","外包公司","北京","20-30K","")).eligible());
        assertFalse(rules.match(new JobCandidate("boss","4","Java开发","公司","上海","20-30K","")).eligible());
        for (String salary:List.of("面议","20-30万/年","300元/天",""))
            assertFalse(rules.match(new JobCandidate("boss","5","Java开发","公司","北京",salary,"")).eligible());
        assertFalse(rules.match(new JobCandidate("boss","","Java开发","公司","北京","20-30K","")).eligible());
    }
    @Test void unconfirmedOrExpiredPreviewCannotActivateAndDraftNeverReplacesActive() {
        var source=new SQLiteDataSource(); source.setUrl("jdbc:sqlite:"+directory.resolve("profile.db"));
        var clock=new DailyQuotaServiceTest.MutableClock();
        var service=new JobProfileService(source,clock); service.initialize();
        long first=service.save(profile());
        assertThrows(IllegalStateException.class,()->service.confirm(first));
        service.recordPreview(first,List.of(new JobCandidate("boss","1","Java开发","公司","北京","15-20K","")));
        service.confirm(first);
        assertEquals(first,service.active().orElseThrow().version());
        assertThrows(IllegalStateException.class,()->service.recordPreview(first,List.of(new JobCandidate("boss","1","Java开发","公司","北京","15-20K",""))));
        service.forbid("company","公司");
        assertThrows(IllegalStateException.class,()->service.assertAllowed(service.active().orElseThrow(),new JobCandidate("boss","1","Java开发","公司","北京","15-20K","")));
        long draft=service.save(profile());
        assertNotEquals(first,draft);
        assertEquals(first,service.active().orElseThrow().version());
        service.recordPreview(draft,List.of(new JobCandidate("boss","2","Java开发","公司","北京","15-20K","")));
        clock.now=clock.now.plusSeconds(86401);
        assertThrows(IllegalStateException.class,()->service.confirm(draft));
        assertEquals(first,service.active().orElseThrow().version());
    }
}
