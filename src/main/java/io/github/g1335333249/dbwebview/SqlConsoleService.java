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

/** Executes one administrator-supplied SQL statement on the selected live data source. */
public class SqlConsoleService {
    private final DataSourceRegistry registry;
    private final DbWebViewProperties properties;

    public SqlConsoleService(DataSourceRegistry registry, DbWebViewProperties properties) {
        this.registry = registry;
        this.properties = properties;
    }

    public Map<String, Object> execute(String source, String input) throws SQLException {
        String sql = SingleStatementSql.normalize(input);
        if (properties.isReadOnly() && !sql.matches("(?is)^\\s*SELECT\\b.*"))
            throw new IllegalArgumentException("只读模式仅允许 SELECT；请同时使用只读数据库账号");
        int maxRows = Math.max(1, Math.min(properties.getSqlMaxRows(), 1000));
        int timeout = Math.max(1, Math.min(properties.getSqlTimeoutSeconds(), 300));
        long started = System.nanoTime();
        try (Connection connection = registry.require(source).getConnection()) {
            if (properties.isReadOnly()) connection.setReadOnly(true);
            try (Statement statement = connection.createStatement()) {
            statement.setQueryTimeout(timeout);
            statement.setMaxRows(maxRows + 1);
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
        }
    }

    private Object displayValue(Object value) {
        if (value == null) return null;
        if (value instanceof byte[] || value instanceof Blob || value instanceof Clob || value instanceof SQLXML)
            return "[二进制/大对象]";
        if (value instanceof String || value instanceof Number || value instanceof Boolean) return value;
        return value.toString();
    }
}
