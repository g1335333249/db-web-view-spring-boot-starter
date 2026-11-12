package io.github.guanpeng.dbwebview;

/** Recognizes semicolons outside SQL strings and comments. This is a UX guard, not an SQL permission system. */
final class SingleStatementSql {
    private SingleStatementSql() { }

    static String normalize(String input) {
        if (input == null || input.trim().isEmpty()) throw new IllegalArgumentException("请输入 SQL");
        if (input.length() > 100_000) throw new IllegalArgumentException("SQL 最多 100000 个字符");
        int end = -1;
        int blockDepth = 0;
        boolean single = false, doubleQuoted = false, backtick = false, line = false;
        String dollar = null;
        for (int i = 0; i < input.length(); i++) {
            char c = input.charAt(i);
            char next = i + 1 < input.length() ? input.charAt(i + 1) : 0;
            if (line) { if (c == '\n' || c == '\r') line = false; continue; }
            if (blockDepth > 0) {
                if (c == '/' && next == '*') { blockDepth++; i++; }
                else if (c == '*' && next == '/') { blockDepth--; i++; }
                continue;
            }
            if (dollar != null) {
                if (input.startsWith(dollar, i)) { i += dollar.length() - 1; dollar = null; }
                continue;
            }
            if (single || doubleQuoted || backtick) {
                char quote = single ? '\'' : doubleQuoted ? '"' : '`';
                if (c == '\\') { i++; continue; }
                if (c == quote) {
                    if (next == quote) i++;
                    else { single = false; doubleQuoted = false; backtick = false; }
                }
                continue;
            }
            if (c == '-' && next == '-') { line = true; i++; continue; }
            if (c == '#') { line = true; continue; }
            if (c == '/' && next == '*') { blockDepth = 1; i++; continue; }
            if (end >= 0 && !Character.isWhitespace(c)) throw new IllegalArgumentException("一次只能执行一条 SQL");
            if (c == '\'') { single = true; continue; }
            if (c == '"') { doubleQuoted = true; continue; }
            if (c == '`') { backtick = true; continue; }
            if (c == '$') {
                int j = i + 1;
                while (j < input.length() && (Character.isLetterOrDigit(input.charAt(j)) || input.charAt(j) == '_')) j++;
                if (j < input.length() && input.charAt(j) == '$') { dollar = input.substring(i, j + 1); i = j; continue; }
            }
            if (c == ';') {
                end = i;
            }
        }
        if (single || doubleQuoted || backtick || dollar != null || blockDepth > 0)
            throw new IllegalArgumentException("SQL 字符串或注释未结束");
        String sql = (end >= 0 ? input.substring(0, end) : input).trim();
        if (sql.isEmpty()) throw new IllegalArgumentException("请输入 SQL");
        return sql;
    }
}
