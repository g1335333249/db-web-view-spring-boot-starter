package io.github.guanpeng.dbwebview;

import org.springframework.web.filter.OncePerRequestFilter;

import javax.servlet.FilterChain;
import javax.servlet.ServletException;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import javax.servlet.http.HttpSession;
import java.io.IOException;

public class DbWebViewAuthFilter extends OncePerRequestFilter {
    public static final String AUTH = DbWebViewAuthFilter.class.getName() + ".auth";
    public static final String CSRF = DbWebViewAuthFilter.class.getName() + ".csrf";
    private final DbWebViewProperties properties;

    public DbWebViewAuthFilter(DbWebViewProperties properties) { this.properties = properties; }

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
        HttpSession session = request.getSession(false);
        if (session == null || !Boolean.TRUE.equals(session.getAttribute(AUTH))) {
            reject(response, 401, "请先登录");
            return;
        }
        if (!"GET".equals(request.getMethod()) && !"HEAD".equals(request.getMethod())) {
            String expected = (String) session.getAttribute(CSRF);
            String provided = request.getHeader("X-DB-Web-View-CSRF");
            if (expected == null || !expected.equals(provided)) {
                reject(response, 403, "CSRF 校验失败");
                return;
            }
        }
        chain.doFilter(request, response);
    }

    private void reject(HttpServletResponse response, int status, String message) throws IOException {
        response.setStatus(status);
        response.setContentType("application/json;charset=UTF-8");
        response.getWriter().write("{\"error\":\"" + message + "\"}");
    }
}
