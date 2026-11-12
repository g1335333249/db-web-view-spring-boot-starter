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
    private final AuditStore auditStore;

    public DbWebViewController(DatabaseService service, SqlConsoleService sqlService, DbWebViewProperties properties, JwtAuthService jwt, AuditStore auditStore) {
        this.service = service;
        this.sqlService = sqlService;
        this.properties = properties;
        this.jwt = jwt;
        this.auditStore = auditStore;
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
    public Map<String, Object> login(@RequestBody Map<String, String> body, HttpServletRequest request, HttpServletResponse response) throws SQLException {
        ensureConfigured();
        String ip = clientIp(request);
        if (auditStore.tooManyFailures(ip)) throw new TooManyRequestsException("登录失败次数过多，请稍后再试");
        if (body == null || !matches(properties.getUsername(), body.get("username"))
                || !matches(properties.getPassword(), body.get("password"))) {
            auditStore.failedLogin(ip);
            log.warn("DB Web View login failed from {}", ip);
            throw new UnauthorizedException("账号或密码错误");
        }
        auditStore.successfulLogin(ip);
        log.info("DB Web View login succeeded from {}", ip);
        return Collections.singletonMap("csrf", jwt.issue(request, response));
    }

    @GetMapping("/api/session")
    public Map<String, Object> session(HttpServletRequest request) {
        JwtAuthService.Claims claims = (JwtAuthService.Claims) request.getAttribute(DbWebViewAuthFilter.CLAIMS);
        return Collections.singletonMap("csrf", jwt.csrf(claims));
    }

    @PostMapping("/api/logout")
    public Map<String, Object> logout(HttpServletRequest request, HttpServletResponse response) throws SQLException {
        JwtAuthService.Claims claims = (JwtAuthService.Claims) request.getAttribute(DbWebViewAuthFilter.CLAIMS);
        try { auditStore.revoke(claims.jti, claims.exp); }
        finally { jwt.clear(request, response); }
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
                                    @RequestParam(required = false) String filters,
                                    @RequestParam(required = false) String cursor) throws SQLException {
        return service.rows(source, namespace, table, page, size, parseFilters(filters), sort, descending, cursor);
    }

    @GetMapping("/api/structure")
    public Map<String, Object> structure(@RequestParam String source, @RequestParam String namespace,
                                          @RequestParam String table) throws SQLException {
        return service.structure(source, namespace, table);
    }

    @GetMapping(value = "/api/export", produces = "text/csv;charset=UTF-8")
    public void export(@RequestParam String source, @RequestParam String namespace,
                       @RequestParam String table, @RequestParam(required = false) String sort,
                       @RequestParam(defaultValue = "false") boolean descending,
                       @RequestParam(required = false) String filters,
                       HttpServletResponse response) throws SQLException, java.io.IOException {
        List<FilterCondition> checked = parseFilters(filters);
        response.setContentType("text/csv;charset=UTF-8");
        response.setHeader("Content-Disposition", "attachment; filename=table.csv");
        response.setHeader("Cache-Control", "no-store");
        service.writeCsv(response.getOutputStream(), source, namespace, table, checked, sort, descending);
    }

    private List<FilterCondition> parseFilters(String raw) {
        if (raw == null || raw.isEmpty()) return Collections.emptyList();
        try {
            com.fasterxml.jackson.databind.JsonNode node = new com.fasterxml.jackson.databind.ObjectMapper().readTree(raw);
            if (node.size() > 20) throw new IllegalArgumentException("筛选条件过多");
            List<FilterCondition> result = new ArrayList<>();
            if (node.isObject()) {
                node.fields().forEachRemaining(entry -> {
                    if (!entry.getValue().isTextual()) throw new IllegalArgumentException("筛选值必须为文本");
                    result.add(new FilterCondition(entry.getKey(), "eq", entry.getValue().asText(), null));
                });
            } else if (node.isArray()) {
                for (com.fasterxml.jackson.databind.JsonNode item : node) {
                    if (!item.isObject() || !item.path("column").isTextual() || !item.path("operator").isTextual())
                        throw new IllegalArgumentException("筛选格式错误");
                    for (String key : new String[]{"value", "value2"})
                        if (item.has(key) && !item.get(key).isTextual()) throw new IllegalArgumentException("筛选值必须为文本");
                    result.add(new FilterCondition(item.get("column").asText(), item.get("operator").asText(),
                            item.has("value") ? item.get("value").asText() : null,
                            item.has("value2") ? item.get("value2").asText() : null));
                }
            } else throw new IllegalArgumentException("筛选格式错误");
            return result;
        } catch (java.io.IOException ex) { throw new IllegalArgumentException("筛选格式错误", ex); }
    }

    @PostMapping("/api/rows")
    public Map<String, Object> insert(@RequestBody RowRequest request, HttpServletRequest http) throws SQLException {
        auditStore.checkAvailable();
        int count = service.insert(request.source, request.namespace, request.table, request.values);
        return affected(count, audit("insert", request, count, http));
    }

    @PutMapping("/api/rows")
    public Map<String, Object> update(@RequestBody RowRequest request, HttpServletRequest http) throws SQLException {
        auditStore.checkAvailable();
        int count = service.update(request.source, request.namespace, request.table, request.keys, request.values);
        return affected(count, audit("update", request, count, http));
    }

    @DeleteMapping("/api/rows")
    public Map<String, Object> delete(@RequestBody RowRequest request, HttpServletRequest http) throws SQLException {
        auditStore.checkAvailable();
        int count = service.delete(request.source, request.namespace, request.table, request.keys);
        return affected(count, audit("delete", request, count, http));
    }

    @PostMapping("/api/sql")
    public Map<String, Object> executeSql(@RequestBody SqlRequest request, HttpServletRequest http) throws SQLException {
        if (!sqlAvailable()) throw new IllegalArgumentException("SQL 控制台已关闭（配置表白名单时不可使用）");
        auditStore.checkAvailable();
        long started = System.nanoTime();
        Map<String, Object> result;
        try { result = sqlService.execute(request.source, request.sql,
                request.executionId == null ? java.util.UUID.randomUUID().toString() : request.executionId, false); }
        catch (SQLException | RuntimeException ex) {
            recordSql(request, http, null, auditError(ex), (System.nanoTime() - started) / 1_000_000);
            throw ex;
        }
        if (!recordSql(request, http, result, null, (System.nanoTime() - started) / 1_000_000))
            result.put("auditWarning", "操作已执行，但审计写入失败");
        return result;
    }

    @PostMapping("/api/sql/explain")
    public Map<String, Object> explainSql(@RequestBody SqlRequest request, HttpServletRequest http) throws SQLException {
        if (!sqlAvailable()) throw new IllegalArgumentException("SQL 控制台已关闭");
        auditStore.checkAvailable();
        long started = System.nanoTime();
        Map<String, Object> result;
        try { result = sqlService.execute(request.source, request.sql,
                request.executionId == null ? java.util.UUID.randomUUID().toString() : request.executionId, true); }
        catch (SQLException | RuntimeException ex) {
            recordSql(request, http, null, auditError(ex), (System.nanoTime() - started) / 1_000_000);
            throw ex;
        }
        if (!recordSql(request, http, result, null, (System.nanoTime() - started) / 1_000_000))
            result.put("auditWarning", "操作已执行，但审计写入失败");
        return result;
    }

    @PostMapping("/api/sql/cancel")
    public Map<String, Object> cancelSql(@RequestBody SqlRequest request) throws SQLException {
        if (!sqlAvailable()) throw new IllegalArgumentException("SQL 控制台已关闭");
        return Collections.singletonMap("requested", sqlService.cancel(request.executionId));
    }

    @GetMapping("/api/sql/history")
    public Map<String, Object> sqlHistory() throws SQLException {
        if (!sqlAvailable()) throw new IllegalArgumentException("SQL 控制台已关闭（配置表白名单时不可使用）");
        return Collections.singletonMap("history", auditStore.sqlHistory());
    }

    @GetMapping("/api/audit")
    public Map<String, Object> auditHistory() throws SQLException {
        return Collections.singletonMap("history", auditStore.auditHistory());
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

    private boolean recordSql(SqlRequest request, HttpServletRequest http, Map<String, Object> result,
                              String error, long elapsed) {
        Map<String, Object> item = new LinkedHashMap<>();
        item.put("time", java.time.Instant.now().toString()); item.put("action", "sql");
        item.put("source", request.source); item.put("ip", clientIp(http));
        item.put("elapsedMs", elapsed); item.put("kind", result == null ? "error" : result.get("kind"));
        item.put("affected", result == null ? null : result.get("affected"));
        item.put("rowCount", result == null ? null : result.get("rowCount"));
        item.put("error", error);
        if (properties.isSaveSqlText()) item.put("sql", request.sql);
        boolean saved = saveAudit(item);
        log.info("DB Web View SQL source={} ip={} kind={} affected={} returned={} elapsedMs={} error={}",
                request.source, clientIp(http), item.get("kind"), item.get("affected"), item.get("rowCount"), elapsed, error);
        return saved;
    }

    private boolean audit(String action, RowRequest request, int count, HttpServletRequest http) {
        Map<String, Object> item = new LinkedHashMap<>();
        item.put("time", java.time.Instant.now().toString()); item.put("action", action);
        item.put("source", request.source); item.put("namespace", request.namespace);
        item.put("table", request.table); item.put("ip", clientIp(http));
        item.put("kind", "update"); item.put("affected", count);
        boolean saved = saveAudit(item);
        log.info("DB Web View {} source={} namespace={} table={} affected={}",
                action, request.source, request.namespace, request.table, count);
        return saved;
    }

    private boolean saveAudit(Map<String, Object> item) {
        try { auditStore.record(item); return true; }
        catch (SQLException ex) { log.error("DB Web View audit write failed after operation", ex); return false; }
    }

    private Map<String, Object> affected(int count, boolean auditSaved) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("affected", count);
        if (!auditSaved) result.put("auditWarning", "操作已执行，但审计写入失败");
        return result;
    }

    private String clientIp(HttpServletRequest request) {
        if (properties.isLoginTrustForwardedFor()) {
            String forwarded = request.getHeader("X-Forwarded-For");
            if (forwarded != null) {
                String first = forwarded.split(",", 2)[0].trim();
                if (!first.isEmpty() && first.length() <= 64 && first.matches("[0-9A-Fa-f:.]+")) return first;
            }
        }
        return request.getRemoteAddr();
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

    @ExceptionHandler(TooManyRequestsException.class)
    public ResponseEntity<Map<String, String>> tooMany(TooManyRequestsException ex) {
        return ResponseEntity.status(429).header("Retry-After", String.valueOf(Math.max(60, properties.getLoginWindowSeconds())))
                .body(Collections.singletonMap("error", ex.getMessage()));
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
        public String executionId;
    }

    private static class TooManyRequestsException extends RuntimeException {
        TooManyRequestsException(String message) { super(message); }
    }

    private static class UnauthorizedException extends RuntimeException {
        UnauthorizedException(String message) { super(message); }
    }
}
