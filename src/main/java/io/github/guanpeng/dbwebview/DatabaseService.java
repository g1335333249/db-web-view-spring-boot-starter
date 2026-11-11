package io.github.guanpeng.dbwebview;

import javax.sql.DataSource;
import java.math.BigDecimal;
import java.sql.*;
import java.util.*;

public class DatabaseService {
    private final DataSourceRegistry registry;
    private final DbWebViewProperties properties;

    public DatabaseService(DataSourceRegistry registry, DbWebViewProperties properties) {
        this.registry = registry;
        this.properties = properties;
    }

    public List<String> sources() { return new ArrayList<>(registry.list().keySet()); }

    public List<Map<String, String>> tables(String source) throws SQLException {
        try (Connection connection = registry.require(source).getConnection()) {
            DatabaseMetaData metadata = connection.getMetaData();
            String dialect = dialect(metadata);
            List<Map<String, String>> result = new ArrayList<>();
            try (ResultSet rs = metadata.getTables(null, null, "%", new String[]{"TABLE", "VIEW"})) {
                while (rs.next()) {
                    String catalog = rs.getString("TABLE_CAT");
                    String schema = rs.getString("TABLE_SCHEM");
                    String name = rs.getString("TABLE_NAME");
                    String namespace = "mysql".equals(dialect) ? catalog : schema;
                    if (namespace == null || isSystem(namespace)) continue;
                    Map<String, String> table = new LinkedHashMap<>();
                    table.put("namespace", namespace);
                    table.put("name", name);
                    table.put("type", rs.getString("TABLE_TYPE"));
                    result.add(table);
                }
            }
            result.sort(Comparator.comparing((Map<String, String> t) -> t.get("namespace"))
                    .thenComparing(t -> t.get("name")));
            return result;
        }
    }

    public Map<String, Object> rows(String source, String namespace, String tableName, int page, int size) throws SQLException {
        if (page < 0) throw new IllegalArgumentException("页码不能小于 0");
        int max = Math.max(1, Math.min(1000, properties.getMaxPageSize()));
        int actualSize = Math.max(1, Math.min(size <= 0 ? properties.getPageSize() : size, max));
        if ((long) page * actualSize > Integer.MAX_VALUE) throw new IllegalArgumentException("页码过大");
        try (Connection connection = registry.require(source).getConnection()) {
            Table table = resolve(connection, namespace, tableName);
            List<Map<String, Object>> records = new ArrayList<>();
            StringBuilder sql = new StringBuilder("SELECT * FROM ").append(table.qualifiedName);
            if (!table.primaryKeys.isEmpty()) {
                sql.append(" ORDER BY ");
                for (int i = 0; i < table.primaryKeys.size(); i++) {
                    if (i > 0) sql.append(',');
                    sql.append(quote(connection, table.primaryKeys.get(i)));
                }
            }
            sql.append(" LIMIT ? OFFSET ?");
            try (PreparedStatement statement = connection.prepareStatement(sql.toString())) {
                statement.setInt(1, actualSize + 1);
                statement.setInt(2, page * actualSize);
                statement.setQueryTimeout(30);
                try (ResultSet rs = statement.executeQuery()) {
                    while (rs.next()) {
                        Map<String, Object> row = new LinkedHashMap<>();
                        for (Column column : table.columns.values()) {
                            row.put(column.name, jsonValue(rs.getObject(column.name)));
                        }
                        records.add(row);
                    }
                }
            }
            boolean hasNext = records.size() > actualSize;
            if (hasNext) records.remove(records.size() - 1);
            Map<String, Object> result = new LinkedHashMap<>();
            result.put("columns", table.columnDescriptions());
            result.put("primaryKeys", table.primaryKeys);
            result.put("readOnly", table.readOnly);
            result.put("canInsert", !table.view);
            result.put("rows", records);
            result.put("page", page);
            result.put("size", actualSize);
            result.put("hasNext", hasNext);
            return result;
        }
    }

    public int insert(String source, String namespace, String tableName, Map<String, Object> values) throws SQLException {
        try (Connection connection = registry.require(source).getConnection()) {
            Table table = resolve(connection, namespace, tableName);
            if (table.view) throw new IllegalArgumentException("视图不允许新增");
            Map<String, Object> checked = checkedValues(table, values, true);
            if (checked.isEmpty()) throw new IllegalArgumentException("至少填写一个字段");
            StringJoiner names = new StringJoiner(", ");
            StringJoiner placeholders = new StringJoiner(", ");
            for (String name : checked.keySet()) { names.add(quote(connection, name)); placeholders.add("?"); }
            String sql = "INSERT INTO " + table.qualifiedName + " (" + names + ") VALUES (" + placeholders + ")";
            return execute(connection, sql, checked, table);
        }
    }

    public int update(String source, String namespace, String tableName, Map<String, Object> keys,
                      Map<String, Object> values) throws SQLException {
        try (Connection connection = registry.require(source).getConnection()) {
            Table table = resolve(connection, namespace, tableName);
            requireWritable(table);
            Map<String, Object> checkedKeys = checkedKeys(table, keys);
            Map<String, Object> checked = checkedValues(table, values, false);
            if (checked.isEmpty()) throw new IllegalArgumentException("没有可修改字段");
            StringJoiner assignments = new StringJoiner(", ");
            for (String name : checked.keySet()) assignments.add(quote(connection, name) + " = ?");
            String sql = "UPDATE " + table.qualifiedName + " SET " + assignments + " WHERE " + where(connection, table);
            Map<String, Object> all = new LinkedHashMap<>(checked);
            all.putAll(checkedKeys);
            return execute(connection, sql, all, table);
        }
    }

    public int delete(String source, String namespace, String tableName, Map<String, Object> keys) throws SQLException {
        try (Connection connection = registry.require(source).getConnection()) {
            Table table = resolve(connection, namespace, tableName);
            requireWritable(table);
            Map<String, Object> checked = checkedKeys(table, keys);
            String sql = "DELETE FROM " + table.qualifiedName + " WHERE " + where(connection, table);
            return execute(connection, sql, checked, table);
        }
    }

    private int execute(Connection connection, String sql, Map<String, Object> values, Table table) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            int i = 1;
            for (Map.Entry<String, Object> entry : values.entrySet()) {
                Object value = entry.getValue();
                int type = table.columns.get(entry.getKey()).jdbcType;
                if (value == null) statement.setNull(i++, type);
                else statement.setObject(i++, convert(value, type), type);
            }
            statement.setQueryTimeout(30);
            return statement.executeUpdate();
        }
    }

    private Object convert(Object value, int jdbcType) {
        if (!(value instanceof String)) return value;
        String text = (String) value;
        switch (jdbcType) {
            case Types.INTEGER: case Types.SMALLINT: case Types.TINYINT: return Integer.valueOf(text);
            case Types.BIGINT: return Long.valueOf(text);
            case Types.DECIMAL: case Types.NUMERIC: return new BigDecimal(text);
            case Types.FLOAT: case Types.REAL: case Types.DOUBLE: return Double.valueOf(text);
            case Types.BOOLEAN: case Types.BIT: return Boolean.valueOf(text);
            case Types.DATE: return java.sql.Date.valueOf(text);
            case Types.TIME: return Time.valueOf(text);
            case Types.TIMESTAMP: return Timestamp.valueOf(text.replace('T', ' '));
            default: return value;
        }
    }

    private String where(Connection connection, Table table) throws SQLException {
        StringJoiner terms = new StringJoiner(" AND ");
        for (String key : table.primaryKeys) terms.add(quote(connection, key) + " = ?");
        return terms.toString();
    }

    private Map<String, Object> checkedKeys(Table table, Map<String, Object> values) {
        if (values == null || table.primaryKeys.isEmpty() || values.size() != table.primaryKeys.size())
            throw new IllegalArgumentException("必须提供完整主键");
        Map<String, Object> result = new LinkedHashMap<>();
        for (String key : table.primaryKeys) {
            if (!values.containsKey(key) || values.get(key) == null) throw new IllegalArgumentException("主键缺失: " + key);
            result.put(key, values.get(key));
        }
        return result;
    }

    private Map<String, Object> checkedValues(Table table, Map<String, Object> values, boolean inserting) {
        if (values == null) throw new IllegalArgumentException("缺少字段值");
        Map<String, Object> result = new LinkedHashMap<>();
        for (Map.Entry<String, Object> entry : values.entrySet()) {
            Column column = table.columns.get(entry.getKey());
            if (column == null) throw new IllegalArgumentException("未知字段: " + entry.getKey());
            if (column.generated || column.binary || (inserting && column.autoIncrement)
                    || (!inserting && table.primaryKeys.contains(column.name)))
                throw new IllegalArgumentException("字段不可编辑: " + column.name);
            result.put(column.name, entry.getValue());
        }
        return result;
    }

    private void requireWritable(Table table) {
        if (table.readOnly) throw new IllegalArgumentException("此表只允许查询（视图或无主键）");
    }

    private Table resolve(Connection connection, String namespace, String name) throws SQLException {
        if (namespace == null || namespace.isEmpty() || name == null || name.isEmpty())
            throw new IllegalArgumentException("必须指定命名空间和表名");
        DatabaseMetaData metadata = connection.getMetaData();
        String dialect = dialect(metadata);
        String catalog = "mysql".equals(dialect) ? namespace : connection.getCatalog();
        String schema = "mysql".equals(dialect) ? null : namespace;
        String type = null;
        try (ResultSet rs = metadata.getTables(catalog, schema, null, new String[]{"TABLE", "VIEW"})) {
            while (rs.next()) {
                if (name.equals(rs.getString("TABLE_NAME"))
                        && Objects.equals(catalog, rs.getString("TABLE_CAT"))
                        && Objects.equals(schema, rs.getString("TABLE_SCHEM"))) {
                    type = rs.getString("TABLE_TYPE");
                    break;
                }
            }
        }
        if (type == null || isSystem(namespace)) throw new IllegalArgumentException("表不存在或不可访问");
        LinkedHashMap<String, Column> columns = new LinkedHashMap<>();
        try (ResultSet rs = metadata.getColumns(catalog, schema, name, null)) {
            while (rs.next()) {
                if (!name.equals(rs.getString("TABLE_NAME"))) continue;
                Column column = new Column(rs.getString("COLUMN_NAME"), rs.getInt("DATA_TYPE"),
                        rs.getString("TYPE_NAME"), "YES".equals(rs.getString("IS_NULLABLE")),
                        "YES".equals(rs.getString("IS_AUTOINCREMENT")),
                        "YES".equals(rs.getString("IS_GENERATEDCOLUMN")));
                columns.put(column.name, column);
            }
        }
        TreeMap<Short, String> keys = new TreeMap<>();
        try (ResultSet rs = metadata.getPrimaryKeys(catalog, schema, name)) {
            while (rs.next()) keys.put(rs.getShort("KEY_SEQ"), rs.getString("COLUMN_NAME"));
        }
        String qualified = quote(connection, namespace) + "." + quote(connection, name);
        boolean view = !"TABLE".equals(type);
        return new Table(qualified, columns, new ArrayList<>(keys.values()), view || keys.isEmpty(), view);
    }

    private String quote(Connection connection, String identifier) throws SQLException {
        String q = connection.getMetaData().getIdentifierQuoteString().trim();
        if (q.isEmpty()) throw new IllegalStateException("数据库不支持安全的标识符引用");
        return q + identifier.replace(q, q + q) + q;
    }

    private String dialect(DatabaseMetaData metadata) throws SQLException {
        String product = metadata.getDatabaseProductName().toLowerCase(Locale.ROOT);
        if (product.contains("mysql") || product.contains("mariadb")) return "mysql";
        if (product.contains("postgresql")) return "postgresql";
        // H2 is used only by the isolated integration tests.
        if (product.contains("h2")) return "h2";
        throw new IllegalArgumentException("暂不支持数据库: " + metadata.getDatabaseProductName());
    }

    private boolean isSystem(String namespace) {
        return namespace.equals("information_schema") || namespace.equals("pg_catalog")
                || namespace.startsWith("pg_toast") || namespace.startsWith("pg_temp")
                || namespace.equals("mysql") || namespace.equals("performance_schema") || namespace.equals("sys");
    }

    private Object jsonValue(Object value) {
        if (value == null) return null;
        if (value instanceof byte[] || value instanceof Blob || value instanceof Clob || value instanceof SQLXML)
            return "[二进制/大对象]";
        if (value instanceof String || value instanceof Number || value instanceof Boolean) return value;
        return value.toString();
    }

    private static class Table {
        final String qualifiedName;
        final LinkedHashMap<String, Column> columns;
        final List<String> primaryKeys;
        final boolean readOnly; final boolean view;
        Table(String qualifiedName, LinkedHashMap<String, Column> columns, List<String> primaryKeys, boolean readOnly, boolean view) {
            this.qualifiedName = qualifiedName; this.columns = columns; this.primaryKeys = primaryKeys; this.readOnly = readOnly; this.view = view;
        }
        List<Map<String, Object>> columnDescriptions() {
            List<Map<String, Object>> result = new ArrayList<>();
            for (Column column : columns.values()) {
                Map<String, Object> item = new LinkedHashMap<>();
                item.put("name", column.name); item.put("type", column.typeName);
                item.put("nullable", column.nullable); item.put("autoIncrement", column.autoIncrement);
                item.put("generated", column.generated); item.put("binary", column.binary);
                result.add(item);
            }
            return result;
        }
    }
    private static class Column {
        final String name; final int jdbcType; final String typeName;
        final boolean nullable; final boolean autoIncrement; final boolean generated; final boolean binary;
        Column(String name, int jdbcType, String typeName, boolean nullable, boolean autoIncrement, boolean generated) {
            this.name = name; this.jdbcType = jdbcType; this.typeName = typeName;
            this.nullable = nullable; this.autoIncrement = autoIncrement; this.generated = generated;
            this.binary = jdbcType == Types.BINARY || jdbcType == Types.VARBINARY || jdbcType == Types.LONGVARBINARY
                    || jdbcType == Types.BLOB || jdbcType == Types.CLOB || jdbcType == Types.NCLOB;
        }
    }
}
