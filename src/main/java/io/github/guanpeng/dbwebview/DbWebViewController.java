package io.github.guanpeng.dbwebview;

import org.springframework.core.io.ClassPathResource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpSession;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.sql.SQLException;
import java.util.Base64;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

@RestController
@RequestMapping("/db-web-view")
public class DbWebViewController {
    private static final Logger log = LoggerFactory.getLogger(DbWebViewController.class);
    private final DatabaseService service;
    private final SqlConsoleService sqlService;
    private final DbWebViewProperties properties;
    private final SecureRandom random = new SecureRandom();

    public DbWebViewController(DatabaseService service, SqlConsoleService sqlService, DbWebViewProperties properties) {
        this.service = service;
        this.sqlService = sqlService;
        this.properties = properties;
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
    public Map<String, Object> login(@RequestBody Map<String, String> body, HttpServletRequest request) {
        ensureConfigured();
        if (body == null || !matches(properties.getUsername(), body.get("username"))
                || !matches(properties.getPassword(), body.get("password"))) {
            log.warn("DB Web View login failed from {}", request.getRemoteAddr());
            throw new UnauthorizedException("账号或密码错误");
        }
        log.info("DB Web View login succeeded from {}", request.getRemoteAddr());
        HttpSession old = request.getSession(false);
        if (old != null) old.invalidate();
        HttpSession session = request.getSession(true);
        session.setMaxInactiveInterval(30 * 60);
        session.setAttribute(DbWebViewAuthFilter.AUTH, Boolean.TRUE);
        byte[] bytes = new byte[32];
        random.nextBytes(bytes);
        String csrf = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
        session.setAttribute(DbWebViewAuthFilter.CSRF, csrf);
        return Collections.singletonMap("csrf", csrf);
    }

    @GetMapping("/api/session")
    public Map<String, Object> session(HttpSession session) {
        return Collections.singletonMap("csrf", session.getAttribute(DbWebViewAuthFilter.CSRF));
    }

    @PostMapping("/api/logout")
    public Map<String, Object> logout(HttpSession session) {
        session.invalidate();
        return Collections.singletonMap("ok", true);
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
                                    @RequestParam(defaultValue = "-1") int size) throws SQLException {
        return service.rows(source, namespace, table, page, size);
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
    public Map<String, Object> executeSql(@RequestBody SqlRequest request) throws SQLException {
        Map<String, Object> result = sqlService.execute(request.source, request.sql);
        log.info("DB Web View SQL source={} kind={} affected={} returned={}", request.source,
                result.get("kind"), result.get("affected"), result.get("rowCount"));
        return result;
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
