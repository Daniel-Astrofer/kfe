package com.kerosene.kfe.runtime;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jwts;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.nio.charset.StandardCharsets;
import java.util.Date;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.*;

class KfeJwtVerifierTest {

    private static final String SECRET = "super_secret_jwt_key_that_is_long_enough_for_hs256_123!";

    @Test
    void verifiesCoreCompatibleTokenAndNormalizesRoles() {
        KfeJwtVerifier verifier = new KfeJwtVerifier(SECRET, (StringRedisTemplate) null, true, false, null, null);

        Claims claims = verifier.verify(token(SECRET, List.of("ROLE_admin", "user")));

        assertEquals("42", claims.getId());
        assertEquals(List.of("ADMIN", "USER"), verifier.roles(claims));
    }

    @Test
    void rejectsTokenSignedWithDifferentSecret() {
        KfeJwtVerifier verifier = new KfeJwtVerifier(SECRET, (StringRedisTemplate) null, true, false, null, null);

        assertThrows(RuntimeException.class, () -> verifier.verify(token(
                "different_secret_key_that_is_long_enough_for_hs256_123!",
                List.of("USER"))));
    }

    @Test
    void acceptsTokenWithMatchingIssuerAndAudience() {
        KfeJwtVerifier verifier = new KfeJwtVerifier(SECRET, (StringRedisTemplate) null,
                true, false, "Kerosene-Auth", "kerosene-app");

        String token = Jwts.builder()
                .subject("42").id("42")
                .issuer("Kerosene-Auth")
                .audience().add("kerosene-app").and()
                .claim("sessionId", "session-1")
                .claim("roles", List.of("USER"))
                .issuedAt(new Date())
                .expiration(new Date(System.currentTimeMillis() + 300_000))
                .signWith(io.jsonwebtoken.security.Keys.hmacShaKeyFor(SECRET.getBytes(StandardCharsets.UTF_8)))
                .compact();

        Claims claims = verifier.verify(token);
        assertThat(claims.getIssuer()).isEqualTo("Kerosene-Auth");
    }

    @Test
    void rejectsTokenWithMissingIssuerWhenConfigured() {
        KfeJwtVerifier verifier = new KfeJwtVerifier(SECRET, (StringRedisTemplate) null,
                true, false, "Kerosene-Auth", null);

        assertThrows(IllegalStateException.class,
                () -> verifier.verify(token(SECRET, List.of("USER"))));
    }

    @Test
    void rejectsTokenWithWrongIssuer() {
        KfeJwtVerifier verifier = new KfeJwtVerifier(SECRET, (StringRedisTemplate) null,
                true, false, "Kerosene-Auth", null);

        String token = Jwts.builder()
                .subject("42").id("42")
                .issuer("Wrong-Issuer")
                .claim("sessionId", "session-1")
                .claim("roles", List.of("USER"))
                .issuedAt(new Date())
                .expiration(new Date(System.currentTimeMillis() + 300_000))
                .signWith(io.jsonwebtoken.security.Keys.hmacShaKeyFor(SECRET.getBytes(StandardCharsets.UTF_8)))
                .compact();

        assertThrows(IllegalStateException.class, () -> verifier.verify(token));
    }

    @Test
    void rejectsTokenWithMissingAudienceWhenConfigured() {
        KfeJwtVerifier verifier = new KfeJwtVerifier(SECRET, (StringRedisTemplate) null,
                true, false, null, "kerosene-app");

        assertThrows(IllegalStateException.class,
                () -> verifier.verify(token(SECRET, List.of("USER"))));
    }

    @Test
    void acceptsTokenWithoutIssuerWhenNoneConfigured() {
        KfeJwtVerifier verifier = new KfeJwtVerifier(SECRET, (StringRedisTemplate) null,
                true, false, null, null);

        assertDoesNotThrow(() -> verifier.verify(token(SECRET, List.of("USER"))));
    }

    @Test
    void rolesDefaultsToUserWhenMissing() {
        KfeJwtVerifier verifier = new KfeJwtVerifier(SECRET, (StringRedisTemplate) null, true, false, null, null);

        String token = Jwts.builder()
                .subject("42").id("42")
                .issuedAt(new Date())
                .expiration(new Date(System.currentTimeMillis() + 300_000))
                .signWith(io.jsonwebtoken.security.Keys.hmacShaKeyFor(SECRET.getBytes(StandardCharsets.UTF_8)))
                .compact();

        Claims claims = verifier.verify(token);
        assertThat(verifier.roles(claims)).isEqualTo(List.of("USER"));
    }

    private String token(String secret, List<String> roles) {
        return Jwts.builder()
                .subject("42")
                .id("42")
                .claim("sessionId", "session-1")
                .claim("roles", roles)
                .issuedAt(new Date())
                .expiration(new Date(System.currentTimeMillis() + 300_000))
                .signWith(io.jsonwebtoken.security.Keys.hmacShaKeyFor(secret.getBytes(StandardCharsets.UTF_8)))
                .compact();
    }
}
