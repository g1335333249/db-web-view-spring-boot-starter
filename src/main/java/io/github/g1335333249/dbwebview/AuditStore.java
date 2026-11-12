package io.github.g1335333249.dbwebview;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Optional shared JDBC audit/security store; falls back to local memory when no audit source is configured. */
public class AuditStore {
    private static final Logger log = LoggerFactory.getLogger(AuditStore.class);
    private final DataSourceRegistry registry;
    private final DbWebViewProperties properties;
    private final Deque<Map<String, Object>> localHistory = new ArrayDeque<>();
    private final ConcurrentHashMap<String, Deque<Long>> localFailures = new ConcurrentHashMap<>();
    private volatile boolean initialized;
    private final AtomicLong lastCleanup = new AtomicLong();

    public AuditStore(DataSourceRegistry registry, DbWebViewProperties properties) {
        this.registry = registry;
        this.properties = properties;
    }

    public void checkAvailable() throws SQLException {
        if (!shared()) return;
        initialize();
        try (Connection ignored = dataSource().getConnection()) { /* fail before a write when the audit pool is unavailable */ }
    }

    public boolean shared() { return properties.getAuditSource() != null && !properties.getAuditSource().trim().isEmpty(); }

    private DataSource dataSource() { return registry.requireInternal(properties.getAuditSource()); }

    public synchronized void initialize() throws SQLException {
        if (!shared() || initialized) return;
        try (Connection connection = dataSource().getConnection()) {
            if (properties.isAuditAutoCreate()) {
                try (Statement statement = connection.createStatement()) {
                    statement.execute("CREATE TABLE IF NOT EXISTS dbwv_audit_event (event_id VARCHAR(36) PRIMARY KEY, occurred_at BIGINT NOT NULL, action_name VARCHAR(32) NOT NULL, source_name VARCHAR(128), namespace_name VARCHAR(128), table_name VARCHAR(128), client_ip VARCHAR(64), result_kind VARCHAR(32), affected BIGINT, row_count BIGINT, elapsed_ms BIGINT, error_text VARCHAR(1000), sql_text TEXT)");
                    statement.execute("CREATE TABLE IF NOT EXISTS dbwv_token_revocation (jti VARCHAR(43) PRIMARY KEY, expires_at BIGINT NOT NULL)");
                    statement.execute("CREATE TABLE IF NOT EXISTS dbwv_login_failure (event_id VARCHAR(36) PRIMARY KEY, occurred_at BIGINT NOT NULL, client_ip VARCHAR(64) NOT NULL)");
                }
            }
            try (Statement statement = connection.createStatement()) {
                statement.executeQuery("SELECT 1 FROM dbwv_audit_event WHERE 1=0").close();
                statement.executeQuery("SELECT 1 FROM dbwv_token_revocation WHERE 1=0").close();
                statement.executeQuery("SELECT 1 FROM dbwv_login_failure WHERE 1=0").close();
            } catch (SQLException ex) {
                throw new SQLException("审计表未准备好，请执行 dbweb/audit-schema.sql 或设置 db-web-view.audit-auto-create=true", ex);
            }
            initialized = true;
        }
    }

    public void record(Map<String, Object> event) throws SQLException {
        if (!shared()) {
            synchronized (localHistory) {
                localHistory.addFirst(new LinkedHashMap<>(event));
                while (localHistory.size() > historyLimit()) localHistory.removeLast();
            }
            return;
        }
        initialize();
        maybeCleanup();
        String sql = "INSERT INTO dbwv_audit_event (event_id,occurred_at,action_name,source_name,namespace_name,table_name,client_ip,result_kind,affected,row_count,elapsed_ms,error_text,sql_text) VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?)";
        try (Connection connection = dataSource().getConnection(); PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, UUID.randomUUID().toString());
            statement.setLong(2, Instant.parse((String) event.get("time")).toEpochMilli());
            statement.setString(3, str(event.get("action")));
            statement.setString(4, truncate(str(event.get("source")), 128));
            statement.setString(5, truncate(str(event.get("namespace")), 128));
            statement.setString(6, truncate(str(event.get("table")), 128));
            statement.setString(7, truncate(str(event.get("ip")), 64));
            statement.setString(8, truncate(str(event.get("kind")), 32));
            nullableLong(statement, 9, event.get("affected"));
            nullableLong(statement, 10, event.get("rowCount"));
            nullableLong(statement, 11, event.get("elapsedMs"));
            statement.setString(12, truncate(str(event.get("error")), 1000));
            statement.setString(13, truncate(str(event.get("sql")), 15000));
            statement.executeUpdate();
        }
    }

    public List<Map<String, Object>> sqlHistory() throws SQLException { return history(true); }
    public List<Map<String, Object>> auditHistory() throws SQLException { return history(false); }

    private List<Map<String, Object>> history(boolean onlySql) throws SQLException {
        if (!shared()) {
            synchronized (localHistory) {
                List<Map<String, Object>> result = new ArrayList<>();
                for (Map<String, Object> event : localHistory)
                    if (!onlySql || "sql".equals(event.get("action"))) result.add(new LinkedHashMap<>(event));
                return result;
            }
        }
        initialize();
        List<Map<String, Object>> result = new ArrayList<>();
        String sql = "SELECT occurred_at,action_name,source_name,namespace_name,table_name,client_ip,result_kind,affected,row_count,elapsed_ms,error_text,sql_text FROM dbwv_audit_event "
                + (onlySql ? "WHERE action_name='sql' " : "") + "ORDER BY occurred_at DESC,event_id DESC LIMIT ?";
        try (Connection connection = dataSource().getConnection(); PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setInt(1, historyLimit());
            try (ResultSet rs = statement.executeQuery()) {
                while (rs.next()) {
                    Map<String, Object> event = new LinkedHashMap<>();
                    event.put("time", Instant.ofEpochMilli(rs.getLong(1)).toString());
                    event.put("action", rs.getString(2)); event.put("source", rs.getString(3));
                    event.put("namespace", rs.getString(4)); event.put("table", rs.getString(5));
                    event.put("ip", rs.getString(6)); event.put("kind", rs.getString(7));
                    event.put("affected", rs.getObject(8)); event.put("rowCount", rs.getObject(9));
                    event.put("elapsedMs", rs.getObject(10)); event.put("error", rs.getString(11));
                    if (properties.isSaveSqlText()) event.put("sql", rs.getString(12));
                    result.add(event);
                }
            }
        }
        return result;
    }

    public boolean tooManyFailures(String ip) throws SQLException {
        int max = Math.max(1, properties.getLoginMaxAttempts());
        long cutoff = Instant.now().getEpochSecond() - Math.max(60, properties.getLoginWindowSeconds());
        if (!shared()) {
            if (localFailures.size() >= 10000) {
                localFailures.forEach((address, queue) -> {
                    synchronized (queue) {
                        while (!queue.isEmpty() && queue.peekFirst() < cutoff) queue.removeFirst();
                        if (queue.isEmpty()) localFailures.remove(address, queue);
                    }
                });
                if (localFailures.size() >= 10000 && !localFailures.containsKey(ip)) return true;
            }
            Deque<Long> attempts = localFailures.get(ip);
            if (attempts == null) return false;
            synchronized (attempts) {
                while (!attempts.isEmpty() && attempts.peekFirst() < cutoff) attempts.removeFirst();
                return attempts.size() >= max;
            }
        }
        initialize();
        try (Connection connection = dataSource().getConnection(); PreparedStatement statement = connection.prepareStatement(
                "SELECT COUNT(*) FROM dbwv_login_failure WHERE client_ip=? AND occurred_at>=?")) {
            statement.setString(1, ip); statement.setLong(2, cutoff * 1000);
            try (ResultSet rs = statement.executeQuery()) { rs.next(); return rs.getLong(1) >= max; }
        }
    }

    public void failedLogin(String ip) throws SQLException {
        if (!shared()) {
            Deque<Long> attempts = localFailures.computeIfAbsent(ip, unused -> new ArrayDeque<>());
            synchronized (attempts) {
                long cutoff = Instant.now().getEpochSecond() - Math.max(60, properties.getLoginWindowSeconds());
                while (!attempts.isEmpty() && attempts.peekFirst() < cutoff) attempts.removeFirst();
                attempts.addLast(Instant.now().getEpochSecond());
            }
            return;
        }
        initialize();
        maybeCleanup();
        try (Connection connection = dataSource().getConnection(); PreparedStatement statement = connection.prepareStatement(
                "INSERT INTO dbwv_login_failure (event_id,occurred_at,client_ip) VALUES (?,?,?)")) {
            statement.setString(1, UUID.randomUUID().toString());
            statement.setLong(2, Instant.now().toEpochMilli()); statement.setString(3, ip);
            statement.executeUpdate();
        }
    }

    public void successfulLogin(String ip) throws SQLException {
        if (!shared()) { localFailures.remove(ip); return; }
        initialize();
        try (Connection connection = dataSource().getConnection(); PreparedStatement statement = connection.prepareStatement(
                "DELETE FROM dbwv_login_failure WHERE client_ip=?")) {
            statement.setString(1, ip); statement.executeUpdate();
        }
    }

    public void revoke(String jti, long expiresAt) throws SQLException {
        if (!shared()) return;
        initialize();
        maybeCleanup();
        try (Connection connection = dataSource().getConnection(); PreparedStatement statement = connection.prepareStatement(
                "INSERT INTO dbwv_token_revocation (jti,expires_at) VALUES (?,?)")) {
            statement.setString(1, jti); statement.setLong(2, expiresAt * 1000);
            statement.executeUpdate();
        }
    }

    public boolean revoked(String jti) throws SQLException {
        if (!shared()) return false;
        initialize();
        try (Connection connection = dataSource().getConnection(); PreparedStatement statement = connection.prepareStatement(
                "SELECT 1 FROM dbwv_token_revocation WHERE jti=? AND expires_at>?")) {
            statement.setString(1, jti); statement.setLong(2, Instant.now().toEpochMilli());
            try (ResultSet rs = statement.executeQuery()) { return rs.next(); }
        }
    }

    private void maybeCleanup() {
        long now = Instant.now().getEpochSecond();
        long previous = lastCleanup.get();
        if (now - previous < 3600 || !lastCleanup.compareAndSet(previous, now)) return;
        long days = Math.max(1, Math.min(3650, properties.getAuditRetentionDays()));
        try (Connection connection = dataSource().getConnection()) {
            cleanup(connection, "DELETE FROM dbwv_audit_event WHERE occurred_at<?", Instant.ofEpochSecond(now - days * 86400));
            cleanup(connection, "DELETE FROM dbwv_token_revocation WHERE expires_at<?", Instant.ofEpochSecond(now));
            cleanup(connection, "DELETE FROM dbwv_login_failure WHERE occurred_at<?",
                    Instant.ofEpochSecond(now - Math.max(60, properties.getLoginWindowSeconds())));
        } catch (SQLException ex) { log.warn("DB Web View audit cleanup failed", ex); }
    }

    private void cleanup(Connection connection, String sql, Instant cutoff) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setLong(1, cutoff.toEpochMilli()); statement.executeUpdate();
        }
    }

    private int historyLimit() { return Math.max(1, Math.min(1000, properties.getHistorySize())); }
    private String str(Object value) { return value == null ? null : String.valueOf(value); }
    private String truncate(String value, int max) { return value == null || value.length() <= max ? value : value.substring(0, max); }
    private void nullableLong(PreparedStatement statement, int index, Object value) throws SQLException {
        if (value == null) statement.setNull(index, java.sql.Types.BIGINT);
        else statement.setLong(index, ((Number) value).longValue());
    }
}
