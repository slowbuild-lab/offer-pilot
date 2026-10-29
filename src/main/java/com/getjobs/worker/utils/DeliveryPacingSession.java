package com.getjobs.worker.utils;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.getjobs.application.service.DeliveryPacingService;
import com.microsoft.playwright.*;
import java.net.URI;
import java.time.*;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.function.*;

/** Runs on one platform's browser thread. Waiting never owns a database transaction or another browser. */
public final class DeliveryPacingSession implements AutoCloseable {
    public static class Halt extends RuntimeException {public Halt(String message){super(message);}}
    private final DeliveryPacingService service;
    private final String platform;
    private final BooleanSupplier stopped;
    private final Consumer<String> progress;
    private final LongConsumer testWait;
    private final Consumer<Response> responseListener=this::observe;
    private Page page;
    private BrowserContext context;
    private String token;
    private volatile String storageFailure;
    private volatile boolean transientReadFailure;
    private long lastAction;
    public DeliveryPacingSession(DeliveryPacingService service,String platform,BooleanSupplier stopped,Consumer<String> progress){this(service,platform,stopped,progress,null);}
    public DeliveryPacingSession(DeliveryPacingService service,String platform,BooleanSupplier stopped,Consumer<String> progress,LongConsumer testWait){
        DeliveryPacingService.platform(platform);this.service=service;this.platform=platform;this.stopped=stopped;this.progress=progress;this.testWait=testWait;
    }
    public void attach(Page page){
        this.page=page;
        if(context==null){context=page.context();context.onResponse(responseListener);service.begin(platform);}
    }
    public void usePage(Page current){page=current;}
    private long now(){return service.currentTime();}
    public void check(){
        if(stopped.getAsBoolean()||Thread.currentThread().isInterrupted())throw new Halt("任务已停止");
        if(storageFailure!=null)throw new Halt(storageFailure);
        if(page!=null&&!page.isClosed()){
            String risk=pageRisk(page);
            if(!risk.isEmpty()){
                long earliest=risk.contains("今日额度")?Instant.ofEpochMilli(now()).atZone(ZoneId.of("Asia/Shanghai")).toLocalDate().plusDays(1).atStartOfDay(ZoneId.of("Asia/Shanghai")).toInstant().toEpochMilli():0;
                service.pause(platform,risk,earliest);throw new Halt(risk);
            }
        }
        // The persistent pause can have been set by a response arriving on another page in this context.
        var state=service.persistentState(platform);
        if(!state.get("paused").toString().isEmpty())throw new Halt(state.get("paused").toString());
    }
    private DeliveryPacingService.Decision await(Supplier<DeliveryPacingService.Decision> action){
        String previous="";
        while(true){
            check();var d=action.get();
            if("READY".equals(d.kind())||"DUPLICATE".equals(d.kind()))return d;
            if(!"WAIT".equals(d.kind()))throw new Halt(d.message());
            String message=d.message()+"，预计 "+Instant.ofEpochMilli(d.until()).atZone(ZoneId.of("Asia/Shanghai")).format(DateTimeFormatter.ofPattern("HH:mm:ss"))+" 可继续";
            if(!message.equals(previous)){progress.accept(message);previous=message;}
            waitMillis(Math.min(500,Math.max(1,d.until()-now())));
        }
    }
    private void waitMillis(long ms){
        if(testWait!=null)testWait.accept(ms);
        else if(page!=null&&!page.isClosed())page.waitForTimeout(ms);
        else try{Thread.sleep(ms);}catch(InterruptedException e){Thread.currentThread().interrupt();throw new Halt("任务已停止");}
    }
    public void read(boolean list){await(()->service.read(platform,list));}
    /** Retry only read operations. An armed external action is never passed to this method. */
    public void read(boolean list,Runnable operation){
        for(int retry=0;;retry++){
            read(list);
            try{transientReadFailure=false;operation.run();check();if(transientReadFailure)throw new PlaywrightException("平台只读请求临时失败");return;}
            catch(Halt e){throw e;}
            catch(PlaywrightException e){
                check();
                if(retry>=2){service.pause(platform,"只读页面连续加载失败，请检查后恢复",0);throw new Halt("只读页面连续加载失败，已暂停");}
                long until=now()+(retry==0?30000:60000);
                progress.accept("只读加载失败，退避后重试（"+(retry+1)+"/2）");
                while(now()<until){check();waitMillis(Math.min(500,until-now()));}
            }
        }
    }
    public boolean open(String job,String title,String link){
        if(token!=null)throw new IllegalStateException("上一组外发尚未收尾");
        var permit=await(()->service.reserve(platform,job,title,link));token=permit.token();lastAction=0;
        return "READY".equals(permit.kind());
    }
    public void arm(){check();if(token==null)throw new IllegalStateException("没有外发资格");service.markSent(token);lastAction=now();}
    public void nextAction(){
        if(token==null)throw new IllegalStateException("没有外发资格");
        long until=lastAction+service.policy(platform).actionIntervalSeconds()*1000L;
        while(now()<until){check();waitMillis(Math.min(500,until-now()));}
        check();lastAction=now();
    }
    public void complete(boolean confirmed){if(token!=null){service.finish(token,confirmed);token=null;}}
    public void cancel(){if(token!=null){service.cancel(token);token=null;}}
    public void pause(String reason){service.pause(platform,reason,0);throw new Halt(reason);}
    private void observe(Response response){
        try{
            if(!critical(platform,response.url()))return;
            int status=response.status();String message="";
            if(status==429)message="平台请求限频，请人工检查后恢复";
            else if(status==401)message="平台登录已失效，请重新登录后恢复";
            else if(status==403)message="平台核心请求被拒绝，请人工检查";
            else if(status>=500){transientReadFailure=true;return;}
            else if(status==200&&response.headers().getOrDefault("content-type","").contains("json")){
                String body=response.text();
                if(body.length()<1000000){
                    JsonNode root=new ObjectMapper().readTree(body);var pieces=new ArrayList<String>();
                    for(JsonNode node:List.of(root,root.path("data")))for(String key:List.of("message","msg","errorMsg","errmsg"))
                        if(node.path(key).isTextual())pieces.add(node.path(key).asText());
                    message=riskText(String.join(" ",pieces));
                    if(message.isEmpty())for(JsonNode node:List.of(root,root.path("data")))for(String key:List.of("code","errorCode","status")){
                        String code=node.path(key).asText("");
                        if(Set.of("429","401","403").contains(code))message="平台业务响应要求限制访问，请人工检查";
                    }
                }
            }
            if(!message.isEmpty())service.pause(platform,message,retryAfter(response.headers().get("retry-after"),now()));
        }catch(Exception e){storageFailure="平台响应检查或保护记录不可用，已停止操作";}
    }
    static boolean critical(String platform,String url){
        try{
            URI u=URI.create(url);String host=Optional.ofNullable(u.getHost()).orElse("");
            String domain=switch(platform){case "boss"->"zhipin.com";case "zhilian"->"zhaopin.com";case "51job"->"51job.com";case "liepin"->"liepin.com";default->"invalid";};
            if(!host.equals(domain)&&!host.endsWith("."+domain))return false;
            String path=Optional.ofNullable(u.getPath()).orElse("").toLowerCase(Locale.ROOT);
            return !path.matches(".*\\.(png|jpg|svg|css|js|woff2?)$")&&path.matches(".*(wapi|api|search|job|chat|deliver|apply|greet|login|passport|verify|safe).*");
        }catch(Exception e){return false;}
    }
    public static long retryAfter(String value,long now){
        if(value==null||value.isBlank())return 0;
        try{
            if(value.trim().matches("\\d+")){
                var seconds=new java.math.BigInteger(value.trim());var until=seconds.multiply(java.math.BigInteger.valueOf(1000)).add(java.math.BigInteger.valueOf(now));
                return until.min(java.math.BigInteger.valueOf(Long.MAX_VALUE)).longValue();
            }
            return Math.max(now,ZonedDateTime.parse(value.trim(),DateTimeFormatter.RFC_1123_DATE_TIME).toInstant().toEpochMilli());
        }catch(Exception e){return 0;}
    }
    static String riskText(String text){
        if(text.matches("(?s).*(安全验证|安全校验|访问验证|滑动验证|验证码|操作(?:过于)?频繁|请求(?:过于)?频繁|访问(?:过于)?频繁|异常访问|账号.*(封禁|限制)|账户.*限制).*"))return "平台要求验证或限制访问，请人工处理";
        if(text.matches("(?s).*(今日.*(上限|太多)|投递次数.*上限|休息一下明天).*"))return "平台提示今日额度已用完";
        if(text.matches("(?s).*(重新登录|登录已失效|登录已过期|请先登录).*"))return "平台登录已失效，请重新登录";
        return "";
    }
    static String pageRisk(Page page){
        String url=page.url();
        if(url.matches(".*(/safe/|/verify|/security|security-check|captcha).*"))return "平台要求安全验证，请人工处理";
        if(url.matches(".*(passport\\.|/login(?:[/?#]|$)).*"))return "平台登录已失效，请重新登录";
        String content=(String)page.evaluate("""
            () => {
              const visible=e=>{if(!e.getClientRects().length)return false;for(let n=e;n;n=n.parentElement){const s=getComputedStyle(n);if(s.display==='none'||s.visibility==='hidden'||s.opacity==='0')return false}return true};
              if([...document.querySelectorAll('[class*="captcha"],[id*="captcha"],[class*="geetest"],iframe[src*="captcha"]')].some(visible))return '安全验证';
              return [...document.querySelectorAll('[role="dialog"],[role="alert"],.a-modal,.a-message,.el-dialog,.el-message,.ant-modal,.ant-message')].filter(visible).map(e=>e.innerText||'').join(' ');
            }
            """);
        String risk=riskText(content);if(!risk.isEmpty())return risk;
        if(!content.isBlank()&&!content.matches("(?s).*(投递成功|申请成功|已向对方发送简历和打招呼语|沟通成功).*"))return "页面出现未识别提示，请人工检查后恢复";
        return "";
    }
    @Override public void close(){try{cancel();}finally{if(context!=null)context.offResponse(responseListener);}}
}
