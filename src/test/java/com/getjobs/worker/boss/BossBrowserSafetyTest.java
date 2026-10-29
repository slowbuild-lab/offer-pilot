package com.getjobs.worker.boss;

import com.getjobs.application.service.*;
import com.getjobs.worker.utils.Job;
import com.getjobs.worker.utils.PlaywrightUtil;
import com.getjobs.worker.zhilian.*;
import com.microsoft.playwright.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.junit.jupiter.api.io.TempDir;
import org.sqlite.SQLiteDataSource;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/** Real Chrome, disposable contexts, all requests fulfilled from fixtures; no recruiting account/network. */
class BossBrowserSafetyTest {
    @TempDir Path directory;
    static Playwright playwright; static Browser browser;
    @BeforeAll static void browser() {
        Path driver=Path.of("build/patchright-driver").toAbsolutePath();
        if (java.nio.file.Files.exists(driver.resolve("package/cli.js"))) System.setProperty("playwright.cli.dir",driver.toString());
        playwright=Playwright.create();
        browser=playwright.chromium().launch(new BrowserType.LaunchOptions().setChannel("chrome").setHeadless(true));
    }
    @AfterAll static void close() { if (browser!=null) browser.close(); if (playwright!=null) playwright.close(); }
    private static final String DETAIL="""
        <a class="btn-startchat" onclick="window.recordAction('chat')">立即沟通</a>
        <textarea class="input-area"></textarea>
        <button class="btn-send" onclick="window.recordAction('text'); const li=document.createElement('li'); li.className='message-item item-myself'; const s=document.createElement('span'); s.className='message-content'; s.textContent=document.querySelector('textarea').value; li.append(s); document.querySelector('ul').append(li)">发送</button>
        <ul class="im-list"></ul>
        """;
    @ParameterizedTest @ValueSource(booleans={false,true})
    void quotaIsEnforcedBeforeChatAndConfirmedTextHasRealDomEvidence(boolean iframe) throws Exception {
        var source=new SQLiteDataSource(); source.setUrl("jdbc:sqlite:"+directory.resolve("b.db"));
        var policy=new DailyQuotaService(source); policy.initialize(); policy.update("boss",1);
        var ledger=new BossDeliveryLedger(source,policy); ledger.initialize();
        var worker=new Boss(mock(BossService.class),null,null);
        var config=new BossConfig(); config.setSayHi("您好，希望进一步了解岗位"); worker.setConfig(config);
        worker.setLedger(ledger); worker.setTaskId(ledger.createTask(20)); worker.setShouldStopCallback(()->false);
        var chat=new AtomicInteger(); var text=new AtomicInteger();
        try (var context=browser.newContext(); var sleeps=mockStatic(PlaywrightUtil.class)) {
            context.exposeBinding("recordAction",(sourceContext,args)-> { if ("chat".equals(args[0])) chat.incrementAndGet(); else text.incrementAndGet(); return null; });
            String body=iframe?DETAIL.replace("document.querySelector('ul').append(li)","document.querySelector('iframe').contentDocument.querySelector('ul').append(li)")
                    .replace("<ul class=\"im-list\"></ul>","<iframe srcdoc=\"<ul class='im-list'></ul>\"></iframe>"):DETAIL;
            context.route("**/*",route -> route.fulfill(new Route.FulfillOptions().setContentType("text/html; charset=utf-8").setBody(body)));
            Page listing=context.newPage(); worker.setPage(listing);
            listing.setContent("<a class='more-job-btn' href='/job_detail/one.html'>详情</a>");
            var method=Boss.class.getDeclaredMethod("resumeSubmission",String.class,Job.class); method.setAccessible(true);
            Job job=new Job(); job.setJobName("Java开发"); job.setCompanyName("隔离公司");
            assertEquals(true,method.invoke(worker,"Java",job), () -> "动作="+ledger.attempts(1)+",chat="+chat.get()+",text="+text.get());
            assertEquals(1,ledger.summary().get("todayAttempts"));
            assertEquals("CONFIRMED",ledger.attempts(1).getFirst().get("state"));
            listing.setContent("<a class='more-job-btn' href='/job_detail/two.html'>详情</a>");
            var error=assertThrows(java.lang.reflect.InvocationTargetException.class,()->method.invoke(worker,"Java",job));
            assertInstanceOf(BossDeliveryLedger.LimitReached.class,error.getCause());
            assertEquals(1,chat.get()); assertEquals(1,text.get());
        }
    }
    @Test void bossPreviewNeverOpensDetailOrUsesJournal() throws Exception {
        var worker=new Boss(null,null,null); var config=new BossConfig(); config.setDebugger(true); worker.setConfig(config);
        var collected=new ArrayList<JobCandidate>(); worker.setPreviewCallback(collected::add); worker.setShouldStopCallback(()->false);
        Job job=new Job(); job.setHref("https://www.zhipin.com/job_detail/one.html"); job.setJobName("Java"); job.setJobInfo("负责大模型开发");
        var method=Boss.class.getDeclaredMethod("resumeSubmission",String.class,Job.class); method.setAccessible(true);
        assertEquals(false,method.invoke(worker,"Java",job));
        assertEquals(1,collected.size()); // No Page or journal is supplied: any attempt to open/click would fail this test.
        assertEquals("负责大模型开发",collected.getFirst().description());
    }
    @ParameterizedTest @ValueSource(booleans={false,true})
    void evaluatesRealJobDescriptionBeforeOpeningChatAndKeepsQuotaForMatchesOnly(boolean allowed) throws Exception {
        var source=new SQLiteDataSource(); source.setUrl("jdbc:sqlite:"+directory.resolve("advanced.db"));
        var quotas=new DailyQuotaService(source); quotas.initialize(); var ledger=new BossDeliveryLedger(source,quotas); ledger.initialize();
        var profiles=new JobProfileService(source); profiles.initialize();
        var profile=new JobProfile("开发",List.of("boss"),List.of("Java"),"北京",null,true,List.of(),List.of(),"您好",false,false,
                new JobProfile.BossFilters(List.of("隔离"),List.of("大模型"),List.of("销售")));
        long version=profiles.save(profile); var snapshot=profiles.get(version);
        var config=new BossConfig(); config.setConfirmedProfile(snapshot); config.setSayHi("您好");
        var worker=new Boss(mock(BossService.class),null,null); worker.setConfig(config); worker.setLedger(ledger);
        String task=ledger.createTask(20); ledger.bindConfig(task,config); worker.setTaskId(task); worker.setShouldStopCallback(()->false);
        var screenings=new CandidateScreeningService(source); screenings.initialize();
        worker.setScreeningCallback((job,result)->screenings.record(task,version,job,result));
        worker.setExternalGuard(job->profiles.assertAllowed(snapshot,job));
        var clicks=new AtomicInteger();
        try(var context=browser.newContext();var sleeps=mockStatic(PlaywrightUtil.class)) {
            context.exposeBinding("recordAction",(s,a)->{clicks.incrementAndGet();return null;});
            context.route("**/*",route->route.fulfill(new Route.FulfillOptions().setContentType("text/html; charset=utf-8").setBody(DETAIL)));
            var page=context.newPage(); page.setContent("<a class='more-job-btn' href='/job_detail/one.html'>详情</a>"); worker.setPage(page);
            var job=new Job(); job.setHref("https://www.zhipin.com/job_detail/one.html"); job.setJobName("Java开发"); job.setCompanyName("隔离公司"); job.setJobArea("北京");
            job.setJobInfo(allowed?"负责大模型开发，无需销售":"负责大模型开发，承担销售指标");
            var method=Boss.class.getDeclaredMethod("resumeSubmission",String.class,Job.class); method.setAccessible(true);
            var opened=new AtomicInteger(); context.onPage(p->opened.incrementAndGet());
            assertEquals(allowed,method.invoke(worker,"Java",job));
            assertEquals(allowed?2:0,clicks.get()); assertEquals(allowed?1:0,ledger.summary().get("todayAttempts"));
            assertEquals(allowed?1:0,opened.get());
            assertEquals(1,screenings.records(1,null,null,allowed?"passed":"excluded").get("total"));
        }
    }
    @Test void recruiterCooldownSkipsTheSecondJobBeforeAnyExternalAction() throws Exception {
        var source=new SQLiteDataSource(); source.setUrl("jdbc:sqlite:"+directory.resolve("cooldown.db"));
        var quotas=new DailyQuotaService(source); quotas.initialize(); var ledger=new BossDeliveryLedger(source,quotas); ledger.initialize();
        var contacts=new ContactGuard(source); contacts.initialize();
        var profiles=new JobProfileService(source); profiles.initialize();
        var profile=new JobProfile("开发",List.of("boss"),List.of("Java"),"北京",null,true,List.of(),List.of(),"您好",false,false,null,new JobProfile.ContactPolicy(30));
        long version=profiles.save(profile); var snapshot=profiles.get(version);
        var config=new BossConfig(); config.setConfirmedProfile(snapshot); config.setSayHi("您好");
        var worker=new Boss(mock(BossService.class),null,null); worker.setConfig(config); worker.setLedger(ledger);
        String task=ledger.createTask(20); ledger.bindConfig(task,config); worker.setTaskId(task); worker.setShouldStopCallback(()->false);
        var screenings=new CandidateScreeningService(source); screenings.initialize();
        worker.setScreeningCallback((job,result)->screenings.record(task,version,job,result));
        worker.setCooldownCheck(job->contacts.evaluate(snapshot.rules().contactPolicy(),job));
        worker.setExternalGuard(job->profiles.assertAllowed(snapshot,job));
        contacts.record(new JobCandidate("boss","older-job","Java开发","隔离公司","北京","15-20K","https://www.zhipin.com/job_detail/older.html","负责大模型开发",null).withHr("hr-1",null));
        var clicks=new AtomicInteger();
        try(var context=browser.newContext();var sleeps=mockStatic(PlaywrightUtil.class)) {
            context.exposeBinding("recordAction",(s,a)->{clicks.incrementAndGet();return null;});
            context.route("**/*",route->route.fulfill(new Route.FulfillOptions().setContentType("text/html; charset=utf-8").setBody(DETAIL)));
            var page=context.newPage(); page.setContent("<a class='more-job-btn' href='/job_detail/one.html'>详情</a>"); worker.setPage(page);
            var job=new Job(); job.setHref("https://www.zhipin.com/job_detail/one.html"); job.setJobName("Java开发"); job.setCompanyName("隔离公司"); job.setJobArea("北京");
            job.setJobInfo("负责大模型开发"); job.setHrId("hr-1");
            var method=Boss.class.getDeclaredMethod("resumeSubmission",String.class,Job.class); method.setAccessible(true);
            assertEquals(false,method.invoke(worker,"Java",job),()->"同一 HR 换岗位应被跳过:"+ledger.attempts(1));
            assertEquals(0,clicks.get(),()->"冷却期命中后不应有任何外发动作:"+ledger.attempts(1));
            assertEquals(0,ledger.summary().get("todayAttempts"));
            assertEquals(1,screenings.records(1,null,null,"excluded").get("total"));
        }
    }
    @Test void zhilianPreviewParsesRealDomWithoutApplyingOrWritingJobs() throws Exception {
        var service=mock(ZhilianService.class); var worker=new ZhiLian(service); var config=new ZhilianConfig(); worker.setConfig(config);
        var collected=new ArrayList<JobCandidate>(); worker.setPreviewCallback(collected::add); worker.setShouldStopCallback(()->false);
        var clicks=new AtomicInteger();
        try(var context=browser.newContext()) {
            context.route("**/*",route -> route.abort());
            context.exposeBinding("recordAction",(s,a)-> { clicks.incrementAndGet(); return null; });
            var page=context.newPage(); worker.setPage(page);
            page.setContent("""
                <div class="job-list-panel"><div class="job-card job-card--active">
                  <span class="job-card__title-main">Java开发</span><span class="job-card__company-name">隔离公司</span>
                  <span class="job-card__salary">15-20K</span><span class="job-card__location">北京</span>
                </div></div>
                <a class="job-company-info__view-all" href="https://jobs.zhaopin.com/jobdetail/one.htm">详情</a>
                <button class="job-detail-summary__apply" onclick="window.recordAction('apply')">立即投递</button>
                """);
            var method=ZhiLian.class.getDeclaredMethod("deliverModernPage"); method.setAccessible(true);
            assertEquals(true,method.invoke(worker));
            assertEquals(1,collected.size()); assertEquals(0,clicks.get()); verifyNoInteractions(service);
        }
    }
    @ParameterizedTest @ValueSource(booleans={false,true})
    void bossCollectOnlyParsesDetailJsonAndKeepsOriginalPlatformFilterEvidence(boolean legacyBlocked) throws Exception {
        var service=mock(BossService.class);
        when(service.getBlackJobs()).thenReturn(legacyBlocked?Set.of("Java"):Set.of());
        when(service.getBlackCompanies()).thenReturn(Set.of()); when(service.getBlackRecruiters()).thenReturn(Set.of());
        var worker=new Boss(service,null,null); var config=new BossConfig(); config.setDebugger(true);
        config.setCityCode(List.of("101010100")); config.setKeywords(List.of("Java")); worker.setConfig(config);
        var collected=new ArrayList<JobCandidate>(); worker.setPreviewCallback(collected::add); worker.setShouldStopCallback(()->false); worker.setProgressCallback((m,c,t)->{});
        var requests=new ArrayList<String>();
        try(var context=browser.newContext();var sleeps=mockStatic(PlaywrightUtil.class)) {
            context.route("**/*",route->{
                requests.add(route.request().url());
                boolean detail=route.request().url().contains("/wapi/zpgeek/job/detail.json");
                route.fulfill(new Route.FulfillOptions().setContentType(detail?"application/json":"text/html; charset=utf-8").setBody(detail?"""
                    {"zpData":{"jobInfo":{"encryptId":"fixture-id","jobName":"Java开发","salaryDesc":"15-20K","locationName":"北京","postDescription":"负责大模型开发，无需销售"},"brandComInfo":{"brandName":"隔离公司"},"bossInfo":{"title":"招聘经理","activeTimeDesc":"今日活跃"}}}
                    """:"""
                    <ul class="rec-job-list"><li class="job-card-box" onclick="fetch('/wapi/zpgeek/job/detail.json')">Java开发</li></ul><a class="more-job-btn" href="/job_detail/fixture-id.html">详情</a><div id="footer">底部</div>
                    """));
            });
            worker.setPage(context.newPage()); worker.prepare(); assertEquals(0,worker.execute());
            assertEquals(1,collected.size()); var candidate=collected.getFirst();
            assertEquals("fixture-id",candidate.jobId()); assertEquals("负责大模型开发，无需销售",candidate.description());
            assertEquals(legacyBlocked,candidate.platformFilterReason().contains("黑名单"));
            var profile=new JobProfile("开发",List.of("boss"),List.of("Java"),"北京",null,true,List.of(),List.of(),"您好",false,false,
                    new JobProfile.BossFilters(List.of("隔离"),List.of("大模型"),List.of("销售")));
            assertEquals(!legacyBlocked,profile.match(candidate).eligible());
            assertTrue(requests.stream().allMatch(u->u.contains("/web/geek/jobs") || u.contains("/wapi/zpgeek/job/detail.json")));
            verify(service,never()).insertBossJob(any());
        }
    }
    @Test void unavailableScreeningJournalStopsBeforeAnyExternalAction() throws Exception {
        var config=new BossConfig(); config.setConfirmedProfile(new JobProfileService.Snapshot(1,
                new JobProfile("开发",List.of("boss"),List.of("Java"),"北京",null,true,List.of(),List.of(),"您好",false,false),1L,""));
        var worker=new Boss(null,null,null); worker.setConfig(config); worker.setTaskId("task"); worker.setLedger(mock(BossDeliveryLedger.class));
        worker.setShouldStopCallback(()->false); worker.setScreeningCallback((j,m)->{throw new IllegalStateException("fixture database unavailable");});
        var listing=mock(Page.class); var more=mock(Locator.class); when(listing.locator("a.more-job-btn")).thenReturn(more); when(more.count()).thenReturn(1); when(more.first()).thenReturn(more); when(more.getAttribute("href")).thenReturn("/job_detail/one.html"); worker.setPage(listing);
        var job=new Job(); job.setJobName("Java开发"); job.setCompanyName("隔离公司"); job.setJobArea("北京");
        var method=Boss.class.getDeclaredMethod("resumeSubmission",String.class,Job.class); method.setAccessible(true);
        var failure=assertThrows(java.lang.reflect.InvocationTargetException.class,()->method.invoke(worker,"Java",job));
        assertTrue(failure.getCause().getMessage().contains("筛选记录保存失败")); assertEquals("FAILED",worker.getOutcome());
        verify(listing,never()).context();
    }
}
