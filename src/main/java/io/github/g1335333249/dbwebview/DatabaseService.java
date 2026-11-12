package io.github.g1335333249.dbwebview;

import javax.sql.DataSource;
import java.math.BigDecimal;
import java.io.IOException;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
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
                    if (allowedTable(namespace, name)) result.add(table);
                }
            }
            result.sort(Comparator.comparing((Map<String, String> t) -> t.get("namespace"))
                    .thenComparing(t -> t.get("name")));
            return result;
        }
    }

    public Map<String, Object> rows(String source, String namespace, String tableName, int page, int size) throws SQLException {
        return rows(source, namespace, tableName, page, size, Collections.emptyMap(), null, false);
    }

    public Map<String, Object> rows(String source, String namespace, String tableName, int page, int size,
                                    Map<String, String> filters, String sort, boolean descending) throws SQLException {
        return rows(source, namespace, tableName, page, size, filters, sort, descending, null);
    }

    public Map<String, Object> rows(String source, String namespace, String tableName, int page, int size,
                                    Map<String, String> filters, String sort, boolean descending, String cursor) throws SQLException {
        return rows(source, namespace, tableName, page, size, legacyFilters(filters), sort, descending, cursor);
    }

    public Map<String, Object> rows(String source, String namespace, String tableName, int page, int size,
                                    List<FilterCondition> filters, String sort, boolean descending, String cursor) throws SQLException {
        if (page < 0) throw new IllegalArgumentException("页码不能小于 0");
        int max = Math.max(1, Math.min(1000, properties.getMaxPageSize()));
        int actualSize = Math.max(1, Math.min(size <= 0 ? properties.getPageSize() : size, max));
        if ((long) page * actualSize > Integer.MAX_VALUE) throw new IllegalArgumentException("页码过大");
        try (Connection connection = registry.require(source).getConnection()) {
            Table table = resolve(connection, namespace, tableName);
            List<Map<String, Object>> records = new ArrayList<>();
            StringBuilder sql = new StringBuilder("SELECT * FROM ").append(table.qualifiedName);
            List<FilterCondition> checkedFilters = filters == null ? Collections.emptyList() : filters;
            if (checkedFilters.size() > 20) throw new IllegalArgumentException("筛选字段过多");
            boolean keyset = table.primaryKeys.size() == 1 && (sort == null || sort.isEmpty()) && !descending;
            if (cursor != null && !cursor.isEmpty() && !keyset) throw new IllegalArgumentException("此排序不支持游标翻页");
            List<String> termsList = new ArrayList<>();
            List<Object> filterValues = new ArrayList<>();
            for (FilterCondition filter : checkedFilters) {
                if (filter == null || filter.column == null) throw new IllegalArgumentException("筛选格式错误");
                Column definition = table.columns.get(filter.column);
                if (definition == null || definition.binary) throw new IllegalArgumentException("不可筛选字段: " + filter.column);
                String name = quote(connection, filter.column);
                String operator = filter.operator == null ? "eq" : filter.operator;
                switch (operator) {
                    case "eq": case "ne": case "gt": case "gte": case "lt": case "lte":
                        if (filter.value == null) throw new IllegalArgumentException("缺少筛选值");
                        String comparison = "eq".equals(operator) ? "=" : "ne".equals(operator) ? "<>" :
                                "gt".equals(operator) ? ">" : "gte".equals(operator) ? ">=" : "lt".equals(operator) ? "<" : "<=";
                        termsList.add(name + " " + comparison + " ?");
                        filterValues.add(convert(filter.value, definition.jdbcType));
                        break;
                    case "contains":
                        if (filter.value == null) throw new IllegalArgumentException("缺少筛选值");
                        if (!isTextType(definition.jdbcType)) throw new IllegalArgumentException("包含操作只支持文本字段");
                        termsList.add(name + " LIKE ? ESCAPE '!'");
                        filterValues.add("%" + filter.value.replace("!", "!!").replace("%", "!%").replace("_", "!_") + "%");
                        break;
                    case "between":
                        if (filter.value == null || filter.value2 == null) throw new IllegalArgumentException("范围筛选需要两个值");
                        termsList.add(name + " BETWEEN ? AND ?");
                        filterValues.add(convert(filter.value, definition.jdbcType));
                        filterValues.add(convert(filter.value2, definition.jdbcType));
                        break;
                    case "isNull": case "isNotNull":
                        termsList.add(name + ("isNull".equals(operator) ? " IS NULL" : " IS NOT NULL"));
                        break;
                    default: throw new IllegalArgumentException("不支持的筛选操作: " + operator);
                }
            }
            if (cursor != null && !cursor.isEmpty()) {
                if (cursor.length() > 1024) throw new IllegalArgumentException("游标过长");
                termsList.add(quote(connection, table.primaryKeys.get(0)) + " > ?");
            }
            if (!termsList.isEmpty()) sql.append(" WHERE ").append(String.join(" AND ", termsList));
            if (sort != null && !sort.isEmpty() && !table.columns.containsKey(sort))
                throw new IllegalArgumentException("未知排序字段: " + sort);
            List<String> order = new ArrayList<>();
            if (sort != null && !sort.isEmpty()) order.add(quote(connection, sort) + (descending ? " DESC" : " ASC"));
            for (String key : table.primaryKeys) if (!key.equals(sort)) order.add(quote(connection, key));
            if (!order.isEmpty()) sql.append(" ORDER BY ").append(String.join(", ", order));
            sql.append(" LIMIT ?");
            if (!keyset || (page > 0 && (cursor == null || cursor.isEmpty()))) sql.append(" OFFSET ?");
            try (PreparedStatement statement = connection.prepareStatement(sql.toString())) {
                int parameter = 1;
                for (Object value : filterValues) statement.setObject(parameter++, value);
                if (cursor != null && !cursor.isEmpty()) {
                    String key = table.primaryKeys.get(0);
                    String decoded;
                    try { decoded = new String(Base64.getUrlDecoder().decode(cursor), StandardCharsets.UTF_8); }
                    catch (IllegalArgumentException ex) { throw new IllegalArgumentException("游标格式错误", ex); }
                    statement.setObject(parameter++, convert(decoded, table.columns.get(key).jdbcType));
                }
                statement.setInt(parameter++, actualSize + 1);
                if (!keyset || (page > 0 && (cursor == null || cursor.isEmpty())))
                    statement.setInt(parameter, page * actualSize);
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
            result.put("readOnly", table.readOnly || properties.isReadOnly());
            result.put("canInsert", !table.view && !properties.isReadOnly());
            result.put("rows", records);
            result.put("page", page);
            result.put("size", actualSize);
            result.put("hasNext", hasNext);
            if (hasNext && keyset && !records.isEmpty()) {
                Object lastKey = records.get(records.size() - 1).get(table.primaryKeys.get(0));
                result.put("nextCursor", Base64.getUrlEncoder().withoutPadding()
                        .encodeToString(String.valueOf(lastKey).getBytes(StandardCharsets.UTF_8)));
            }
            return result;
        }
    }

    public int insert(String source, String namespace, String tableName, Map<String, Object> values) throws SQLException {
        requireWriteEnabled();
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
        requireWriteEnabled();
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
        requireWriteEnabled();
        try (Connection connection = registry.require(source).getConnection()) {
            Table table = resolve(connection, namespace, tableName);
            requireWritable(table);
            Map<String, Object> checked = checkedKeys(table, keys);
            String sql = "DELETE FROM " + table.qualifiedName + " WHERE " + where(connection, table);
            return execute(connection, sql, checked, table);
        }
    }

    private void requireWriteEnabled() {
        if (properties.isReadOnly()) throw new IllegalArgumentException("管理页面处于只读模式");
    }

    private boolean allowedTable(String namespace, String name) {
        List<String> allowed = properties.getAllowedTables();
        return allowed == null || allowed.isEmpty() || allowed.contains(namespace + "." + name);
    }

    public Map<String, Object> structure(String source, String namespace, String tableName) throws SQLException {
        try (Connection connection = registry.require(source).getConnection()) {
            Table table = resolve(connection, namespace, tableName);
            DatabaseMetaData metadata = connection.getMetaData();
            String catalog = "mysql".equals(dialect(metadata)) ? namespace : connection.getCatalog();
            String schema = "mysql".equals(dialect(metadata)) ? null : namespace;
            List<Map<String, Object>> indexes = new ArrayList<>();
            try (ResultSet rs = metadata.getIndexInfo(catalog, schema, tableName, false, false)) {
                while (rs.next()) {
                    String column = rs.getString("COLUMN_NAME");
                    if (column == null) continue;
                    Map<String, Object> item = new LinkedHashMap<>();
                    item.put("name", rs.getString("INDEX_NAME")); item.put("column", column);
                    item.put("unique", !rs.getBoolean("NON_UNIQUE")); indexes.add(item);
                }
            }
            List<Map<String, Object>> foreignKeys = new ArrayList<>();
            try (ResultSet rs = metadata.getImportedKeys(catalog, schema, tableName)) {
                while (rs.next()) {
                    Map<String, Object> item = new LinkedHashMap<>();
                    item.put("name", rs.getString("FK_NAME"));
                    item.put("column", rs.getString("FKCOLUMN_NAME"));
                    item.put("reference", rs.getString("PKTABLE_SCHEM") + "." + rs.getString("PKTABLE_NAME") + "." + rs.getString("PKCOLUMN_NAME"));
                    foreignKeys.add(item);
                }
            }
            Map<String, Object> result = new LinkedHashMap<>();
            result.put("columns", table.columnDescriptions()); result.put("primaryKeys", table.primaryKeys);
            result.put("indexes", indexes); result.put("foreignKeys", foreignKeys);
            return result;
        }
    }

    public void writeCsv(OutputStream output, String source, String namespace, String tableName,
                         Map<String, String> filters, String sort, boolean descending) throws SQLException, IOException {
        writeCsv(output, source, namespace, tableName, legacyFilters(filters), sort, descending);
    }

    public void writeCsv(OutputStream output, String source, String namespace, String tableName,
                         List<FilterCondition> filters, String sort, boolean descending) throws SQLException, IOException {
        int limit = Math.max(1, Math.min(properties.getExportMaxRows(), 100000));
        Writer writer = new OutputStreamWriter(output, StandardCharsets.UTF_8);
        int page = 0, count = 0;
        String cursor = null;
        final int size = Math.min(500, Math.max(1, Math.min(1000, properties.getMaxPageSize())));
        while (count < limit) {
            Map<String, Object> result = rows(source, namespace, tableName, page++, size,
                    filters, sort, descending, cursor);
            @SuppressWarnings("unchecked") List<Map<String, Object>> columns = (List<Map<String, Object>>) result.get("columns");
            @SuppressWarnings("unchecked") List<Map<String, Object>> records = (List<Map<String, Object>>) result.get("rows");
            if (count == 0) {
                writer.write('\uFEFF');
                List<String> names = new ArrayList<>();
                for (Map<String, Object> column : columns) names.add(csvCell(column.get("name")));
                writer.write(String.join(",", names)); writer.write("\r\n");
            }
            for (Map<String, Object> record : records) {
                if (count >= limit) break;
                List<String> cells = new ArrayList<>();
                for (Map<String, Object> column : columns) cells.add(csvCell(record.get(column.get("name"))));
                writer.write(String.join(",", cells)); writer.write("\r\n");
                count++;
            }
            writer.flush();
            if (!(Boolean) result.get("hasNext") || records.isEmpty()) break;
            cursor = (String) result.get("nextCursor");
        }
        writer.flush();
    }

    private List<FilterCondition> legacyFilters(Map<String, String> filters) {
        List<FilterCondition> result = new ArrayList<>();
        if (filters != null) filters.forEach((column, value) -> result.add(new FilterCondition(column, "eq", value, null)));
        return result;
    }

    private boolean isTextType(int type) {
        return type == Types.CHAR || type == Types.VARCHAR || type == Types.LONGVARCHAR
                || type == Types.NCHAR || type == Types.NVARCHAR || type == Types.LONGNVARCHAR;
    }

    private String csvCell(Object value) {
        if (value == null) return "";
        String text = String.valueOf(value);
        if (value instanceof String && !text.trim().isEmpty()
                && "=+-@".indexOf(text.trim().charAt(0)) >= 0) text = "'" + text;
        return "\"" + text.replace("\"", "\"\"") + "\"";
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
        if (type == null || isSystem(namespace) || !allowedTable(namespace, name)) throw new IllegalArgumentException("表不存在或不可访问");
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
