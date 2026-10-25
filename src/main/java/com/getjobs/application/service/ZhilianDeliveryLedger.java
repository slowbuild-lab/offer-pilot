package com.getjobs.application.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.getjobs.application.entity.ZhilianJobDataEntity;
import com.getjobs.worker.zhilian.ZhilianConfig;
import jakarta.annotation.PostConstruct;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import javax.sql.DataSource;
import java.sql.*;
import java.time.Clock;
import java.time.ZoneId;
import java.util.*;

/** Local intent journal. An unknown external result is never treated as an unsent application. */
@Service
public class ZhilianDeliveryLedger {
    public static class StorageException extends IllegalStateException {
        StorageException(Exception cause) { super("投递记录不可用，已停止操作", cause); }
    }
    public enum Decision { RESERVED, DUPLICATE, TASK_LIMIT, DAY_LIMIT }
    public record Reservation(String attemptId, Decision decision) {}
    private static final ZoneId ZONE = ZoneId.of("Asia/Shanghai");
    private final DataSource dataSource;
    private final Clock clock;
    private final ObjectMapper json = new ObjectMapper();
    @Autowired private DailyQuotaService quota;

    @Autowired
    public ZhilianDeliveryLedger(DataSource dataSource) { this(dataSource, Clock.systemUTC()); }
    public ZhilianDeliveryLedger(DataSource dataSource, Clock clock) {
        this.dataSource = dataSource;
        this.clock = clock;
    }

    @PostConstruct
    public void initialize() {
        if (quota == null) { quota = new DailyQuotaService(dataSource, clock); quota.initialize(); }
        transaction(c -> {
            execute(c, "CREATE TABLE IF NOT EXISTS zhilian_tasks (id TEXT PRIMARY KEY, config_json TEXT NOT NULL, " +
                    "task_limit INTEGER NOT NULL, day_limit INTEGER NOT NULL, state TEXT NOT NULL, reason TEXT, started_at INTEGER NOT NULL, block_day TEXT)");
            execute(c, "CREATE TABLE IF NOT EXISTS zhilian_attempts (id TEXT PRIMARY KEY, task_id TEXT NOT NULL, " +
                    "job_id TEXT NOT NULL, job_title TEXT, company TEXT, job_link TEXT, state TEXT NOT NULL, reason TEXT, " +
                    "initiated_at INTEGER NOT NULL, local_day TEXT NOT NULL, UNIQUE(task_id, job_id))");
            execute(c, "CREATE INDEX IF NOT EXISTS idx_zhilian_attempt_day ON zhilian_attempts(local_day,state)");
            execute(c, "CREATE INDEX IF NOT EXISTS idx_zhilian_attempt_job ON zhilian_attempts(job_id,state)");
            execute(c, "UPDATE zhilian_attempts SET state='UNKNOWN',reason='上次运行中断，需核实' WHERE state IN ('RESERVED','SENT')");
            execute(c, "UPDATE zhilian_tasks SET state='PAUSED',reason='上次运行中断，请核实后继续' WHERE state IN ('STARTING','RUNNING','STOPPING')");
            return null;
        });
    }

    public String createTask(ZhilianConfig config) {
        return transaction(c -> {
            String id = UUID.randomUUID().toString();
            execute(c, "INSERT INTO zhilian_tasks (id,config_json,task_limit,day_limit,state,reason,started_at) VALUES (?,?,?,?,?,?,?)", id, json.writeValueAsString(config),
                    config.getMaxAttemptsPerTask(), config.getMaxAttemptsPerDay(), "RUNNING", "", clock.millis());
            return id;
        });
    }

    public Reservation reserve(String taskId, ZhilianJobDataEntity job) {
        if (job.getJobId() == null || job.getJobId().isBlank()) throw new IllegalArgumentException("岗位编号缺失");
        return transaction(c -> {
            var task = row(c, "SELECT * FROM zhilian_tasks WHERE id=?", taskId);
            if (!"RUNNING".equals(task.get("state"))) throw new IllegalStateException("任务不在运行中");
            boolean legacyDelivered = count(c, "SELECT count(*) FROM sqlite_master WHERE type='table' AND name='zhilian_data'") > 0
                    && count(c, "SELECT count(*) FROM zhilian_data WHERE job_id=? AND delivery_status='已投递'", job.getJobId()) > 0;
            if (legacyDelivered || count(c, "SELECT count(*) FROM zhilian_attempts WHERE job_id=? AND " +
                    "(task_id=? OR state IN ('RESERVED','SENT','UNKNOWN','CONFIRMED','NOT_DELIVERED'))", job.getJobId(), taskId) > 0) {
                return new Reservation(null, Decision.DUPLICATE);
            }
            if (count(c, "SELECT count(*) FROM zhilian_attempts WHERE task_id=? AND state!='RELEASED'", taskId)
                    >= ((Number) task.get("task_limit")).intValue()) return new Reservation(null, Decision.TASK_LIMIT);
            if (dailyCount(c) >= quota.limit(c, "zhilian", ((Number) task.get("day_limit")).intValue())) return new Reservation(null, Decision.DAY_LIMIT);
            String id = UUID.randomUUID().toString();
            execute(c, "INSERT INTO zhilian_attempts VALUES (?,?,?,?,?,?,?,?,?,?)", id, taskId, job.getJobId(),
                    job.getJobTitle(), job.getCompanyName(), job.getJobLink(), "RESERVED", "", clock.millis(), day());
            return new Reservation(id, Decision.RESERVED);
        });
    }

    public void markSent(String id) {
        boolean allowed = transaction(c -> {
            var attempt = row(c, "SELECT * FROM zhilian_attempts WHERE id=? AND state='RESERVED'", id);
            int others = count(c, "SELECT count(*) FROM zhilian_attempts WHERE local_day=? AND state!='RELEASED' AND id!=?", day(), id);
            int limit = quota.limit(c, "zhilian", 50);
            if (others >= limit) {
                execute(c, "UPDATE zhilian_attempts SET state='RELEASED',reason='提交前额度变化' WHERE id=?", id);
                return false;
            }
            execute(c, "UPDATE zhilian_attempts SET state='SENT',local_day=?,reason='即将点击投递，结果待确认' WHERE id=?", day(), id);
            return true;
        });
        if (!allowed) throw new DailyQuotaService.Exhausted();
    }
    /** Only the caller immediately before click may use this: no browser click has been issued. */
    public void cancelBeforeClick(String id) { transition(id, "SENT", "RELEASED", "点击前已停止，未发起投递"); }
    public void confirm(String id) { transition(id, "SENT", "CONFIRMED", "页面已确认投递成功"); }
    public void release(String id) { transition(id, "RESERVED", "RELEASED", "已停止，尚未点击投递"); }
    public void unknown(String id, String reason) { transition(id, "SENT", "UNKNOWN", reason); }

    private void transition(String id, String from, String to, String reason) {
        transaction(c -> {
            if (execute(c, "UPDATE zhilian_attempts SET state=?,reason=? WHERE id=? AND state=?", to, reason, id, from) != 1)
                throw new IllegalStateException("投递记录状态不一致，已停止操作");
            return null;
        });
    }

    public void resolve(String id, boolean delivered) {
        transaction(c -> {
            var attempt = row(c, "SELECT * FROM zhilian_attempts WHERE id=?", id);
            if (!"UNKNOWN".equals(attempt.get("state"))) throw new IllegalArgumentException("只能核实结果待确认的岗位");
            execute(c, "UPDATE zhilian_attempts SET state=?,reason=? WHERE id=?", delivered ? "CONFIRMED" : "NOT_DELIVERED",
                    delivered ? "用户核实已投递" : "用户核实未成功，保留本次尝试次数", id);
            if (delivered && count(c, "SELECT count(*) FROM sqlite_master WHERE name='zhilian_data'") > 0)
                execute(c, "UPDATE zhilian_data SET delivery_status='已投递' WHERE job_id=?", attempt.get("job_id"));
            return null;
        });
    }

    public List<Map<String, Object>> unknownAttempts() {
        return unknownAttempts(1);
    }

    public List<Map<String, Object>> unknownAttempts(int page) {
        if (page < 1 || page > 10000) throw new IllegalArgumentException("页码超出范围");
        return transaction(c -> rows(c, "SELECT id,job_id AS jobId,job_title AS jobTitle,company,job_link AS jobLink," +
                "reason,initiated_at AS initiatedAt FROM zhilian_attempts WHERE state='UNKNOWN' ORDER BY initiated_at DESC, rowid DESC LIMIT 50 OFFSET ?", (page - 1) * 50));
    }

    public Map<String, Object> latestTask() {
        return transaction(c -> {
            var rows = rows(c, "SELECT id,state,reason FROM zhilian_tasks ORDER BY started_at DESC, rowid DESC LIMIT 1");
            return rows.isEmpty() ? Map.of() : rows.getFirst();
        });
    }

    /**
     * 最近的智联任务，供作战台的"最近任务"列表使用（只读）。
     * BOSS 目前没有任务账本，这里只返回智联的记录，不伪造其它平台的历史。
     */
    public List<Map<String, Object>> recentTasks(int limit) {
        int size = Math.max(1, Math.min(50, limit));
        return transaction(c -> rows(c, "SELECT id,state,reason,started_at AS startedAt FROM zhilian_tasks " +
                "ORDER BY started_at DESC, rowid DESC LIMIT ?", size));
    }

    public ZhilianConfig loadConfig(String taskId) {
        return transaction(c -> json.readValue((String) row(c, "SELECT config_json FROM zhilian_tasks WHERE id=?", taskId)
                .get("config_json"), ZhilianConfig.class));
    }

    public void resumeTask(String taskId) {
        transaction(c -> {
            if (execute(c, "UPDATE zhilian_tasks SET state='RUNNING',reason='' WHERE id=? AND state='PAUSED'", taskId) != 1)
                throw new IllegalArgumentException("没有可继续的暂停任务");
            return null;
        });
    }

    public void finishTask(String taskId, String state, String reason) {
        transaction(c -> {
            execute(c, "UPDATE zhilian_attempts SET state='UNKNOWN',reason='任务已结束，但投递结果未确认，请核实' " +
                    "WHERE task_id=? AND state IN ('RESERVED','SENT')", taskId);
            execute(c, "UPDATE zhilian_tasks SET state=?,reason=?,block_day=? WHERE id=?", state, reason,
                    "PLATFORM_LIMIT".equals(state) ? day() : null, taskId);
            return null;
        });
    }

    public boolean platformLimitedToday() {
        return transaction(c -> count(c, "SELECT count(*) FROM zhilian_tasks WHERE block_day=?", day()) > 0);
    }

    public Map<String, Object> summary(String taskId, int dayLimit) {
        return transaction(c -> {
            Map<String, Object> result = new LinkedHashMap<>();
            int today = dailyCount(c);
            int effective = quota.limit(c, "zhilian", dayLimit);
            int reserved = count(c, "SELECT count(*) FROM zhilian_attempts WHERE local_day=? AND state='RESERVED'", day());
            result.put("todayAttempts", today - reserved);
            result.put("reservedCount", reserved);
            result.put("effectiveLimit", effective);
            result.put("remainingToday", Math.max(0, effective - today));
            result.put("attemptCount", count(c, "SELECT count(*) FROM zhilian_attempts WHERE task_id=? AND state!='RELEASED'", taskId));
            result.put("confirmedCount", count(c, "SELECT count(*) FROM zhilian_attempts WHERE task_id=? AND state='CONFIRMED'", taskId));
            result.put("unknownCount", count(c, "SELECT count(*) FROM zhilian_attempts WHERE state='UNKNOWN'"));
            result.put("statisticsSince", row(c, "SELECT min(started_at) AS first FROM zhilian_tasks").get("first"));
            return result;
        });
    }

    private int dailyCount(Connection c) throws SQLException {
        return count(c, "SELECT count(*) FROM zhilian_attempts WHERE local_day=? AND state!='RELEASED'", day());
    }
    public List<Map<String,Object>> attempts(int page) {
        if (page < 1 || page > 10000) throw new IllegalArgumentException("页码必须为 1 到 10000");
        return transaction(c -> {
            var found=rows(c,"SELECT a.*,t.config_json FROM zhilian_attempts a JOIN zhilian_tasks t ON t.id=a.task_id ORDER BY a.initiated_at DESC,a.rowid DESC LIMIT 20 OFFSET ?",(page-1)*20);
            for (var item:found) {
                Object raw=item.remove("config_json");
                Long version=null;
                if (raw!=null) {
                    var node=json.readTree(raw.toString()).path("confirmedProfile").path("version");
                    if (node.isIntegralNumber()) version=node.longValue();
                }
                item.put("profile_version",version);
            }
            return found;
        });
    }
    private String day() { return clock.instant().atZone(ZONE).toLocalDate().toString(); }

    private interface Work<T> { T run(Connection c) throws Exception; }
    /**
     * BEGIN IMMEDIATE serializes check-and-reserve even across different JDBC connections.
     * 多平台并行后这里会和其它平台的写入争锁，等待时间放宽到 15 秒；
     * 等不到锁仍然抛 StorageException，由调用方停止投递，绝不吞掉数据库错误继续点投递。
     */
    private <T> T transaction(Work<T> work) {
        try (Connection c = dataSource.getConnection()) {
            try (Statement s = c.createStatement()) { s.execute("PRAGMA busy_timeout=15000"); s.execute("BEGIN IMMEDIATE"); }
            try {
                T result = work.run(c);
                try (Statement s = c.createStatement()) { s.execute("COMMIT"); }
                return result;
            } catch (Exception e) {
                try (Statement s = c.createStatement()) { s.execute("ROLLBACK"); } catch (SQLException rollback) { e.addSuppressed(rollback); }
                if (e instanceof IllegalArgumentException argument) throw argument;
                throw new StorageException(e);
            }
        } catch (SQLException e) { throw new StorageException(e); }
    }

    private int execute(Connection c, String sql, Object... values) throws SQLException {
        try (PreparedStatement s = c.prepareStatement(sql)) {
            bind(s, values); return s.executeUpdate();
        }
    }
    private List<Map<String, Object>> rows(Connection c, String sql, Object... values) throws SQLException {
        try (PreparedStatement s = c.prepareStatement(sql)) {
            bind(s, values);
            try (ResultSet r = s.executeQuery()) {
                List<Map<String, Object>> rows = new ArrayList<>();
                while (r.next()) {
                    Map<String, Object> row = new LinkedHashMap<>();
                    for (int i = 1; i <= r.getMetaData().getColumnCount(); i++) row.put(r.getMetaData().getColumnLabel(i), r.getObject(i));
                    rows.add(row);
                }
                return rows;
            }
        }
    }
    private Map<String, Object> row(Connection c, String sql, Object... values) throws SQLException {
        var rows = rows(c, sql, values);
        if (rows.isEmpty()) throw new IllegalArgumentException("未找到投递记录");
        return rows.getFirst();
    }
    private int count(Connection c, String sql, Object... values) throws SQLException {
        return ((Number) row(c, sql, values).values().iterator().next()).intValue();
    }
    private void bind(PreparedStatement s, Object... values) throws SQLException {
        for (int i = 0; i < values.length; i++) s.setObject(i + 1, values[i]);
    }
}
