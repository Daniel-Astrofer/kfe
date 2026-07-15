package source.kfe.runtime;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jwts;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.nio.charset.StandardCharsets;
import java.util.Date;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class KfeJwtVerifierTest {

    private static final String SECRET = "super_secret_jwt_key_that_is_long_enough_for_hs256_123!";

    @Test
    void verifiesCoreCompatibleTokenAndNormalizesRoles() {
        KfeJwtVerifier verifier = new KfeJwtVerifier(SECRET, (StringRedisTemplate) null, true);

        Claims claims = verifier.verify(token(SECRET, List.of("ROLE_admin", "user")));

        assertEquals("42", claims.getId());
        assertEquals(List.of("ADMIN", "USER"), verifier.roles(claims));
    }

    @Test
    void rejectsTokenSignedWithDifferentSecret() {
        KfeJwtVerifier verifier = new KfeJwtVerifier(SECRET, (StringRedisTemplate) null, true);

        assertThrows(RuntimeException.class, () -> verifier.verify(token(
                "different_secret_key_that_is_long_enough_for_hs256_123!",
                List.of("USER"))));
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
