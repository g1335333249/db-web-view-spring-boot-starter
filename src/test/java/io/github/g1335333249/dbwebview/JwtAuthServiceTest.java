package io.github.g1335333249.dbwebview;

import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;

import static org.junit.jupiter.api.Assertions.*;

class JwtAuthServiceTest {
    private DbWebViewProperties properties(String secret) {
        DbWebViewProperties properties = new DbWebViewProperties();
        properties.setUsername("admin");
        properties.setJwtSecret(secret);
        return properties;
    }

    @Test void bearerTokenWorksAcrossInstancesAndRejectsTampering() {
        String shared = "0123456789abcdefghijklmnopqrstuvwxyz-shared-key";
        JwtAuthService podA = new JwtAuthService(properties(shared));
        JwtAuthService podB = new JwtAuthService(properties(shared));
        String token = podA.issue();
        MockHttpServletRequest otherPod = new MockHttpServletRequest();
        otherPod.addHeader("Authorization", "Bearer " + token);
        JwtAuthService.Claims claims = podB.verify(otherPod);
        assertNotNull(claims);
        assertNull(podB.refreshIfNeeded(claims));
        assertNull(new JwtAuthService(properties("a-different-shared-secret-0123456789-abcdef"))
                .verify(otherPod));
        MockHttpServletRequest tampered = new MockHttpServletRequest();
        tampered.addHeader("Authorization", "Bearer " + token.substring(0, token.length() - 1) + "x");
        assertNull(podB.verify(tampered));
        assertNull(podB.verify(new MockHttpServletRequest()));
    }

    @Test void secretMustBeSharedAndStrong() {
        assertThrows(IllegalStateException.class, () -> new JwtAuthService(properties("short")));
    }

}
