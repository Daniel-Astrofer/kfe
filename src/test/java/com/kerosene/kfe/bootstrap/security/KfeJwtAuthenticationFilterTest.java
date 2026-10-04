package com.kerosene.kfe.bootstrap.security;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jwts;
import jakarta.servlet.FilterChain;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.data.redis.RedisConnectionFailureException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;

import java.nio.charset.StandardCharsets;
import java.util.Date;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class KfeJwtAuthenticationFilterTest {
    @AfterEach void clearContext() { SecurityContextHolder.clearContext(); }

    @Test
    void validIdentityReachesDashboardChainWithoutChangingResponse() throws Exception {
        var verifier = validVerifier();
        var filter = new KfeJwtAuthenticationFilter(verifier);
        var request = request();
        var response = new MockHttpServletResponse();
        FilterChain chain = (req, res) -> {
            assertEquals(42L, SecurityContextHolder.getContext().getAuthentication().getPrincipal());
            assertTrue(SecurityContextHolder.getContext().getAuthentication().isAuthenticated());
            res.getWriter().write("dashboard");
        };
        filter.doFilter(request, response, chain);
        assertEquals(200, response.getStatus());
        assertEquals("dashboard", response.getContentAsString());
    }

    @Test
    void downstreamFailureDoesNotBecomeInvalidSessionOrClearVerifiedIdentity() {
        var filter = new KfeJwtAuthenticationFilter(validVerifier());
        var response = new MockHttpServletResponse();
        var failure = new IllegalStateException("dashboard provider unavailable");
        FilterChain chain = (req, res) -> { throw failure; };
        assertSame(failure, assertThrows(IllegalStateException.class,
                () -> filter.doFilter(request(), response, chain)));
        assertNotEquals(401, response.getStatus());
        assertEquals(42L, SecurityContextHolder.getContext().getAuthentication().getPrincipal());
    }

    @Test
    void invalidJwtRemainsUnauthorizedAndDoesNotReachDashboard() throws Exception {
        var verifier = mock(KfeJwtVerifier.class);
        when(verifier.verify("fixture-token")).thenThrow(new IllegalStateException("invalid issuer"));
        var response = new MockHttpServletResponse();
        var chain = mock(FilterChain.class);
        new KfeJwtAuthenticationFilter(verifier).doFilter(request(), response, chain);
        assertEquals(401, response.getStatus());
        assertTrue(response.getContentAsString().contains("INVALID_SESSION"));
        assertNull(SecurityContextHolder.getContext().getAuthentication());
        verifyNoInteractions(chain);
    }

    @Test
    void redisFailureDeniesAccessWithoutInvalidatingValidSessionAndRecovers() throws Exception {
        String fixtureKey = "synthetic_jwt_key_for_filter_tests_only_0123456789";
        var redis = mock(StringRedisTemplate.class);
        var verifier = new KfeJwtVerifier(fixtureKey, redis, true, true, "Kerosene-Auth", "kerosene-app");
        var filter = new KfeJwtAuthenticationFilter(verifier);
        String signedToken = Jwts.builder().subject("42").id("42")
                .issuer("Kerosene-Auth").audience().add("kerosene-app").and()
                .claim("sessionId", "fixture-session").claim("roles", List.of("USER"))
                .expiration(new Date(System.currentTimeMillis() + 300_000))
                .signWith(io.jsonwebtoken.security.Keys.hmacShaKeyFor(fixtureKey.getBytes(StandardCharsets.UTF_8)))
                .compact();
        when(redis.hasKey("auth:jwt:revoked-session:fixture-session"))
                .thenThrow(new RedisConnectionFailureException("sensitive fixture connection detail"))
                .thenReturn(false)
                .thenReturn(true);
        var unavailableRequest = request();
        unavailableRequest.removeHeader("Authorization");
        unavailableRequest.addHeader("Authorization", "Bearer " + signedToken);
        var unavailableResponse = new MockHttpServletResponse();
        var deniedChain = mock(FilterChain.class);
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(99L, null, List.of()));

        filter.doFilter(unavailableRequest, unavailableResponse, deniedChain);
        assertEquals(503, unavailableResponse.getStatus());
        assertTrue(unavailableResponse.getContentAsString().contains("SYS_500"));
        assertFalse(unavailableResponse.getContentAsString().contains("INVALID_SESSION"));
        assertFalse(unavailableResponse.getContentAsString().contains("sensitive fixture"));
        assertNull(SecurityContextHolder.getContext().getAuthentication());
        verifyNoInteractions(deniedChain);

        var recoveredResponse = new MockHttpServletResponse();
        FilterChain acceptedChain = (req, res) -> {
            assertEquals(42L, SecurityContextHolder.getContext().getAuthentication().getPrincipal());
            res.getWriter().write("dashboard");
        };
        filter.doFilter(unavailableRequest, recoveredResponse, acceptedChain);
        assertEquals(200, recoveredResponse.getStatus());
        assertEquals("dashboard", recoveredResponse.getContentAsString());

        var revokedResponse = new MockHttpServletResponse();
        filter.doFilter(unavailableRequest, revokedResponse, deniedChain);
        assertEquals(401, revokedResponse.getStatus());
        assertTrue(revokedResponse.getContentAsString().contains("INVALID_SESSION"));
        assertNull(SecurityContextHolder.getContext().getAuthentication());
        verifyNoInteractions(deniedChain);
    }

    private static MockHttpServletRequest request() {
        var request = new MockHttpServletRequest("GET", "/kfe/dashboard");
        request.addHeader("Authorization", "Bearer fixture-token");
        return request;
    }

    private static KfeJwtVerifier validVerifier() {
        var verifier = mock(KfeJwtVerifier.class);
        Claims claims = Jwts.claims().id("42").build();
        when(verifier.verify("fixture-token")).thenReturn(claims);
        when(verifier.roles(claims)).thenReturn(List.of("USER"));
        return verifier;
    }
}
