package com.kerosene.kfe.runtime;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jwts;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.util.Collection;
import java.util.Date;
import java.util.List;

public class KfeJwtVerifier {

    private static final Logger log = LoggerFactory.getLogger(KfeJwtVerifier.class);

    private final SecretKey secretKey;
    private final StringRedisTemplate redisTemplate;
    private final boolean revocationCheckEnabled;
    private final boolean revocationRequired;
    private final String issuer;
    private final String audience;

    public KfeJwtVerifier(
            @Value("${api.secret.token.secret}") String secret,
            org.springframework.beans.factory.ObjectProvider<StringRedisTemplate> redisTemplate,
            @Value("${kfe.security.jwt.revocation-check-enabled:true}") boolean revocationCheckEnabled,
            @Value("${kfe.auth.revocation.required:false}") boolean revocationRequired,
            @Value("${kfe.auth.jwt.issuer:}") String issuer,
            @Value("${kfe.auth.jwt.audience:}") String audience) {
        this(secret, redisTemplate.getIfAvailable(), revocationCheckEnabled, revocationRequired, issuer, audience);
    }

    KfeJwtVerifier(String secret, StringRedisTemplate redisTemplate, boolean revocationCheckEnabled,
                   boolean revocationRequired, String issuer, String audience) {
        this.secretKey = io.jsonwebtoken.security.Keys.hmacShaKeyFor(secret.trim().getBytes(StandardCharsets.UTF_8));
        this.redisTemplate = redisTemplate;
        this.revocationCheckEnabled = revocationCheckEnabled;
        this.revocationRequired = revocationRequired;
        this.issuer = blankToNull(issuer);
        this.audience = blankToNull(audience);
    }

    public Claims verify(String token) {
        Claims claims = Jwts.parser()
                .verifyWith(secretKey)
                .build()
                .parseSignedClaims(token)
                .getPayload();

        // Require expiration claim
        Date expiration = claims.getExpiration();
        if (expiration == null) {
            throw new IllegalStateException("JWT is missing required 'exp' claim");
        }

        // Require issuer claim if configured
        if (issuer != null) {
            String tokenIssuer = claims.getIssuer();
            if (tokenIssuer == null || !issuer.equals(tokenIssuer)) {
                throw new IllegalStateException("JWT has invalid or missing 'iss' claim");
            }
        }

        // Require audience claim if configured
        if (audience != null) {
            String tokenAudience = claims.getAudience().stream()
                    .filter(aud -> audience.equals(aud))
                    .findFirst()
                    .orElse(null);
            if (tokenAudience == null) {
                throw new IllegalStateException("JWT has invalid or missing 'aud' claim");
            }
        }

        // Require jti if present (replay protection)
        if (claims.getId() == null) {
            log.warn("JWT is missing 'jti' claim; replay protection is not possible");
        }

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
            if (revocationRequired) {
                throw new IllegalStateException(
                        "JWT revocation check required but Redis is unavailable; failing closed");
            }
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

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }
}
