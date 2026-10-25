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
        Job job=new Job(); job.setHref("https://www.zhipin.com/job_detail/one.html"); job.setJobName("Java");
        var method=Boss.class.getDeclaredMethod("resumeSubmission",String.class,Job.class); method.setAccessible(true);
        assertEquals(false,method.invoke(worker,"Java",job));
        assertEquals(1,collected.size()); // No Page or journal is supplied: any attempt to open/click would fail this test.
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
}
