package com.getjobs.application.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PostConstruct;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import javax.sql.DataSource;
import java.sql.Connection;
import java.time.*;
import java.util.*;
import java.util.concurrent.ThreadLocalRandom;
import java.util.function.IntUnaryOperator;
import static com.getjobs.application.service.SqliteJournal.*;

/** Durable limits for tool-initiated reads and external actions, independent of success statistics. */
@Service
public class DeliveryPacingService {
    public static final List<String> PLATFORMS=List.of("boss","zhilian","51job","liepin");
    public record Policy(int minIntervalSeconds,int extraDelaySeconds,int readIntervalSeconds,int pageIntervalSeconds,
                         int startupSeconds,int restEvery,int continuousMinutes,int restMinutes,int dailyLimit,
                         int detailBudget,int actionIntervalSeconds) {
        public Policy {
            range(minIntervalSeconds,30,86400);range(extraDelaySeconds,0,3600);range(readIntervalSeconds,1,3600);
            range(pageIntervalSeconds,1,86400);range(startupSeconds,1,3600);range(restEvery,1,100);
            range(continuousMinutes,1,240);range(restMinutes,1,240);range(dailyLimit,1,500);
            range(detailBudget,1,2000);range(actionIntervalSeconds,1,300);
        }
        public static Policy defaults(){return new Policy(120,60,10,30,15,10,30,15,20,200,5);}
        public Policy withLimit(int limit){return new Policy(minIntervalSeconds,extraDelaySeconds,readIntervalSeconds,pageIntervalSeconds,startupSeconds,restEvery,continuousMinutes,restMinutes,limit,detailBudget,actionIntervalSeconds);}
    }
    public record GlobalPolicy(int minIntervalSeconds,int dailyLimit) {
        public GlobalPolicy {range(minIntervalSeconds,1,3600);range(dailyLimit,1,2000);}
    }
    public record Decision(String kind,long until,String message,String token) {
        static Decision ready(String token){return new Decision("READY",0,"可以执行",token);}
    }
    private final DataSource source;
    private final Clock clock;
    private final IntUnaryOperator random;
    private final ObjectMapper json=new ObjectMapper();
    private static final ZoneId ZONE=ZoneId.of("Asia/Shanghai");
    @Autowired public DeliveryPacingService(DataSource source){this(source,Clock.systemUTC(),bound->ThreadLocalRandom.current().nextInt(bound));}
    public DeliveryPacingService(DataSource source,Clock clock,IntUnaryOperator random){this.source=source;this.clock=clock;this.random=random;}
    @PostConstruct public void initialize(){
        transaction(source,c->{
            execute(c,"CREATE TABLE IF NOT EXISTS pacing_policies (scope TEXT PRIMARY KEY,version INTEGER NOT NULL,json TEXT NOT NULL,pending_limit INTEGER,pending_day TEXT)");
            execute(c,"CREATE TABLE IF NOT EXISTS pacing_state (platform TEXT PRIMARY KEY,next_delivery INTEGER NOT NULL DEFAULT 0,next_read INTEGER NOT NULL DEFAULT 0,next_list INTEGER NOT NULL DEFAULT 0,start_until INTEGER NOT NULL DEFAULT 0,rest_until INTEGER NOT NULL DEFAULT 0,session_start INTEGER NOT NULL DEFAULT 0,streak INTEGER NOT NULL DEFAULT 0,paused TEXT NOT NULL DEFAULT '',not_before INTEGER NOT NULL DEFAULT 0,last_clock INTEGER NOT NULL DEFAULT 0,read_day TEXT NOT NULL DEFAULT '',read_count INTEGER NOT NULL DEFAULT 0)");
            execute(c,"CREATE TABLE IF NOT EXISTS pacing_attempts (id TEXT PRIMARY KEY,platform TEXT NOT NULL,job_id TEXT NOT NULL,title TEXT,link TEXT,state TEXT NOT NULL,local_day TEXT NOT NULL,started_at INTEGER NOT NULL,reason TEXT NOT NULL DEFAULT '')");
            execute(c,"CREATE UNIQUE INDEX IF NOT EXISTS idx_pacing_job ON pacing_attempts(platform,job_id) WHERE state!='CANCELLED'");
            execute(c,"CREATE INDEX IF NOT EXISTS idx_pacing_day ON pacing_attempts(local_day,state)");
            execute(c,"CREATE TABLE IF NOT EXISTS pacing_global (id INTEGER PRIMARY KEY CHECK(id=1),owner TEXT,next_delivery INTEGER NOT NULL DEFAULT 0)");
            if(rows(c,"PRAGMA table_info(pacing_state)").stream().noneMatch(r->"list_count".equals(r.get("name"))))execute(c,"ALTER TABLE pacing_state ADD COLUMN list_count INTEGER NOT NULL DEFAULT 0");
            if(rows(c,"PRAGMA table_info(pacing_state)").stream().noneMatch(r->"last_finished".equals(r.get("name"))))execute(c,"ALTER TABLE pacing_state ADD COLUMN last_finished INTEGER NOT NULL DEFAULT 0");
            execute(c,"INSERT OR IGNORE INTO pacing_global(id) VALUES (1)");
            for(String p:PLATFORMS){
                execute(c,"INSERT OR IGNORE INTO pacing_policies(scope,version,json) VALUES (?,1,?)",p,json.writeValueAsString(Policy.defaults()));
                execute(c,"INSERT OR IGNORE INTO pacing_state(platform) VALUES (?)",p);
            }
            execute(c,"INSERT OR IGNORE INTO pacing_policies(scope,version,json) VALUES ('global',1,?)",json.writeValueAsString(new GlobalPolicy(30,40)));
            // Only an action armed as SENT may have reached the platform. Reserved permits were never clicked.
            execute(c,"UPDATE pacing_state SET paused='上次外发中断，结果待核实，请人工检查' WHERE platform IN (SELECT platform FROM pacing_attempts WHERE state='SENT')");
            execute(c,"UPDATE pacing_attempts SET state='UNKNOWN',reason='运行中断，禁止自动重投' WHERE state='SENT'");
            execute(c,"UPDATE pacing_attempts SET state='CANCELLED',reason='运行中断，尚未登记外发' WHERE state='RESERVED'");
            execute(c,"UPDATE pacing_global SET owner=NULL WHERE id=1");return null;
        });
    }
    private static void range(int n,int min,int max){if(n<min||n>max)throw new IllegalArgumentException("节奏参数必须在 "+min+" 到 "+max+" 之间");}
    public static void platform(String platform){if(!PLATFORMS.contains(platform))throw new IllegalArgumentException("不支持的平台");}
    private long now(){return clock.millis();}
    public long currentTime(){return now();}
    public Map<String,Object> persistentState(String p){platform(p);return transaction(source,c->state(c,p));}
    private String day(){return clock.instant().atZone(ZONE).toLocalDate().toString();}
    private long number(Map<String,Object> row,String key){return ((Number)row.get(key)).longValue();}
    private Map<String,Object> state(Connection c,String p) throws Exception{return row(c,"SELECT * FROM pacing_state WHERE platform=?",p);}
    private Map<String,Object> policyRow(Connection c,String p) throws Exception{
        var r=row(c,"SELECT * FROM pacing_policies WHERE scope=?",p);
        if(r.get("pending_day")!=null&&r.get("pending_day").toString().compareTo(day())<=0){
            String value="global".equals(p)?json.writeValueAsString(new GlobalPolicy(json.readValue(r.get("json").toString(),GlobalPolicy.class).minIntervalSeconds(),((Number)r.get("pending_limit")).intValue()))
                    :json.writeValueAsString(json.readValue(r.get("json").toString(),Policy.class).withLimit(((Number)r.get("pending_limit")).intValue()));
            execute(c,"UPDATE pacing_policies SET json=?,pending_limit=NULL,pending_day=NULL,version=version+1 WHERE scope=?",value,p);
            r=row(c,"SELECT * FROM pacing_policies WHERE scope=?",p);
        }
        return r;
    }
    private Policy policy(Connection c,String p) throws Exception{return json.readValue(policyRow(c,p).get("json").toString(),Policy.class);}
    private GlobalPolicy global(Connection c) throws Exception{return json.readValue(policyRow(c,"global").get("json").toString(),GlobalPolicy.class);}
    public Policy policy(String p){platform(p);return transaction(source,c->policy(c,p));}
    public void begin(String p){platform(p);transaction(source,c->{
        var s=state(c,p);long n=now();var pol=policy(c,p);
        execute(c,"UPDATE pacing_state SET start_until=MAX(start_until,?),session_start=CASE WHEN session_start=0 THEN ? ELSE session_start END WHERE platform=?",n+pol.startupSeconds()*1000L,n,p);return null;
    });}
    private Decision common(Connection c,String p,Policy policy) throws Exception{
        var s=state(c,p);long n=now();
        if(n<number(s,"last_clock")-5000){execute(c,"UPDATE pacing_state SET paused='本机时间倒退，请核对系统时间',not_before=MAX(not_before,?) WHERE platform=?",number(s,"last_clock"),p);s=state(c,p);}
        execute(c,"UPDATE pacing_state SET last_clock=MAX(last_clock,?) WHERE platform=?",n,p);
        if(!s.get("paused").toString().isEmpty())return new Decision("PAUSED",number(s,"not_before"),s.get("paused").toString(),null);
        if(number(s,"rest_until")>n)return new Decision("WAIT",number(s,"rest_until"),"中途休息",null);
        if(number(s,"streak")>=policy.restEvery()||number(s,"session_start")>0&&n-number(s,"session_start")>=policy.continuousMinutes()*60000L){
            long until=n+policy.restMinutes()*60000L;
            execute(c,"UPDATE pacing_state SET rest_until=?,session_start=?,streak=0 WHERE platform=?",until,until,p);
            return new Decision("WAIT",until,"连续处理时间已到，中途休息",null);
        }
        if(number(s,"start_until")>n)return new Decision("WAIT",number(s,"start_until"),"启动缓冲",null);
        return null;
    }
    public Decision read(String p,boolean list){platform(p);return transaction(source,c->{
        var policy=policy(c,p);var blocked=common(c,p,policy);if(blocked!=null)return blocked;
        var s=state(c,p);long n=now();String column=list?"next_list":"next_read";
        if(number(s,column)>n)return new Decision("WAIT",number(s,column),list?"翻页或关键词间隔":"岗位详情读取间隔",null);
        int used=day().equals(s.get("read_day"))?((Number)s.get("read_count")).intValue():0;
        int listed=day().equals(s.get("read_day"))?((Number)s.get("list_count")).intValue():0;
        if(used>=policy.detailBudget())return new Decision("LIMIT",0,"今日详情读取预算已用完",null);
        if(list&&listed>=50)return new Decision("LIMIT",0,"今日自动列表加载已达 50 次，停止继续刷新",null);
        execute(c,"UPDATE pacing_state SET "+column+"=?,session_start=CASE WHEN session_start=0 THEN ? ELSE session_start END,read_count=?,list_count=?,read_day=? WHERE platform=?",
                n+(list?policy.pageIntervalSeconds():policy.readIntervalSeconds())*1000L,n,used+(list?0:1),listed+(list?1:0),day(),p);
        return Decision.ready(null);
    });}
    public Decision reserve(String p,String job,String title,String link){platform(p);
        if(job==null||job.isBlank()||job.length()>300)throw new IllegalArgumentException("缺少可靠岗位编号，禁止外发");
        return transaction(source,c->{
            if(count(c,"SELECT count(*) FROM pacing_attempts WHERE platform=? AND job_id=? AND state!='CANCELLED'",p,job)>0)return new Decision("DUPLICATE",0,"岗位已发起或结果待核实，不重投",null);
            var policy=policy(c,p);var blocked=common(c,p,policy);if(blocked!=null)return blocked;
            if(used(c,p)>=limit(c,p,policy.dailyLimit())||used(c,null)>=global(c).dailyLimit())return new Decision("LIMIT",0,"达到平台或四平台合计每日预算",null);
            var s=state(c,p);var g=row(c,"SELECT * FROM pacing_global WHERE id=1");long n=now();
            if(g.get("owner")!=null)return new Decision("WAIT",n+500,"等待其他平台完成当前投递",null);
            long until=Math.max(number(s,"next_delivery"),number(g,"next_delivery"));
            if(until>n)return new Decision("WAIT",until,"平台或全局投递间隔",null);
            String token=UUID.randomUUID().toString();
            execute(c,"INSERT INTO pacing_attempts(id,platform,job_id,title,link,state,local_day,started_at) VALUES (?,?,?,?,?,'RESERVED',?,?)",token,p,job,title,link,day(),n);
            execute(c,"UPDATE pacing_global SET owner=? WHERE id=1",token);return Decision.ready(token);
        });
    }
    public void markSent(String token){transaction(source,c->{
        var a=row(c,"SELECT * FROM pacing_attempts WHERE id=? AND state='RESERVED'",token);String p=a.get("platform").toString();var policy=policy(c,p);
        var blocked=common(c,p,policy);if(blocked!=null)throw new IllegalStateException(blocked.message());
        if(!Objects.equals(row(c,"SELECT owner FROM pacing_global WHERE id=1").get("owner"),token))throw new IllegalStateException("投递资格已失效");
        if(used(c,p)>=limit(c,p,policy.dailyLimit())||used(c,null)>=global(c).dailyLimit())throw new IllegalStateException("每日预算已变化，未发起投递");
        long n=now();var s=state(c,p);long next=n+1000L*(policy.minIntervalSeconds()+random.applyAsInt(policy.extraDelaySeconds()+1));
        int streak=((Number)s.get("streak")).intValue()+1;boolean rest=streak>=policy.restEvery();long restUntil=rest?n+policy.restMinutes()*60000L:number(s,"rest_until");
        execute(c,"UPDATE pacing_attempts SET state='SENT',local_day=?,started_at=? WHERE id=?",day(),n,token);
        execute(c,"UPDATE pacing_state SET next_delivery=MAX(next_delivery,?),streak=?,rest_until=?,session_start=CASE WHEN ? THEN ? WHEN session_start=0 THEN ? ELSE session_start END WHERE platform=?",next,rest?0:streak,restUntil,rest,restUntil,n,p);
        execute(c,"UPDATE pacing_global SET next_delivery=MAX(next_delivery,?) WHERE id=1",n+global(c).minIntervalSeconds()*1000L);return null;
    });}
    public void finish(String token,boolean confirmed){settle(token,confirmed,false);}
    public void cancel(String token){settle(token,false,true);}
    private void settle(String token,boolean confirmed,boolean cancel){if(token==null)return;transaction(source,c->{
        var found=rows(c,"SELECT * FROM pacing_attempts WHERE id=? AND state IN ('RESERVED','SENT')",token);
        if(!found.isEmpty()){
            var a=found.getFirst();boolean sent="SENT".equals(a.get("state"));String state=!sent?"CANCELLED":confirmed&&!cancel?"CONFIRMED":"UNKNOWN";
            execute(c,"UPDATE pacing_attempts SET state=?,reason=? WHERE id=?",state,"UNKNOWN".equals(state)?"外发结果待核实，禁止自动重投":"",token);
            if(sent){
                String p=a.get("platform").toString();
                // Confirmation can be slow. Never let it shorten the gap after the actual browser click.
                execute(c,"UPDATE pacing_state SET next_delivery=MAX(next_delivery,?),last_finished=MAX(last_finished,?) WHERE platform=?",now()+policy(c,p).minIntervalSeconds()*1000L,now(),p);
                execute(c,"UPDATE pacing_global SET next_delivery=MAX(next_delivery,?) WHERE id=1",now()+global(c).minIntervalSeconds()*1000L);
            }
            if("UNKNOWN".equals(state))execute(c,"UPDATE pacing_state SET paused='外发结果待核实，请人工检查后恢复' WHERE platform=?",a.get("platform"));
        }
        execute(c,"UPDATE pacing_global SET owner=NULL WHERE id=1 AND owner=?",token);return null;
    });}
    public void pause(String p,String reason,long notBefore){platform(p);transaction(source,c->{
        execute(c,"UPDATE pacing_state SET paused=?,not_before=MAX(not_before,?) WHERE platform=?",reason==null?"平台需要人工处理":reason.substring(0,Math.min(reason.length(),500)),notBefore,p);return null;
    });}
    public void resume(String p){platform(p);transaction(source,c->{
        var s=state(c,p);if(number(s,"not_before")>now())throw new IllegalStateException("尚未到平台允许的最早恢复时间");
        if(count(c,"SELECT count(*) FROM pacing_attempts a JOIN pacing_global g ON g.owner=a.id WHERE a.platform=?",p)>0)throw new IllegalStateException("外发尚未结束，请先停止任务");
        execute(c,"UPDATE pacing_state SET paused='',not_before=0,last_clock=? WHERE platform=?",now(),p);return null;
    });}
    private int limit(Connection c,String p,int configured) throws Exception{
        if(table(c,"delivery_daily_limits")){
            var rows=rows(c,"SELECT effective_limit,pending_limit,pending_day FROM delivery_daily_limits WHERE platform=?",p);
            if(!rows.isEmpty()){
                var r=rows.getFirst();int old=((Number)r.get("effective_limit")).intValue();
                if(r.get("pending_day")!=null&&r.get("pending_day").toString().compareTo(day())<=0)old=((Number)r.get("pending_limit")).intValue();
                return Math.min(old,configured);
            }
        }
        return configured;
    }
    public int used(String p){if(p!=null)platform(p);return transaction(source,c->used(c,p));}
    private int used(Connection c,String p) throws Exception{
        int total=p==null?count(c,"SELECT count(*) FROM pacing_attempts WHERE local_day=? AND state IN ('SENT','CONFIRMED','UNKNOWN')",day())
                :count(c,"SELECT count(*) FROM pacing_attempts WHERE local_day=? AND platform=? AND state IN ('SENT','CONFIRMED','UNKNOWN')",day(),p);
        for(String old:List.of("boss","zhilian"))if((p==null||old.equals(p))&&table(c,old+"_attempts"))
            total+=count(c,"SELECT count(*) FROM "+old+"_attempts a WHERE a.local_day=? AND a.state!='RELEASED' AND NOT EXISTS (SELECT 1 FROM pacing_attempts b WHERE b.platform=? AND b.job_id=a.job_id AND b.state!='CANCELLED' AND b.local_day=a.local_day)",day(),old);
        return total;
    }
    public Map<String,Object> snapshot(){return transaction(source,c->{
        var result=new LinkedHashMap<String,Object>();var platforms=new LinkedHashMap<String,Object>();
        for(String p:PLATFORMS){var r=new LinkedHashMap<String,Object>(policyRow(c,p));r.remove("json");r.put("policy",policy(c,p));r.put("state",state(c,p));r.put("used",used(c,p));r.put("effectiveLimit",limit(c,p,policy(c,p).dailyLimit()));platforms.put(p,r);}
        var g=new LinkedHashMap<String,Object>(policyRow(c,"global"));g.remove("json");g.put("policy",global(c));g.put("used",used(c,null));
        result.put("platforms",platforms);result.put("global",g);result.put("serverTime",now());result.put("day",day());return result;
    });}
    public void update(String p,long version,Policy requested){platform(p);transaction(source,c->{
        var old=policy(c,p);savePolicy(c,p,version,json.writeValueAsString(requested.dailyLimit()>old.dailyLimit()?requested.withLimit(old.dailyLimit()):requested),requested.dailyLimit()>old.dailyLimit()?requested.dailyLimit():null);
        var s=state(c,p);long n=now();
        if(requested.minIntervalSeconds()>old.minIntervalSeconds()){
            var times=rows(c,"SELECT MAX(started_at) AS last FROM pacing_attempts WHERE platform=? AND state IN ('SENT','CONFIRMED','UNKNOWN')",p);
            if(times.getFirst().get("last") instanceof Number last)execute(c,"UPDATE pacing_state SET next_delivery=MAX(next_delivery,?) WHERE platform=?",Math.max(last.longValue(),number(s,"last_finished"))+requested.minIntervalSeconds()*1000L,p);
        }
        if(requested.readIntervalSeconds()>old.readIntervalSeconds())execute(c,"UPDATE pacing_state SET next_read=MAX(next_read,?) WHERE platform=?",n+requested.readIntervalSeconds()*1000L,p);
        if(requested.pageIntervalSeconds()>old.pageIntervalSeconds())execute(c,"UPDATE pacing_state SET next_list=MAX(next_list,?) WHERE platform=?",n+requested.pageIntervalSeconds()*1000L,p);
        if(requested.restMinutes()>old.restMinutes()&&number(s,"rest_until")>n){
            long until=number(s,"rest_until")+(requested.restMinutes()-old.restMinutes())*60000L;
            execute(c,"UPDATE pacing_state SET rest_until=?,session_start=MAX(session_start,?) WHERE platform=?",until,until,p);
        }
        return null;
    });}
    public void updateGlobal(long version,GlobalPolicy requested){transaction(source,c->{
        var old=global(c);savePolicy(c,"global",version,json.writeValueAsString(new GlobalPolicy(requested.minIntervalSeconds(),Math.min(old.dailyLimit(),requested.dailyLimit()))),requested.dailyLimit()>old.dailyLimit()?requested.dailyLimit():null);
        if(requested.minIntervalSeconds()>old.minIntervalSeconds())execute(c,"UPDATE pacing_global SET next_delivery=MAX(next_delivery,?) WHERE id=1",now()+requested.minIntervalSeconds()*1000L);
        return null;
    });}
    private void savePolicy(Connection c,String scope,long version,String value,Integer pending) throws Exception{
        policyRow(c,scope);
        if(execute(c,"UPDATE pacing_policies SET json=?,pending_limit=?,pending_day=?,version=version+1 WHERE scope=? AND version=?",value,pending,pending==null?null:LocalDate.parse(day()).plusDays(1).toString(),scope,version)!=1)throw new IllegalStateException("配置已变化，请刷新后再保存");
    }
    public Map<String,Object> records(String p,int page){if(p!=null)platform(p);if(page<1||page>10000)throw new IllegalArgumentException("页码超出范围");return transaction(source,c->{
        String where=p==null?"":" WHERE platform=?";Object[] args=p==null?new Object[]{}:new Object[]{p};
        int total=count(c,"SELECT count(*) FROM pacing_attempts"+where,args);
        var params=new ArrayList<Object>(Arrays.asList(args));params.add((page-1)*20);
        return Map.of("items",rows(c,"SELECT * FROM pacing_attempts"+where+" ORDER BY started_at DESC,rowid DESC LIMIT 20 OFFSET ?",params.toArray()),"total",total,"page",page,"pageSize",20,"hasNext",page*20L<total);
    });}
}
