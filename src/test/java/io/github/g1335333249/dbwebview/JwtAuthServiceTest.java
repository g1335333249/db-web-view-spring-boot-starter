package io.github.g1335333249.dbwebview;

import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import javax.servlet.http.Cookie;

import static org.junit.jupiter.api.Assertions.*;

class JwtAuthServiceTest {
    private DbWebViewProperties properties(String secret) {
        DbWebViewProperties properties = new DbWebViewProperties();
        properties.setUsername("admin");
        properties.setJwtSecret(secret);
        return properties;
    }

    @Test void tokenWorksAcrossInstancesAndRejectsTampering() {
        String shared = "0123456789abcdefghijklmnopqrstuvwxyz-shared-key";
        JwtAuthService podA = new JwtAuthService(properties(shared));
        JwtAuthService podB = new JwtAuthService(properties(shared));
        MockHttpServletRequest login = new MockHttpServletRequest();
        login.setContextPath("/app");
        login.addHeader("X-Forwarded-Proto", "https");
        MockHttpServletResponse response = new MockHttpServletResponse();
        String csrf = podA.issue(login, response);
        String header = response.getHeader("Set-Cookie");
        assertNotNull(header);
        assertTrue(header.contains("Path=/app/db-web-view"));
        assertTrue(header.contains("HttpOnly"));
        assertTrue(header.contains("SameSite=Lax"));
        assertTrue(header.contains("Secure"));
        assertTrue(header.contains("Max-Age=43200"));
        String token = header.split(";", 2)[0].split("=", 2)[1];
        MockHttpServletRequest otherPod = new MockHttpServletRequest();
        otherPod.setCookies(new Cookie("DBWV_AUTH", token));
        JwtAuthService.Claims claims = podB.verify(otherPod);
        assertNotNull(claims);
        assertEquals(csrf, podB.csrf(claims));
        assertTrue(podB.validCsrf(claims, csrf));
        assertFalse(podB.validCsrf(claims, "wrong"));
        assertNull(new JwtAuthService(properties("a-different-shared-secret-0123456789-abcdef"))
                .verify(otherPod));
        otherPod.setCookies(new Cookie("DBWV_AUTH", token.substring(0, token.length() - 1) + "x"));
        assertNull(podB.verify(otherPod));
        MockHttpServletResponse logout = new MockHttpServletResponse();
        podB.clear(login, logout);
        assertTrue(logout.getHeader("Set-Cookie").contains("Max-Age=0"));
    }

    @Test void secretMustBeSharedAndStrong() {
        assertThrows(IllegalStateException.class, () -> new JwtAuthService(properties("short")));
    }

    @Test void publicCookiePathCanOverrideInternalContextPath() {
        DbWebViewProperties config = properties("0123456789abcdefghijklmnopqrstuvwxyz-shared-key");
        config.setCookiePath("/szqdzx-api/szhqdzx/db-web-view");
        JwtAuthService jwt = new JwtAuthService(config);
        MockHttpServletRequest login = new MockHttpServletRequest();
        MockHttpServletResponse response = new MockHttpServletResponse();
        jwt.issue(login, response);
        assertTrue(response.getHeader("Set-Cookie").contains("Path=/szqdzx-api/szhqdzx/db-web-view;"));
        config.setCookiePath("/gateway/db-web-view; Secure");
        assertThrows(IllegalStateException.class, () -> new JwtAuthService(config));
    }
}
