package com.getjobs.application.service;

import jakarta.annotation.PostConstruct;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import javax.sql.DataSource;
import java.time.Clock;
import java.util.*;
import static com.getjobs.application.service.SqliteJournal.*;

@Service
public class BossDeliveryLedger {
    public static class LimitReached extends IllegalStateException { public LimitReached(String message) { super(message); } }
    private final DataSource source;
    private final DailyQuotaService quota;
    private final Clock clock;
    @Autowired public BossDeliveryLedger(DataSource source, DailyQuotaService quota) { this(source, quota, Clock.systemUTC()); }
    public BossDeliveryLedger(DataSource source, DailyQuotaService quota, Clock clock) { this.source=source; this.quota=quota; this.clock=clock; }
    @PostConstruct public void initialize() {
        transaction(source, c -> {
            execute(c, "CREATE TABLE IF NOT EXISTS boss_tasks (id TEXT PRIMARY KEY, task_limit INTEGER NOT NULL, state TEXT NOT NULL, reason TEXT, started_at INTEGER NOT NULL)");
            if (rows(c,"PRAGMA table_info(boss_tasks)").stream().noneMatch(r -> "config_json".equals(r.get("name")))) execute(c,"ALTER TABLE boss_tasks ADD COLUMN config_json TEXT");
            if (rows(c,"PRAGMA table_info(boss_tasks)").stream().noneMatch(r -> "profile_version".equals(r.get("name")))) execute(c,"ALTER TABLE boss_tasks ADD COLUMN profile_version INTEGER");
            execute(c, "CREATE TABLE IF NOT EXISTS boss_attempts (id TEXT PRIMARY KEY,task_id TEXT NOT NULL,job_id TEXT NOT NULL,job_title TEXT,company TEXT,job_link TEXT,state TEXT NOT NULL,reason TEXT,initiated_at INTEGER NOT NULL,local_day TEXT NOT NULL)");
            execute(c, "CREATE INDEX IF NOT EXISTS idx_boss_attempt_job ON boss_attempts(job_id,state)");
            execute(c, "CREATE INDEX IF NOT EXISTS idx_boss_attempt_day ON boss_attempts(local_day,state)");
            execute(c, "CREATE TABLE IF NOT EXISTS boss_actions (attempt_id TEXT NOT NULL,action TEXT NOT NULL,state TEXT NOT NULL,started_at INTEGER NOT NULL,finished_at INTEGER,PRIMARY KEY(attempt_id,action))");
            execute(c, "UPDATE boss_actions SET state='UNKNOWN' WHERE state='SENT'");
            execute(c, "UPDATE boss_attempts SET state='UNKNOWN',reason='上次运行中断，需核实' WHERE state IN ('RESERVED','SENT')");
            execute(c, "UPDATE boss_tasks SET state='PAUSED',reason='上次运行中断，请核实后操作' WHERE state='RUNNING'");
            return null;
        });
    }
    public String createTask(int taskLimit) {
        if (taskLimit < 1 || taskLimit > 500) throw new IllegalArgumentException("本轮上限必须为 1 到 500");
        return transaction(source, c -> {
            String id=UUID.randomUUID().toString();
            execute(c,"INSERT INTO boss_tasks(id,task_limit,state,reason,started_at) VALUES (?,?,'RUNNING','',?)",id,taskLimit,clock.millis()); return id;
        });
    }
    public void bindConfig(String task,com.getjobs.worker.boss.BossConfig config) {
        transaction(source,c -> {
            execute(c,"UPDATE boss_tasks SET config_json=?,profile_version=? WHERE id=? AND state='RUNNING'",
                    new com.fasterxml.jackson.databind.ObjectMapper().writeValueAsString(config),config.getConfirmedProfile()==null?null:config.getConfirmedProfile().version(),task);
            return null;
        });
    }
    public String reserve(String task, String job, String title, String company, String link) {
        if (job == null || job.isBlank() || job.length()>300) throw new IllegalArgumentException("岗位编号缺失或过长");
        return transaction(source, c -> {
            var running=row(c,"SELECT * FROM boss_tasks WHERE id=?",task);
            if (!"RUNNING".equals(running.get("state"))) throw new IllegalStateException("任务不在运行中");
            if (count(c,"SELECT count(*) FROM boss_attempts WHERE job_id=? AND state!='RELEASED'",job)>0) return null;
            if (table(c,"boss_data") && count(c,"SELECT count(*) FROM boss_data WHERE encrypt_id=? AND delivery_status='已投递'",job)>0) return null;
            if (count(c,"SELECT count(*) FROM boss_attempts WHERE task_id=? AND state!='RELEASED'",task)>=((Number)running.get("task_limit")).intValue()) throw new LimitReached("已达到本轮岗位尝试上限");
            if (count(c,"SELECT count(*) FROM boss_attempts WHERE local_day=? AND state!='RELEASED'",quota.day())>=quota.limit(c,"boss",20)) throw new LimitReached("已达到今日岗位尝试上限");
            String id=UUID.randomUUID().toString();
            execute(c,"INSERT INTO boss_attempts VALUES (?,?,?,?,?,?,'RESERVED','',?,?)",id,task,job,title,company,link,clock.millis(),quota.day());
            return id;
        });
    }
    public void startAction(String id, String action) {
        if (!Set.of("CHAT","TEXT","IMAGE").contains(action)) throw new IllegalArgumentException("未知动作");
        transaction(source, c -> {
            var attempt=row(c,"SELECT a.*,t.state AS task_state FROM boss_attempts a JOIN boss_tasks t ON t.id=a.task_id WHERE a.id=?",id);
            if (!"RUNNING".equals(attempt.get("task_state")) || !Set.of("RESERVED","SENT","CONFIRMED").contains(attempt.get("state"))) throw new IllegalStateException("任务或尝试已结束，禁止外发");
            if (count(c,"SELECT count(*) FROM boss_actions WHERE attempt_id=? AND action=?",id,action)>0) throw new IllegalStateException("动作已记录，禁止重复发送");
            boolean first=count(c,"SELECT count(*) FROM boss_actions WHERE attempt_id=?",id)==0;
            if (first) {
                int others=count(c,"SELECT count(*) FROM boss_attempts WHERE local_day=? AND state!='RELEASED' AND id!=?",quota.day(),id);
                if (others>=quota.limit(c,"boss",20)) throw new LimitReached("今日额度已变化，禁止新增岗位");
                execute(c,"UPDATE boss_attempts SET local_day=? WHERE id=?",quota.day(),id);
            }
            execute(c,"INSERT INTO boss_actions VALUES (?,?,'SENT',?,NULL)",id,action,clock.millis());
            execute(c,"UPDATE boss_attempts SET state='SENT' WHERE id=?",id); return null;
        });
    }
    public void finishAction(String id, String action, boolean confirmed) {
        transaction(source,c -> {
            if (execute(c,"UPDATE boss_actions SET state=?,finished_at=? WHERE attempt_id=? AND action=? AND state='SENT'",confirmed?"CONFIRMED":"UNKNOWN",clock.millis(),id,action)!=1) throw new IllegalStateException("动作状态不一致");
            boolean allConfirmed=count(c,"SELECT count(*) FROM boss_actions WHERE attempt_id=? AND state NOT IN ('CONFIRMED','CANCELLED')",id)==0;
            execute(c,"UPDATE boss_attempts SET state=?,reason=? WHERE id=?",allConfirmed?"CONFIRMED":"UNKNOWN",allConfirmed?"页面已确认已记录的动作":"动作结果待核实，不自动重投",id); return null;
        });
    }
    public void cancelBeforeExternal(String id) {
        transaction(source,c -> {
            if (count(c,"SELECT count(*) FROM boss_actions WHERE attempt_id=?",id)>0) throw new IllegalStateException("已有可能外发动作，不能释放额度");
            execute(c,"UPDATE boss_attempts SET state='RELEASED',reason='提交前取消' WHERE id=? AND state='RESERVED'",id); return null;
        });
    }
    /** Only the immediate caller before click can prove that this armed action was never issued. */
    public void cancelActionBeforeClick(String id,String action) {
        transaction(source,c -> {
            if (execute(c,"UPDATE boss_actions SET state='CANCELLED',finished_at=? WHERE attempt_id=? AND action=? AND state='SENT'",clock.millis(),id,action)!=1) throw new IllegalStateException("不能取消已发出或已结束的动作");
            int issued=count(c,"SELECT count(*) FROM boss_actions WHERE attempt_id=? AND state!='CANCELLED'",id);
            int uncertain=count(c,"SELECT count(*) FROM boss_actions WHERE attempt_id=? AND state IN ('SENT','UNKNOWN')",id);
            execute(c,"UPDATE boss_attempts SET state=?,reason='动作在点击前取消' WHERE id=?",issued==0?"RELEASED":uncertain>0?"UNKNOWN":"CONFIRMED",id);
            return null;
        });
    }
    public void settle(String id) {
        transaction(source,c -> {
            execute(c,"UPDATE boss_actions SET state='UNKNOWN' WHERE attempt_id=? AND state='SENT'",id);
            execute(c,"UPDATE boss_attempts SET state='UNKNOWN',reason='动作未获可靠确认' WHERE id=? AND state='SENT'",id);
            execute(c,"UPDATE boss_attempts SET state='RELEASED',reason='未发生外发' WHERE id=? AND state='RESERVED'",id); return null;
        });
    }
    public void finishTask(String task, String state, String reason) {
        transaction(source,c -> {
            execute(c,"UPDATE boss_actions SET state='UNKNOWN' WHERE state='SENT' AND attempt_id IN (SELECT id FROM boss_attempts WHERE task_id=?)",task);
            execute(c,"UPDATE boss_attempts SET state='UNKNOWN',reason='任务已结束，结果待核实' WHERE task_id=? AND state IN ('RESERVED','SENT')",task);
            execute(c,"UPDATE boss_tasks SET state=?,reason=? WHERE id=?",state,reason,task); return null;
        });
    }
    public void resolve(String id, boolean completed) {
        transaction(source,c -> {
            if (count(c,"SELECT count(*) FROM boss_tasks WHERE state='RUNNING'")>0) throw new IllegalStateException("请先停止 BOSS 任务，再核实结果");
            if (execute(c,"UPDATE boss_attempts SET state=?,reason=? WHERE id=? AND state='UNKNOWN'",completed?"USER_CONFIRMED":"NOT_DELIVERED",completed?"本人核实已完成":"本人核实未完成，保留次数且不自动重投",id)!=1) throw new IllegalArgumentException("只能核实待确认记录");
            return null;
        });
    }
    public Map<String,Object> summary() {
        return transaction(source,c -> {
            int reserved=count(c,"SELECT count(*) FROM boss_attempts WHERE local_day=? AND state='RESERVED'",quota.day());
            int used=count(c,"SELECT count(*) FROM boss_attempts WHERE local_day=? AND state NOT IN ('RESERVED','RELEASED')",quota.day());
            int limit=quota.limit(c,"boss",20);
            var result=new LinkedHashMap<String,Object>(quota.policy(c,"boss",20));
            result.put("todayAttempts",used); result.put("reservedCount",reserved); result.put("remainingToday",Math.max(0,limit-used-reserved));
            result.put("unknownCount",count(c,"SELECT count(*) FROM boss_attempts WHERE state='UNKNOWN'")); return result;
        });
    }
    public List<Map<String,Object>> attempts(int page) {
        if (page<1 || page>10000) throw new IllegalArgumentException("页码必须为 1 到 10000");
        return transaction(source,c -> {
            var found=rows(c,"SELECT a.*,t.profile_version FROM boss_attempts a JOIN boss_tasks t ON t.id=a.task_id ORDER BY a.initiated_at DESC,a.rowid DESC LIMIT 20 OFFSET ?",(page-1)*20);
            for (var item:found) item.put("actions",rows(c,"SELECT * FROM boss_actions WHERE attempt_id=? ORDER BY started_at,action",item.get("id")));
            return found;
        });
    }
}
