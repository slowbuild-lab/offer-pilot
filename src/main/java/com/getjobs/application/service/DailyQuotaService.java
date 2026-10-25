package com.getjobs.application.service;

import jakarta.annotation.PostConstruct;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import javax.sql.DataSource;
import java.sql.*;
import java.time.*;
import java.util.*;
import static com.getjobs.application.service.SqliteJournal.*;

@Service
public class DailyQuotaService {
    public static class Exhausted extends IllegalStateException { public Exhausted() { super("今日额度已变化，未发起投递"); } }
    private final DataSource source;
    private final Clock clock;
    @Autowired public DailyQuotaService(DataSource source) { this(source, Clock.systemUTC()); }
    public DailyQuotaService(DataSource source, Clock clock) { this.source = source; this.clock = clock; }
    @PostConstruct public void initialize() {
        transaction(source, c -> {
            execute(c, "CREATE TABLE IF NOT EXISTS delivery_daily_limits (platform TEXT PRIMARY KEY, effective_limit INTEGER NOT NULL, pending_limit INTEGER, pending_day TEXT)");
            return null;
        });
    }
    public String day() { return clock.instant().atZone(ZoneId.of("Asia/Shanghai")).toLocalDate().toString(); }
    public static void validatePlatform(String platform) {
        if (!Set.of("boss", "zhilian").contains(platform)) throw new IllegalArgumentException("仅支持 BOSS 和智联");
    }
    public Map<String, Object> get(String platform) {
        return transaction(source, c -> policy(c, platform, "boss".equals(platform) ? 20 : 50));
    }
    public Map<String, Object> update(String platform, int limit) {
        if (limit < 1 || limit > 500) throw new IllegalArgumentException("每日上限必须为 1 到 500 的整数");
        return transaction(source, c -> {
            var current = policy(c, platform, "boss".equals(platform) ? 20 : 50);
            int effective = ((Number) current.get("effectiveLimit")).intValue();
            if (limit <= effective) execute(c, "UPDATE delivery_daily_limits SET effective_limit=?,pending_limit=NULL,pending_day=NULL WHERE platform=?", limit, platform);
            else execute(c, "UPDATE delivery_daily_limits SET pending_limit=?,pending_day=? WHERE platform=?", limit,
                    LocalDate.parse(day()).plusDays(1).toString(), platform);
            return policy(c, platform, effective);
        });
    }
    Map<String, Object> policy(Connection c, String platform, int fallback) throws SQLException {
        validatePlatform(platform);
        int initial = fallback;
        if ("zhilian".equals(platform) && table(c, "zhilian_config") &&
                rows(c, "PRAGMA table_info(zhilian_config)").stream().anyMatch(r -> "max_attempts_per_day".equals(r.get("name")))) {
            var existing = rows(c, "SELECT max_attempts_per_day AS quota FROM zhilian_config LIMIT 1");
            if (!existing.isEmpty() && existing.getFirst().get("quota") instanceof Number n && n.intValue() >= 1 && n.intValue() <= 500) initial = n.intValue();
        }
        execute(c, "INSERT OR IGNORE INTO delivery_daily_limits(platform,effective_limit) VALUES (?,?)", platform, initial);
        execute(c, "UPDATE delivery_daily_limits SET effective_limit=pending_limit,pending_limit=NULL,pending_day=NULL WHERE platform=? AND pending_day<=?", platform, day());
        var value = row(c, "SELECT * FROM delivery_daily_limits WHERE platform=?", platform);
        var result = new LinkedHashMap<String, Object>();
        result.put("platform", platform); result.put("effectiveLimit", value.get("effective_limit"));
        result.put("pendingLimit", value.get("pending_limit")); result.put("pendingDay", value.get("pending_day")); result.put("day", day());
        return result;
    }
    int limit(Connection c, String platform, int fallback) throws SQLException {
        return ((Number) policy(c, platform, fallback).get("effectiveLimit")).intValue();
    }
}
