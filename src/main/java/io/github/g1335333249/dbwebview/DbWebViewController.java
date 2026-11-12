package io.github.g1335333249.dbwebview;

import org.springframework.core.io.ClassPathResource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.sql.SQLException;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.List;
import java.util.ArrayList;

@RestController
@RequestMapping("/db-web-view")
public class DbWebViewController {
    private static final Logger log = LoggerFactory.getLogger(DbWebViewController.class);
    private final DatabaseService service;
    private final SqlConsoleService sqlService;
    private final DbWebViewProperties properties;
    private final JwtAuthService jwt;
    private final List<Map<String, Object>> history = new ArrayList<>();

    public DbWebViewController(DatabaseService service, SqlConsoleService sqlService, DbWebViewProperties properties, JwtAuthService jwt) {
        this.service = service;
        this.sqlService = sqlService;
        this.properties = properties;
        this.jwt = jwt;
    }

    @GetMapping("")
    public ResponseEntity<Void> redirect(HttpServletRequest request) {
        return ResponseEntity.status(302).header("Location", request.getContextPath() + "/db-web-view/").build();
    }

    @GetMapping(value = "/", produces = MediaType.TEXT_HTML_VALUE)
    public ResponseEntity<ClassPathResource> page() {
        ensureConfigured();
        return ResponseEntity.ok().header("Cache-Control", "no-store")
                .header("X-Frame-Options", "DENY")
                .header("Content-Security-Policy", "default-src 'none'; script-src 'self'; style-src 'self'; connect-src 'self'; base-uri 'none'; frame-ancestors 'none'")
                .body(new ClassPathResource("dbweb/index.html"));
    }

    @GetMapping(value = "/app.js", produces = "application/javascript")
    public ResponseEntity<ClassPathResource> script() {
        ensureConfigured();
        return ResponseEntity.ok().header("Cache-Control", "no-store").body(new ClassPathResource("dbweb/app.js"));
    }

    @GetMapping(value = "/app.css", produces = "text/css")
    public ResponseEntity<ClassPathResource> styles() {
        ensureConfigured();
        return ResponseEntity.ok().header("Cache-Control", "no-store").body(new ClassPathResource("dbweb/app.css"));
    }

    @PostMapping("/api/login")
    public Map<String, Object> login(@RequestBody Map<String, String> body, HttpServletRequest request, HttpServletResponse response) {
        ensureConfigured();
        if (body == null || !matches(properties.getUsername(), body.get("username"))
                || !matches(properties.getPassword(), body.get("password"))) {
            log.warn("DB Web View login failed from {}", request.getRemoteAddr());
            throw new UnauthorizedException("账号或密码错误");
        }
        log.info("DB Web View login succeeded from {}", request.getRemoteAddr());
        return Collections.singletonMap("csrf", jwt.issue(request, response));
    }

    @GetMapping("/api/session")
    public Map<String, Object> session(HttpServletRequest request) {
        JwtAuthService.Claims claims = (JwtAuthService.Claims) request.getAttribute(DbWebViewAuthFilter.CLAIMS);
        return Collections.singletonMap("csrf", jwt.csrf(claims));
    }

    @PostMapping("/api/logout")
    public Map<String, Object> logout(HttpServletRequest request, HttpServletResponse response) {
        jwt.clear(request, response);
        return Collections.singletonMap("ok", true);
    }

    @GetMapping("/api/settings")
    public Map<String, Object> settings() {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("readOnly", properties.isReadOnly()); result.put("sqlEnabled", sqlAvailable());
        return result;
    }

    @GetMapping("/api/sources")
    public Map<String, Object> sources() { return Collections.singletonMap("sources", service.sources()); }

    @GetMapping("/api/tables")
    public Map<String, Object> tables(@RequestParam String source) throws SQLException {
        return Collections.singletonMap("tables", service.tables(source));
    }

    @GetMapping("/api/rows")
    public Map<String, Object> rows(@RequestParam String source, @RequestParam String namespace,
                                    @RequestParam String table, @RequestParam(defaultValue = "0") int page,
                                    @RequestParam(defaultValue = "-1") int size,
                                    @RequestParam(required = false) String sort,
                                    @RequestParam(defaultValue = "false") boolean descending,
                                    @RequestParam(required = false) String filters) throws SQLException {
        return service.rows(source, namespace, table, page, size, parseFilters(filters), sort, descending);
    }

    @GetMapping("/api/structure")
    public Map<String, Object> structure(@RequestParam String source, @RequestParam String namespace,
                                          @RequestParam String table) throws SQLException {
        return service.structure(source, namespace, table);
    }

    @GetMapping(value = "/api/export", produces = "text/csv;charset=UTF-8")
    public ResponseEntity<byte[]> export(@RequestParam String source, @RequestParam String namespace,
                                         @RequestParam String table, @RequestParam(required = false) String sort,
                                         @RequestParam(defaultValue = "false") boolean descending,
                                         @RequestParam(required = false) String filters) throws SQLException {
        String csv = service.exportCsv(source, namespace, table, parseFilters(filters), sort, descending);
        return ResponseEntity.ok().header("Content-Disposition", "attachment; filename=table.csv")
                .header("Cache-Control", "no-store")
                .body(csv.getBytes(StandardCharsets.UTF_8));
    }

    private Map<String, String> parseFilters(String raw) {
        if (raw == null || raw.isEmpty()) return Collections.emptyMap();
        try {
            com.fasterxml.jackson.databind.JsonNode node = new com.fasterxml.jackson.databind.ObjectMapper().readTree(raw);
            if (!node.isObject() || node.size() > 20) throw new IllegalArgumentException("筛选格式错误");
            Map<String, String> result = new LinkedHashMap<>();
            node.fields().forEachRemaining(entry -> {
                if (!entry.getValue().isTextual()) throw new IllegalArgumentException("筛选值必须为文本");
                result.put(entry.getKey(), entry.getValue().asText());
            });
            return result;
        } catch (java.io.IOException ex) { throw new IllegalArgumentException("筛选格式错误", ex); }
    }

    @PostMapping("/api/rows")
    public Map<String, Object> insert(@RequestBody RowRequest request) throws SQLException {
        int count = service.insert(request.source, request.namespace, request.table, request.values);
        audit("insert", request, count);
        return affected(count);
    }

    @PutMapping("/api/rows")
    public Map<String, Object> update(@RequestBody RowRequest request) throws SQLException {
        int count = service.update(request.source, request.namespace, request.table, request.keys, request.values);
        audit("update", request, count);
        return affected(count);
    }

    @DeleteMapping("/api/rows")
    public Map<String, Object> delete(@RequestBody RowRequest request) throws SQLException {
        int count = service.delete(request.source, request.namespace, request.table, request.keys);
        audit("delete", request, count);
        return affected(count);
    }

    @PostMapping("/api/sql")
    public Map<String, Object> executeSql(@RequestBody SqlRequest request, HttpServletRequest http) throws SQLException {
        if (!sqlAvailable()) throw new IllegalArgumentException("SQL 控制台已关闭（配置表白名单时不可使用）");
        long started = System.nanoTime();
        try {
            Map<String, Object> result = sqlService.execute(request.source, request.sql);
            recordSql(request, http, result, null, (System.nanoTime() - started) / 1_000_000);
            return result;
        } catch (SQLException | RuntimeException ex) {
            recordSql(request, http, null, auditError(ex), (System.nanoTime() - started) / 1_000_000);
            throw ex;
        }
    }

    @GetMapping("/api/sql/history")
    public synchronized Map<String, Object> sqlHistory() {
        if (!sqlAvailable()) throw new IllegalArgumentException("SQL 控制台已关闭（配置表白名单时不可使用）");
        return Collections.singletonMap("history", new ArrayList<>(history));
    }

    private String auditError(Exception ex) {
        if (ex instanceof SQLException) {
            SQLException sql = (SQLException) ex;
            return "SQLState=" + sql.getSQLState() + " code=" + sql.getErrorCode();
        }
        return ex.getMessage();
    }

    private boolean sqlAvailable() {
        return properties.isSqlEnabled() && (properties.getAllowedTables() == null || properties.getAllowedTables().isEmpty());
    }

    private synchronized void recordSql(SqlRequest request, HttpServletRequest http, Map<String, Object> result,
                                        String error, long elapsed) {
        Map<String, Object> item = new LinkedHashMap<>();
        item.put("time", java.time.Instant.now().toString()); item.put("source", request.source);
        item.put("ip", http.getRemoteAddr()); item.put("elapsedMs", elapsed);
        item.put("kind", result == null ? "error" : result.get("kind"));
        item.put("affected", result == null ? null : result.get("affected"));
        item.put("rowCount", result == null ? null : result.get("rowCount"));
        item.put("error", error);
        if (properties.isSaveSqlText()) item.put("sql", request.sql);
        history.add(0, item);
        while (history.size() > Math.max(1, Math.min(1000, properties.getHistorySize()))) history.remove(history.size() - 1);
        log.info("DB Web View SQL source={} ip={} kind={} affected={} returned={} elapsedMs={} error={}",
                request.source, http.getRemoteAddr(), item.get("kind"), item.get("affected"), item.get("rowCount"), elapsed, error);
    }

    private void audit(String action, RowRequest request, int count) {
        log.info("DB Web View {} source={} namespace={} table={} affected={}",
                action, request.source, request.namespace, request.table, count);
    }

    private Map<String, Object> affected(int count) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("affected", count);
        return result;
    }

    private boolean matches(String expected, String actual) {
        byte[] left = expected.getBytes(StandardCharsets.UTF_8);
        byte[] right = (actual == null ? "" : actual).getBytes(StandardCharsets.UTF_8);
        return MessageDigest.isEqual(left, right);
    }

    private void ensureConfigured() {
        if (properties.getUsername() == null || properties.getUsername().trim().isEmpty()
                || properties.getPassword() == null || properties.getPassword().isEmpty()) {
            throw new IllegalStateException("需要先配置 db-web-view.username 和 db-web-view.password");
        }
    }

    @ExceptionHandler(UnauthorizedException.class)
    public ResponseEntity<Map<String, String>> unauthorized(UnauthorizedException ex) {
        return ResponseEntity.status(401).body(Collections.singletonMap("error", ex.getMessage()));
    }

    @ExceptionHandler({IllegalArgumentException.class, IllegalStateException.class, SQLException.class})
    public ResponseEntity<Map<String, String>> failed(Exception ex) {
        int code = ex instanceof IllegalArgumentException ? 400 : ex instanceof IllegalStateException ? 503 : 400;
        return ResponseEntity.status(code).body(Collections.singletonMap("error", ex.getMessage()));
    }

    public static class RowRequest {
        public String source;
        public String namespace;
        public String table;
        public Map<String, Object> keys;
        public Map<String, Object> values;
    }

    public static class SqlRequest {
        public String source;
        public String sql;
    }

    private static class UnauthorizedException extends RuntimeException {
        UnauthorizedException(String message) { super(message); }
    }
}
