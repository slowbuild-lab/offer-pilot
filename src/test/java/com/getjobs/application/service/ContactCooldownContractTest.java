package com.getjobs.application.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.sqlite.SQLiteDataSource;
import java.nio.file.Path;
import java.time.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

/** Contract for the optional BOSS HR cooldown: defaults, validation, preview evidence and the pre-send gate. */
class ContactCooldownContractTest {
    @TempDir Path directory;
    SQLiteDataSource source;
    JobProfileService profiles;
    ContactGuard contacts;
    final Clock clock = Clock.fixed(Instant.parse("2026-10-28T02:00:00Z"), ZoneOffset.UTC);

    @BeforeEach void setup() {
        source = new SQLiteDataSource();
        source.setUrl("jdbc:sqlite:"+directory.resolve("cooldown.db"));
        var quotas = new DailyQuotaService(source); quotas.initialize();
        new BossDeliveryLedger(source, quotas).initialize(); // quota tables exist so we can prove they stay untouched
        contacts = new ContactGuard(source, clock); contacts.initialize();
        profiles = new JobProfileService(source, clock, contacts); profiles.initialize();
    }
    private JobProfile profile(JobProfile.ContactPolicy policy) {
        return new JobProfile("开发",List.of("boss"),List.of("Java"),"北京",null,true,List.of(),List.of(),"您好",false,false,null,policy);
    }
    private JobCandidate job(String jobId, String hrId) {
        return new JobCandidate("boss",jobId,"Java开发","某公司","北京","15-20K","https://www.zhipin.com/job_detail/"+jobId+".html","负责Java开发",null)
                .withHr(hrId,"今日活跃");
    }
    private List<JobProfile.RuleCheck> checks(Map<String,Object> item) {
        @SuppressWarnings("unchecked")
        var value = (List<JobProfile.RuleCheck>) item.get("checks");
        return value;
    }

    @Test void policyRangeIsEnforced() {
        assertThrows(IllegalArgumentException.class, () -> new JobProfile.ContactPolicy(0));
        assertThrows(IllegalArgumentException.class, () -> new JobProfile.ContactPolicy(-1));
        assertThrows(IllegalArgumentException.class, () -> new JobProfile.ContactPolicy(366));
        assertEquals(365, new JobProfile.ContactPolicy(365).hrCooldownDays());
    }

    @Test void policyRequiresTheBossPlatform() {
        var thrown = assertThrows(IllegalArgumentException.class, () -> new JobProfile("开发",List.of("zhilian"),List.of("Java"),
                "北京",null,true,List.of(),List.of(),"您好",false,true,null,new JobProfile.ContactPolicy(30)));
        assertTrue(thrown.getMessage().contains("BOSS"), thrown.getMessage());
    }

    @Test void legacyProfileWithoutPolicyStaysDisabled() throws Exception {
        var json = new ObjectMapper();
        var legacy = new JobProfile("旧方案",List.of("boss"),List.of("Java"),"北京",null,true,List.of(),List.of(),"您好",false,false);
        var node = (ObjectNode) json.valueToTree(legacy);
        node.remove("contactPolicy");
        var parsed = json.treeToValue(node, JobProfile.class);
        assertNotNull(parsed.contactPolicy());
        assertFalse(parsed.contactPolicy().enabled());
    }

    @Test void previewExplainsTheCooldownInsteadOfSilentlySkipping() {
        long version = profiles.save(profile(new JobProfile.ContactPolicy(30)));
        contacts.record(job("job-1","hr-1"));
        var items = profiles.recordPreview(version, List.of(job("job-2","hr-1")));
        assertEquals(1, items.size());
        assertEquals(false, items.getFirst().get("eligible"));
        assertTrue(items.getFirst().get("reason").toString().contains("冷却期内"), items.getFirst().get("reason").toString());
        var cooldown = checks(items.getFirst()).stream().filter(c -> ContactGuard.CODE.equals(c.code())).findFirst().orElseThrow();
        assertEquals("hr-1", cooldown.term());
        assertTrue(cooldown.evidence().contains("job-1"), cooldown.evidence());
    }

    @Test void previewWithoutPolicyShowsNoCooldownCheck() {
        long version = profiles.save(profile(null));
        contacts.record(job("job-1","hr-1"));
        var items = profiles.recordPreview(version, List.of(job("job-2","hr-1")));
        assertEquals(true, items.getFirst().get("eligible"));
        assertTrue(checks(items.getFirst()).stream().noneMatch(c -> ContactGuard.CODE.equals(c.code())));
    }

    @Test void previewPassesWhenAnotherRecruiterOrWindowExpired() {
        long version = profiles.save(profile(new JobProfile.ContactPolicy(30)));
        contacts.record(job("job-1","hr-1"));
        var other = profiles.recordPreview(version, List.of(job("job-2","hr-9")));
        assertEquals(true, other.getFirst().get("eligible"));
        var expired = new ContactGuard(source, Clock.fixed(Instant.parse("2026-11-30T02:00:00Z"),ZoneOffset.UTC));
        expired.initialize();
        var later = new JobProfileService(source, Clock.fixed(Instant.parse("2026-11-30T02:00:00Z"),ZoneOffset.UTC), expired);
        later.initialize();
        var items = later.recordPreview(version, List.of(job("job-2","hr-1")));
        assertEquals(true, items.getFirst().get("eligible"));
    }

    @Test void assertAllowedRejectsTheSecondJobFromTheSameRecruiter() {
        long version = profiles.save(profile(new JobProfile.ContactPolicy(30)));
        contacts.record(job("job-1","hr-1"));
        var snapshot = profiles.get(version);
        var thrown = assertThrows(IllegalStateException.class, () -> profiles.assertAllowed(snapshot, job("job-2","hr-1")));
        assertTrue(thrown.getMessage().contains("冷却期内"), thrown.getMessage());
        assertDoesNotThrow(() -> profiles.assertAllowed(snapshot, job("job-3","hr-2")));
    }

    @Test void assertAllowedFailsClosedWithoutRecruiterIdentity() {
        long version = profiles.save(profile(new JobProfile.ContactPolicy(30)));
        var snapshot = profiles.get(version);
        var thrown = assertThrows(IllegalStateException.class, () -> profiles.assertAllowed(snapshot, job("job-2",null)));
        assertTrue(thrown.getMessage().contains("缺少 HR 稳定标识"), thrown.getMessage());
    }

    @Test void cooldownNeverConsumesDeliveryQuota() throws Exception {
        long version = profiles.save(profile(new JobProfile.ContactPolicy(30)));
        contacts.record(job("job-1","hr-1"));
        profiles.recordPreview(version, List.of(job("job-2","hr-1")));
        try (var c = source.getConnection()) {
            assertEquals(0, SqliteJournal.count(c,"SELECT count(*) FROM boss_attempts"));
            assertEquals(1, SqliteJournal.count(c,"SELECT count(*) FROM boss_contacts"));
        }
    }
}
