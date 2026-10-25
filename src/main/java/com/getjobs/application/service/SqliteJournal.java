package com.getjobs.application.service;

import javax.sql.DataSource;
import java.sql.*;
import java.util.*;

/** Small JDBC boundary shared by the new journals; check-and-reserve holds SQLite's write lock. */
final class SqliteJournal {
    interface Work<T> { T run(Connection c) throws Exception; }
    static <T> T transaction(DataSource source, Work<T> work) {
        try (Connection c = source.getConnection()) {
            try (var s = c.createStatement()) { s.execute("PRAGMA busy_timeout=15000"); s.execute("BEGIN IMMEDIATE"); }
            try {
                T value = work.run(c);
                try (var s = c.createStatement()) { s.execute("COMMIT"); }
                return value;
            } catch (Exception e) {
                try (var s = c.createStatement()) { s.execute("ROLLBACK"); } catch (SQLException rollback) { e.addSuppressed(rollback); }
                if (e instanceof RuntimeException runtime) throw runtime;
                throw new IllegalStateException("投递记录读写失败，已停止操作", e);
            }
        } catch (SQLException e) { throw new IllegalStateException("投递记录读写失败，已停止操作", e); }
    }
    static int execute(Connection c, String sql, Object... args) throws SQLException {
        try (var s = c.prepareStatement(sql)) {
            bind(s, args); return s.executeUpdate();
        }
    }
    static List<Map<String, Object>> rows(Connection c, String sql, Object... args) throws SQLException {
        try (var s = c.prepareStatement(sql)) {
            bind(s, args);
            try (var r = s.executeQuery()) {
                var result = new ArrayList<Map<String, Object>>();
                while (r.next()) {
                    var row = new LinkedHashMap<String, Object>();
                    for (int i = 1; i <= r.getMetaData().getColumnCount(); i++) row.put(r.getMetaData().getColumnLabel(i), r.getObject(i));
                    result.add(row);
                }
                return result;
            }
        }
    }
    static Map<String, Object> row(Connection c, String sql, Object... args) throws SQLException {
        var found = rows(c, sql, args);
        if (found.isEmpty()) throw new IllegalArgumentException("未找到记录");
        return found.getFirst();
    }
    static int count(Connection c, String sql, Object... args) throws SQLException {
        return ((Number) row(c, sql, args).values().iterator().next()).intValue();
    }
    static boolean table(Connection c, String name) throws SQLException {
        return count(c, "SELECT count(*) FROM sqlite_master WHERE type='table' AND name=?", name) > 0;
    }
    private static void bind(PreparedStatement s, Object[] args) throws SQLException {
        for (int i = 0; i < args.length; i++) s.setObject(i + 1, args[i]);
    }
}
