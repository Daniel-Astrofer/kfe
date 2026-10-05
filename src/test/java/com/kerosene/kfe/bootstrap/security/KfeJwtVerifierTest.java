package com.kerosene.kfe.bootstrap.security;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jwts;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.data.redis.RedisConnectionFailureException;
import org.springframework.core.io.support.ResourcePropertySource;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Date;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class KfeJwtVerifierTest {

    private static final String SECRET = "super_secret_jwt_key_that_is_long_enough_for_hs256_123!";

    @ParameterizedTest
    @ValueSource(ints = {32, 64})
    void standaloneProductionDefaultsAcceptCoreClaimsAndCheckRevocation(int keySize) throws Exception {
        var environment = new MockEnvironment();
        environment.getPropertySources().addLast(new ResourcePropertySource("classpath:kfe-service-defaults.properties"));
        String issuer = environment.getRequiredProperty("kfe.auth.jwt.issuer");
        String audience = environment.getRequiredProperty("kfe.auth.jwt.audience");
        assertEquals("Kerosene-Auth", issuer);
        assertEquals("kerosene-app", audience);
        String fixtureKey = "test".repeat(keySize / 4);
        var redis = mock(StringRedisTemplate.class);
        when(redis.hasKey("auth:jwt:revoked-session:fixture-session")).thenReturn(false);
        var verifier = new KfeJwtVerifier(fixtureKey, redis, true, true, issuer, audience);
        String coreCompatibleToken = Jwts.builder().subject("42").id("42")
                .issuer("Kerosene-Auth").audience().add("kerosene-app").and()
                .claim("sessionId", "fixture-session").claim("roles", List.of("USER"))
                .issuedAt(new Date()).expiration(new Date(System.currentTimeMillis() + 300_000))
                .signWith(io.jsonwebtoken.security.Keys.hmacShaKeyFor(fixtureKey.getBytes(StandardCharsets.UTF_8)))
                .compact();
        assertEquals("42", verifier.verify(coreCompatibleToken).getId());
        verify(redis).hasKey("auth:jwt:revoked-session:fixture-session");
        when(redis.hasKey("auth:jwt:revoked-session:fixture-session")).thenReturn(true);
        assertThrows(IllegalStateException.class, () -> verifier.verify(coreCompatibleToken));
    }

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

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void unavailableRevocationNeverAcceptsTokenAndRecoversWhenRedisDoes(boolean required) {
        var redis = mock(StringRedisTemplate.class);
        var verifier = new KfeJwtVerifier(SECRET, redis, true, required, null, null);
        String signedToken = token(SECRET, List.of("USER"));
        when(redis.hasKey("auth:jwt:revoked-session:session-1"))
                .thenThrow(new RedisConnectionFailureException("sensitive fixture connection detail"))
                .thenReturn(false)
                .thenReturn(true);

        var failure = assertThrows(KfeAuthenticationUnavailableException.class,
                () -> verifier.verify(signedToken));
        assertEquals("Authentication dependency unavailable", failure.getMessage());
        assertEquals("42", verifier.verify(signedToken).getId());
        assertThrows(IllegalStateException.class, () -> verifier.verify(signedToken));
    }

    @Test
    void indeterminateRevocationResultDoesNotGrantAccess() {
        var redis = mock(StringRedisTemplate.class);
        when(redis.hasKey("auth:jwt:revoked-session:session-1")).thenReturn(null);
        var verifier = new KfeJwtVerifier(SECRET, redis, true, true, null, null);
        assertThrows(KfeAuthenticationUnavailableException.class,
                () -> verifier.verify(token(SECRET, List.of("USER"))));
    }

    @Test
    void requiredMissingRedisIsUnavailableRatherThanInvalidCredential() {
        var verifier = new KfeJwtVerifier(SECRET, (StringRedisTemplate) null, true, true, null, null);
        assertThrows(KfeAuthenticationUnavailableException.class,
                () -> verifier.verify(token(SECRET, List.of("USER"))));
    }

    @Test
    void requiredRevocationRejectsTokenWithoutSessionIdentity() {
        var redis = mock(StringRedisTemplate.class);
        var verifier = new KfeJwtVerifier(SECRET, redis, true, true, null, null);
        String tokenWithoutSession = Jwts.builder()
                .subject("42").id("42")
                .issuedAt(new Date())
                .expiration(new Date(System.currentTimeMillis() + 300_000))
                .signWith(io.jsonwebtoken.security.Keys.hmacShaKeyFor(SECRET.getBytes(StandardCharsets.UTF_8)))
                .compact();

        assertThrows(KfeAuthenticationUnavailableException.class, () -> verifier.verify(tokenWithoutSession));
        verifyNoInteractions(redis);
    }

    @Test
    void acceptsPreviousSigningKeyOnlyDuringExplicitRotationWindow() {
        String previous = "previous_secret_key_that_is_long_enough_for_hs256_123!";
        var verifier = new KfeJwtVerifier(
                SECRET, (StringRedisTemplate) null, true, false, null, null, previous,
                Instant.now().plusSeconds(60).toString());
        String token = Jwts.builder()
                .subject("42").id("42").claim("sessionId", "session-1")
                .issuedAt(new Date()).expiration(new Date(System.currentTimeMillis() + 300_000))
                .signWith(io.jsonwebtoken.security.Keys.hmacShaKeyFor(previous.getBytes(StandardCharsets.UTF_8)))
                .compact();

        assertDoesNotThrow(() -> verifier.verify(token));
    }

    @Test
    void invalidSignatureIsRejectedBeforeRevocationLookup() {
        var redis = mock(StringRedisTemplate.class);
        var verifier = new KfeJwtVerifier(SECRET, redis, true, true, null, null);
        assertThrows(io.jsonwebtoken.security.SignatureException.class, () -> verifier.verify(token(
                "different_secret_key_that_is_long_enough_for_hs256_123!", List.of("USER"))));
        verifyNoInteractions(redis);
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
