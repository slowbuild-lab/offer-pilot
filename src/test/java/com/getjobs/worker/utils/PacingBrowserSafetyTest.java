package com.getjobs.worker.utils;

import com.getjobs.application.service.*;
import com.getjobs.worker.job51.Job51;
import com.getjobs.worker.liepin.Liepin;
import com.microsoft.playwright.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.sqlite.SQLiteDataSource;
import org.springframework.test.util.ReflectionTestUtils;
import java.nio.file.Path;
import java.time.*;
import java.time.Clock;
import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class PacingBrowserSafetyTest {
    static Playwright playwright;static Browser browser;
    @TempDir Path dir;
    static class TestClock extends Clock {
        long time=Instant.parse("2026-10-28T02:00:00Z").toEpochMilli();
        public Instant instant(){return Instant.ofEpochMilli(time);}
        public ZoneId getZone(){return ZoneOffset.UTC;}
        public Clock withZone(ZoneId z){return this;}
    }
    @BeforeAll static void start(){
        System.setProperty("playwright.cli.dir",Path.of("build/patchright-driver").toAbsolutePath().toString());
        playwright=Playwright.create();browser=playwright.chromium().launch(new BrowserType.LaunchOptions().setChannel("chrome").setHeadless(true));
    }
    @AfterAll static void stop(){if(browser!=null)browser.close();if(playwright!=null)playwright.close();}
    private DeliveryPacingService service(TestClock clock){var ds=new SQLiteDataSource();ds.setUrl("jdbc:sqlite:"+dir.resolve("test.db"));var s=new DeliveryPacingService(ds,clock,b->0);s.initialize();return s;}
    private DeliveryPacingSession session(DeliveryPacingService service,String platform,Page page,TestClock clock){
        var s=new DeliveryPacingSession(service,platform,()->false,m->{},ms->clock.time+=ms);s.attach(page);return s;
    }
    @Test void allFourPlatformsStopBeforeAnExternalActionWhenVerificationIsVisible(){
        var clock=new TestClock();var service=service(clock);
        try(var context=browser.newContext()){
            for(String p:DeliveryPacingService.PLATFORMS){
                var page=context.newPage();page.setContent("<div role='dialog'>操作频繁，请完成安全验证</div>");
                try(var s=session(service,p,page,clock)){
                    assertThrows(DeliveryPacingSession.Halt.class,()->s.open("job","title",""));
                    assertEquals(0,service.used(p));
                }
                assertFalse(service.persistentState(p).get("paused").toString().isEmpty());page.close();
            }
        }
    }
    @Test void hiddenCaptchasAndJobDescriptionWordsDoNotCauseFalseRisk(){
        try(var page=browser.newPage()){
            page.setContent("<div class='captcha' style='display:none'>验证</div><article>负责验证码系统的研发</article>");
            assertEquals("",DeliveryPacingSession.pageRisk(page));
        }
    }
    @Test void stopCancelsAWaitingPermitWithoutReservingQuota(){
        var clock=new TestClock();var service=service(clock);var stop=new AtomicBoolean();
        try(var page=browser.newPage();var s=new DeliveryPacingSession(service,"boss",stop::get,m->{},ms->{clock.time+=ms;stop.set(true);})){
            page.setContent("<main>列表</main>");s.attach(page);
            assertThrows(DeliveryPacingSession.Halt.class,()->s.open("job","title",""));assertEquals(0,service.records(null,1).get("total"));
        }
    }
    @Test void job51SubmitsOnlyOneKnownIdAtATimeWithTheHardInterval() {
        var clock=new TestClock();var service=service(clock);var jobService=mock(Job51Service.class);
        try(var page=browser.newPage();var s=session(service,"51job",page,clock)){
            page.setContent("""
                <style>.ick{width:24px;height:24px;border:1px solid black}</style>
                <div class='tabs_in'><button class='p_but'>其他</button><button class='p_but' onclick="submit()">投递</button></div>
                <article><div class='ick' aria-checked='false' onclick="this.setAttribute('aria-checked',this.getAttribute('aria-checked')!=='true')"></div><a href='https://we.51job.com/pc/jobdetail?jobId=101'>Java一</a></article>
                <article><div class='ick' aria-checked='false' onclick="this.setAttribute('aria-checked',this.getAttribute('aria-checked')!=='true')"></div><a href='https://we.51job.com/pc/jobdetail?jobId=102'>Java二</a></article>
                <script>window.submissions=[];function submit(){const ids=[...document.querySelectorAll('.ick[aria-checked="true"]')].map(x=>x.parentElement.querySelector('a').href);window.submissions.push(ids);const d=document.createElement('div');d.className='el-dialog';d.innerHTML='投递成功1个，未投递0个<button aria-label="Close" onclick="this.parentElement.remove()">关闭</button>';document.body.append(d)}</script>
                """);
            var worker=new Job51(jobService);worker.setPage(page);worker.setPacing(s);worker.setShouldStopCallback(()->false);
            ReflectionTestUtils.invokeMethod(worker,"deliverCurrentPage");
            var submissions=(List<?>)page.evaluate("window.submissions");assertEquals(2,submissions.size());
            for(Object item:submissions)assertEquals(1,((List<?>)item).size());
            var items=(List<?>)service.records("51job",1).get("items");
            long first=((Number)((Map<?,?>)items.get(0)).get("started_at")).longValue();long second=((Number)((Map<?,?>)items.get(1)).get("started_at")).longValue();
            assertTrue(Math.abs(first-second)>=120000);assertEquals(2,service.used("51job"));
            verify(jobService).markDelivered(101L);verify(jobService).markDelivered(102L);
        }
    }
    @Test void job51RefusesAmbiguousCardsInsteadOfSendingABatch(){
        var clock=new TestClock();var service=service(clock);
        try(var page=browser.newPage();var s=session(service,"51job",page,clock)){
            page.setContent("<div class='ick'></div><button onclick='window.sent=true'>投递</button>");
            var worker=new Job51(mock(Job51Service.class));worker.setPage(page);worker.setPacing(s);worker.setShouldStopCallback(()->false);
            assertThrows(DeliveryPacingSession.Halt.class,()->ReflectionTestUtils.invokeMethod(worker,"deliverCurrentPage"));
            assertNull(page.evaluate("window.sent"));assertEquals(0,service.used("51job"));
        }
    }
    @Test void liepinCountsAutomaticGreetingAtClickAndNeedsACardLevelConfirmation(){
        var clock=new TestClock();var service=service(clock);var jobs=mock(LiepinService.class);
        try(var page=browser.newPage();var s=session(service,"liepin",page,clock)){
            page.setContent("""
                <div class='jobCardPcContainer'><button data-tlg-elem-id='c_pc_search_job_listcard_chat_btn' data-tlg-ext='%7B%22jobId%22%3A%22123%22%7D' onclick="window.sent=(window.sent||0)+1;this.textContent='继续聊'">聊一聊</button></div>
                """);
            var worker=new Liepin();worker.setPage(page);worker.setPacing(s);worker.setShouldStopCallback(()->false);ReflectionTestUtils.setField(worker,"liepinService",jobs);
            ReflectionTestUtils.invokeMethod(worker,"submitJob");
            assertEquals(1,((Number)page.evaluate("window.sent")).intValue());assertEquals(1,service.used("liepin"));
            assertEquals("CONFIRMED",((Map<?,?>)((List<?>)service.records("liepin",1).get("items")).getFirst()).get("state"));verify(jobs).markDelivered(123L);
        }
    }
    @Test void aCore429PersistsRetryAfterAndNeverResumesByItself(){
        var clock=new TestClock();var service=service(clock);
        try(var context=browser.newContext()){
            context.route("**/*",r->r.fulfill(new Route.FulfillOptions().setContentType("application/json").setStatus(r.request().url().contains("wapi")?429:200).setHeaders(Map.of("Retry-After","60")).setBody("{}")));
            var page=context.newPage();page.navigate("https://www.zhipin.com/jobs");
            try(var s=session(service,"boss",page,clock)){
                page.evaluate("fetch('/wapi/zpgeek/job/detail.json').then(r=>r.text())");
                assertThrows(DeliveryPacingSession.Halt.class,s::check);
                assertEquals(clock.millis()+60000,((Number)service.persistentState("boss").get("not_before")).longValue());
                clock.time+=120000;assertThrows(DeliveryPacingSession.Halt.class,s::check);
            }
        }
    }
    @Test void readOnlyServerFailuresHaveBoundedBackoffWithoutExternalQuota(){
        var clock=new TestClock();var service=service(clock);var count=new java.util.concurrent.atomic.AtomicInteger();
        try(var context=browser.newContext()){
            context.route("**/*",r->{count.incrementAndGet();r.fulfill(new Route.FulfillOptions().setContentType("text/html; charset=utf-8").setStatus(503).setBody("temporarily unavailable"));});
            var page=context.newPage();
            try(var s=session(service,"boss",page,clock)){
                assertThrows(DeliveryPacingSession.Halt.class,()->s.read(true,()->page.navigate("https://www.zhipin.com/api/search")));
                assertEquals(3,count.get());assertEquals(0,service.used("boss"));
                assertFalse(service.persistentState("boss").get("paused").toString().isEmpty());
            }
        }
    }
    @Test void bossPacesChatAndTextAsOneGroupAndKeepsTheDeliveryJournal() {
        var clock=new TestClock();var service=service(clock);var ds=new SQLiteDataSource();ds.setUrl("jdbc:sqlite:"+dir.resolve("test.db"));
        var quota=new DailyQuotaService(ds,clock);quota.initialize();var ledger=new BossDeliveryLedger(ds,quota,clock);ledger.initialize();
        try(var context=browser.newContext();var sleeps=mockStatic(PlaywrightUtil.class)){
            context.route("**/*",r->r.fulfill(new Route.FulfillOptions().setContentType("text/html; charset=utf-8").setBody("""
                <a class='btn-startchat' onclick='window.chat=true'>立即沟通</a><textarea class='input-area'></textarea>
                <button class='btn-send' onclick="const li=document.createElement('li');li.className='message-item item-myself';li.innerHTML='<span class=message-content>您好</span>';document.querySelector('ul').append(li)">发送</button><ul class='im-list'></ul>
                """)));
            var page=context.newPage();page.setContent("<a class='more-job-btn' href='/job_detail/one.html'>详情</a>");
            try(var s=session(service,"boss",page,clock)){
                var worker=new com.getjobs.worker.boss.Boss(mock(BossService.class),null,null);var config=new com.getjobs.worker.boss.BossConfig();config.setSayHi("您好");
                worker.setConfig(config);worker.setPage(page);worker.setPacing(s);worker.setShouldStopCallback(()->false);worker.setLedger(ledger);worker.setTaskId(ledger.createTask(20));
                var job=new Job();job.setHref("https://www.zhipin.com/job_detail/one.html");job.setJobName("Java");
                assertEquals(true,ReflectionTestUtils.invokeMethod(worker,"resumeSubmission","Java",job),()->"legacy="+ledger.attempts(1)+", pacing="+service.records("boss",1));
                assertEquals(1,service.used("boss"));assertEquals(1,ledger.summary().get("todayAttempts"));
                assertEquals("CONFIRMED",((Map<?,?>)((List<?>)service.records("boss",1).get("items")).getFirst()).get("state"));
            }
        }
    }
    @Test void zhilianPacesActualApplyAndKeepsItsOriginalJournal() {
        var clock=new TestClock();var service=service(clock);var ds=new SQLiteDataSource();ds.setUrl("jdbc:sqlite:"+dir.resolve("test.db"));
        var quota=new DailyQuotaService(ds,clock);quota.initialize();var ledger=new ZhilianDeliveryLedger(ds,clock);ledger.initialize();
        var config=new com.getjobs.worker.zhilian.ZhilianConfig();config.setKeywords(List.of("Java"));config.setMaxAttemptsPerDay(20);config.setMaxAttemptsPerTask(20);config.setPopupDelaySeconds(0);
        try(var page=browser.newPage();var s=session(service,"zhilian",page,clock)){
            page.setContent("""
                <div class='job-list-panel'><div class='job-card job-card--active'><span class='job-card__title-main'>Java开发</span><span class='job-card__company-name'>隔离公司</span><span class='job-card__salary'>15-20K</span><span class='job-card__location'>北京</span></div></div>
                <a class='job-company-info__view-all' href='https://www.zhaopin.com/jobdetail/CC000000000J00000000000.htm'>详情</a>
                <button class='job-detail-summary__apply' onclick="this.textContent='已投递'">立即投递</button>
                """);
            var worker=new com.getjobs.worker.zhilian.ZhiLian(mock(ZhilianService.class));worker.setPage(page);worker.setPacing(s);worker.setConfig(config);worker.setLedger(ledger);worker.setTaskId(ledger.createTask(config));worker.setShouldStopCallback(()->false);
            ReflectionTestUtils.invokeMethod(worker,"deliverModernPage");
            assertEquals(1,service.used("zhilian"));
        }
    }
}
