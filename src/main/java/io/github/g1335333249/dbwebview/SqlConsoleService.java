package io.github.g1335333249.dbwebview;

import java.sql.Blob;
import java.sql.Clob;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLXML;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import javax.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Executes one administrator-supplied SQL statement on the selected live data source. */
public class SqlConsoleService {
    private static final Logger log = LoggerFactory.getLogger(SqlConsoleService.class);
    private final DataSourceRegistry registry;
    private final DbWebViewProperties properties;
    private final AuditStore auditStore;
    private final ConcurrentHashMap<String, Statement> active = new ConcurrentHashMap<>();
    private final ScheduledExecutorService cancellationPoller;

    public SqlConsoleService(DataSourceRegistry registry, DbWebViewProperties properties) {
        this(registry, properties, null);
    }

    public SqlConsoleService(DataSourceRegistry registry, DbWebViewProperties properties, AuditStore auditStore) {
        this.registry = registry;
        this.properties = properties;
        this.auditStore = auditStore;
        this.cancellationPoller = Executors.newSingleThreadScheduledExecutor(task -> {
            Thread thread = new Thread(task, "db-web-view-sql-cancel");
            thread.setDaemon(true);
            return thread;
        });
        if (auditStore != null && auditStore.shared())
            cancellationPoller.scheduleWithFixedDelay(this::pollCancellation, 500, 500, TimeUnit.MILLISECONDS);
    }

    public Map<String, Object> execute(String source, String input) throws SQLException {
        return execute(source, input, UUID.randomUUID().toString(), false);
    }

    public Map<String, Object> explain(String source, String input) throws SQLException {
        return execute(source, input, UUID.randomUUID().toString(), true);
    }

    public Map<String, Object> execute(String source, String input, String executionId, boolean explain) throws SQLException {
        validateId(executionId);
        String sql = SingleStatementSql.normalize(input);
        if (explain) {
            if (!sql.matches("(?is)^SELECT\\b.*")) throw new IllegalArgumentException("执行计划仅支持 SELECT 查询");
            sql = "EXPLAIN " + sql;
        }
        if (properties.isReadOnly() && !explain && !sql.matches("(?is)^\\s*SELECT\\b.*"))
            throw new IllegalArgumentException("只读模式仅允许 SELECT；请同时使用只读数据库账号");
        int maxRows = Math.max(1, Math.min(properties.getSqlMaxRows(), 1000));
        int timeout = Math.max(1, Math.min(properties.getSqlTimeoutSeconds(), 300));
        long started = System.nanoTime();
        boolean registered = false;
        if (auditStore != null) auditStore.beginSqlExecution(executionId);
        try (Connection connection = registry.require(source).getConnection()) {
            if (properties.isReadOnly() || explain) connection.setReadOnly(true);
            try (Statement statement = connection.createStatement()) {
            if (active.putIfAbsent(executionId, statement) != null)
                throw new IllegalArgumentException("执行标识已在使用");
            registered = true;
            statement.setQueryTimeout(timeout);
            statement.setMaxRows(maxRows + 1);
            if (auditStore != null && auditStore.sqlCancellationRequested(executionId))
                throw new SQLException("SQL 已取消");
            boolean hasResult = statement.execute(sql);
            Map<String, Object> result = new LinkedHashMap<>();
            if (hasResult) {
                try (ResultSet rs = statement.getResultSet()) {
                    ResultSetMetaData metadata = rs.getMetaData();
                    List<String> columns = new ArrayList<>();
                    for (int i = 1; i <= metadata.getColumnCount(); i++) columns.add(metadata.getColumnLabel(i));
                    List<List<Object>> rows = new ArrayList<>();
                    while (rs.next() && rows.size() <= maxRows) {
                        List<Object> row = new ArrayList<>();
                        for (int i = 1; i <= columns.size(); i++) row.add(displayValue(rs.getObject(i)));
                        rows.add(row);
                    }
                    boolean truncated = rows.size() > maxRows;
                    if (truncated) rows.remove(rows.size() - 1);
                    result.put("kind", "resultSet");
                    result.put("columns", columns);
                    result.put("rows", rows);
                    result.put("rowCount", rows.size());
                    result.put("truncated", truncated);
                }
            } else {
                result.put("kind", "update");
                result.put("affected", Math.max(0, statement.getUpdateCount()));
            }
            result.put("elapsedMs", (System.nanoTime() - started) / 1_000_000);
            return result;
            }
        } finally {
            if (registered) active.remove(executionId);
            if (auditStore != null) {
                try { auditStore.finishSqlExecution(executionId); }
                catch (SQLException ex) { log.warn("Could not clean up SQL execution {}", executionId, ex); }
            }
        }
    }

    public boolean cancel(String executionId) throws SQLException {
        validateId(executionId);
        Statement statement = active.get(executionId);
        boolean shared = auditStore != null && auditStore.requestSqlCancellation(executionId);
        if (statement != null) statement.cancel();
        return shared || statement != null;
    }

    private void pollCancellation() {
        for (Map.Entry<String, Statement> item : active.entrySet()) {
            try {
                if (auditStore.sqlCancellationRequested(item.getKey())) item.getValue().cancel();
            } catch (SQLException ex) { log.warn("SQL cancellation poll failed", ex); }
        }
    }

    private void validateId(String id) {
        try { UUID.fromString(id); }
        catch (RuntimeException ex) { throw new IllegalArgumentException("执行标识格式错误"); }
    }

    @PreDestroy
    public void shutdown() { cancellationPoller.shutdownNow(); }

    private Object displayValue(Object value) {
        if (value == null) return null;
        if (value instanceof byte[] || value instanceof Blob || value instanceof Clob || value instanceof SQLXML)
            return "[二进制/大对象]";
        if (value instanceof String || value instanceof Number || value instanceof Boolean) return value;
        return value.toString();
    }
}
