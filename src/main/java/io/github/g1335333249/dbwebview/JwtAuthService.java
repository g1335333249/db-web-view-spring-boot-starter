package io.github.g1335333249.dbwebview;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import javax.servlet.http.Cookie;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.Instant;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;

/** Stateless HS256 authentication shared by all pods that use the same configured secret. */
public class JwtAuthService {
    static final String COOKIE_NAME = "DBWV_AUTH";
    private static final String HEADER = "eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9";
    private static final String ISSUER = "db-web-view";
    private static final Base64.Encoder ENCODER = Base64.getUrlEncoder().withoutPadding();
    private static final Base64.Decoder DECODER = Base64.getUrlDecoder();
    private final byte[] key;
    private final long ttlSeconds;
    private final String username;
    private final String configuredCookiePath;
    private final ObjectMapper mapper = new ObjectMapper();
    private final SecureRandom random = new SecureRandom();

    public JwtAuthService(DbWebViewProperties properties) {
        String secret = properties.getJwtSecret();
        boolean credentialsConfigured = properties.getUsername() != null && !properties.getUsername().trim().isEmpty()
                && properties.getPassword() != null && !properties.getPassword().isEmpty();
        if ((secret == null || secret.isEmpty()) && !credentialsConfigured) {
            this.key = new byte[32];
            random.nextBytes(this.key);
        } else {
            if (secret == null || secret.getBytes(StandardCharsets.UTF_8).length < 32)
                throw new IllegalStateException("db-web-view.jwt-secret 至少需要 32 字节，所有 Pod 必须配置相同的值");
            this.key = secret.getBytes(StandardCharsets.UTF_8);
        }
        this.username = properties.getUsername();
        this.configuredCookiePath = validateCookiePath(properties.getCookiePath());
        this.ttlSeconds = Math.max(300, Math.min(7 * 24 * 3600, properties.getJwtTtlSeconds()));
    }

    public String issue(HttpServletRequest request, HttpServletResponse response) {
        byte[] id = new byte[32];
        random.nextBytes(id);
        String jti = ENCODER.encodeToString(id);
        writeCookie(request, response, token(jti, Instant.now().getEpochSecond()), (int) ttlSeconds);
        return csrf(jti);
    }

    public Claims verify(HttpServletRequest request) {
        String raw = null;
        if (request.getCookies() != null) for (Cookie cookie : request.getCookies()) {
            if (COOKIE_NAME.equals(cookie.getName())) { raw = cookie.getValue(); break; }
        }
        if (raw == null || raw.length() > 4096) return null;
        String[] parts = raw.split("\\.", -1);
        if (parts.length != 3 || !HEADER.equals(parts[0])) return null;
        try {
            byte[] signature = DECODER.decode(parts[2]);
            if (!parts[2].equals(ENCODER.encodeToString(signature))) return null;
            byte[] expected = hmac((parts[0] + "." + parts[1]).getBytes(StandardCharsets.US_ASCII));
            if (!MessageDigest.isEqual(expected, signature)) return null;
            JsonNode claims = mapper.readTree(DECODER.decode(parts[1]));
            String jti = text(claims, "jti");
            long iat = number(claims, "iat"), exp = number(claims, "exp");
            long now = Instant.now().getEpochSecond();
            if (!ISSUER.equals(text(claims, "iss")) || !java.util.Objects.equals(username, text(claims, "sub"))
                    || jti == null || !jti.matches("[A-Za-z0-9_-]{43}")
                    || iat > now + 60 || exp <= now || exp <= iat || exp - iat > ttlSeconds) return null;
            return new Claims(jti, exp);
        } catch (RuntimeException | java.io.IOException ex) { return null; }
    }

    public void refreshIfNeeded(HttpServletRequest request, HttpServletResponse response, Claims claims) {
        long now = Instant.now().getEpochSecond();
        if (claims.exp - now < ttlSeconds / 2)
            writeCookie(request, response, token(claims.jti, now), (int) ttlSeconds);
    }

    public String csrf(Claims claims) { return csrf(claims.jti); }

    public boolean validCsrf(Claims claims, String provided) {
        return provided != null && MessageDigest.isEqual(csrf(claims).getBytes(StandardCharsets.US_ASCII),
                provided.getBytes(StandardCharsets.US_ASCII));
    }

    public void clear(HttpServletRequest request, HttpServletResponse response) {
        writeCookie(request, response, "", 0);
    }

    private String csrf(String jti) {
        return ENCODER.encodeToString(hmac(("csrf:" + jti).getBytes(StandardCharsets.US_ASCII)));
    }

    private String token(String jti, long now) {
        Map<String, Object> claims = new LinkedHashMap<>();
        claims.put("iss", ISSUER); claims.put("sub", username);
        claims.put("iat", now); claims.put("exp", now + ttlSeconds); claims.put("jti", jti);
        try {
            String payload = ENCODER.encodeToString(mapper.writeValueAsBytes(claims));
            String signingInput = HEADER + "." + payload;
            return signingInput + "." + ENCODER.encodeToString(hmac(signingInput.getBytes(StandardCharsets.US_ASCII)));
        } catch (java.io.IOException ex) { throw new IllegalStateException("JWT 生成失败", ex); }
    }

    private byte[] hmac(byte[] input) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(key, "HmacSHA256"));
            return mac.doFinal(input);
        } catch (java.security.GeneralSecurityException ex) { throw new IllegalStateException("HMAC 不可用", ex); }
    }

    private void writeCookie(HttpServletRequest request, HttpServletResponse response, String value, int maxAge) {
        String path = configuredCookiePath == null ? request.getContextPath() + "/db-web-view" : configuredCookiePath;
        boolean secure = request.isSecure() || "https".equalsIgnoreCase(request.getHeader("X-Forwarded-Proto"));
        String cookie = COOKIE_NAME + "=" + value + "; Path=" + path + "; Max-Age=" + maxAge
                + "; HttpOnly; SameSite=Lax" + (secure ? "; Secure" : "");
        response.addHeader("Set-Cookie", cookie);
    }

    private String validateCookiePath(String path) {
        if (path == null || path.isEmpty()) return null;
        if (!path.startsWith("/") || path.endsWith("/") || !path.endsWith("/db-web-view")
                || path.contains("//") || !path.matches("/[A-Za-z0-9_./~-]+"))
            throw new IllegalStateException("db-web-view.cookie-path 必须是以 /db-web-view 结尾的公开绝对路径");
        return path;
    }

    private String text(JsonNode node, String name) {
        JsonNode value = node.get(name);
        return value != null && value.isTextual() ? value.asText() : null;
    }

    private long number(JsonNode node, String name) {
        JsonNode value = node.get(name);
        return value != null && value.isIntegralNumber() ? value.asLong() : Long.MIN_VALUE;
    }

    public static final class Claims {
        final String jti;
        final long exp;
        Claims(String jti, long exp) { this.jti = jti; this.exp = exp; }
    }
}
