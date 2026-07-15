package source.kfe.runtime;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jwts;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.util.Collection;
import java.util.List;

public class KfeJwtVerifier {

    private final SecretKey secretKey;
    private final StringRedisTemplate redisTemplate;
    private final boolean revocationCheckEnabled;

    public KfeJwtVerifier(
            @Value("${api.secret.token.secret}") String secret,
            org.springframework.beans.factory.ObjectProvider<StringRedisTemplate> redisTemplate,
            @Value("${kfe.security.jwt.revocation-check-enabled:true}") boolean revocationCheckEnabled) {
        this(secret, redisTemplate.getIfAvailable(), revocationCheckEnabled);
    }

    KfeJwtVerifier(String secret, StringRedisTemplate redisTemplate, boolean revocationCheckEnabled) {
        this.secretKey = io.jsonwebtoken.security.Keys.hmacShaKeyFor(secret.trim().getBytes(StandardCharsets.UTF_8));
        this.redisTemplate = redisTemplate;
        this.revocationCheckEnabled = revocationCheckEnabled;
    }

    public Claims verify(String token) {
        Claims claims = Jwts.parser()
                .verifyWith(secretKey)
                .build()
                .parseSignedClaims(token)
                .getPayload();
        if (isRevoked(claims)) {
            throw new IllegalStateException("JWT session is revoked");
        }
        return claims;
    }

    public List<String> roles(Claims claims) {
        Object rawRoles = claims.get("roles");
        if (rawRoles instanceof Collection<?> collection) {
            List<String> roles = collection.stream()
                    .map(String::valueOf)
                    .map(this::normalizeRole)
                    .filter(role -> !role.isBlank())
                    .distinct()
                    .toList();
            return roles.isEmpty() ? List.of("USER") : roles;
        }
        return List.of("USER");
    }

    private boolean isRevoked(Claims claims) {
        if (!revocationCheckEnabled) {
            return false;
        }
        Object rawSessionId = claims.get("sessionId");
        if (rawSessionId == null || String.valueOf(rawSessionId).isBlank()) {
            return false;
        }
        if (redisTemplate == null) {
            return false;
        }
        return Boolean.TRUE.equals(redisTemplate.hasKey("auth:jwt:revoked-session:" + rawSessionId));
    }

    private String normalizeRole(String role) {
        if (role == null) {
            return "";
        }
        String normalized = role.trim().toUpperCase();
        return normalized.startsWith("ROLE_") ? normalized.substring("ROLE_".length()) : normalized;
    }
}
