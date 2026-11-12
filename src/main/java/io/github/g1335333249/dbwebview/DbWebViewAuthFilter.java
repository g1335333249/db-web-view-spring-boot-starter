package io.github.g1335333249.dbwebview;

import org.springframework.web.filter.OncePerRequestFilter;

import javax.servlet.FilterChain;
import javax.servlet.ServletException;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import java.io.IOException;

public class DbWebViewAuthFilter extends OncePerRequestFilter {
    static final String CLAIMS = DbWebViewAuthFilter.class.getName() + ".claims";
    private final JwtAuthService jwt;
    private final AuditStore auditStore;

    public DbWebViewAuthFilter(JwtAuthService jwt, AuditStore auditStore) { this.jwt = jwt; this.auditStore = auditStore; }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        String path = request.getRequestURI().substring(request.getContextPath().length());
        return !path.startsWith("/db-web-view/api/");
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        response.setHeader("Cache-Control", "no-store");
        response.setHeader("X-Content-Type-Options", "nosniff");
        String path = request.getRequestURI().substring(request.getContextPath().length());
        if ("/db-web-view/api/login".equals(path) && "POST".equals(request.getMethod())) {
            chain.doFilter(request, response);
            return;
        }
        JwtAuthService.Claims claims = jwt.verify(request);
        if (claims == null) { reject(response, 401, "请先登录"); return; }
        try {
            if (auditStore.revoked(claims.jti)) { reject(response, 401, "登录已失效"); return; }
        } catch (java.sql.SQLException ex) { reject(response, 503, "认证存储不可用"); return; }
        request.setAttribute(CLAIMS, claims);
        if (!"/db-web-view/api/logout".equals(path)) {
            String refreshed = jwt.refreshIfNeeded(claims);
            if (refreshed != null) response.setHeader("X-DB-Web-View-Token", refreshed);
        }
        chain.doFilter(request, response);
    }

    private void reject(HttpServletResponse response, int status, String message) throws IOException {
        response.setStatus(status);
        response.setContentType("application/json;charset=UTF-8");
        response.getWriter().write("{\"error\":\"" + message + "\"}");
    }
}
