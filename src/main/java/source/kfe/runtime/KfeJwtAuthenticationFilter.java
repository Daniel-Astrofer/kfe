package source.kfe.runtime;

import io.jsonwebtoken.Claims;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.List;

public class KfeJwtAuthenticationFilter extends OncePerRequestFilter {

    private final KfeJwtVerifier jwtVerifier;

    public KfeJwtAuthenticationFilter(KfeJwtVerifier jwtVerifier) {
        this.jwtVerifier = jwtVerifier;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
            throws ServletException, IOException {
        String authorization = request.getHeader("Authorization");
        if (authorization == null || !authorization.startsWith("Bearer ")) {
            filterChain.doFilter(request, response);
            return;
        }

        try {
            Claims claims = jwtVerifier.verify(authorization.substring("Bearer ".length()));
            Long userId = Long.parseLong(claims.getId());
            List<SimpleGrantedAuthority> authorities = jwtVerifier.roles(claims).stream()
                    .map(KfeJwtAuthenticationFilter::authority)
                    .distinct()
                    .toList();
            SecurityContextHolder.getContext()
                    .setAuthentication(new UsernamePasswordAuthenticationToken(userId, null, authorities));
            filterChain.doFilter(request, response);
        } catch (RuntimeException exception) {
            SecurityContextHolder.clearContext();
            response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
            response.setContentType("application/json");
            response.getWriter().write("""
                    {"success":false,"message":"invalid session","errorCode":"INVALID_SESSION"}
                    """);
        }
    }

    private static SimpleGrantedAuthority authority(String role) {
        String normalized = role == null ? "USER" : role.trim().toUpperCase();
        if (!normalized.startsWith("ROLE_")) {
            normalized = "ROLE_" + normalized;
        }
        return new SimpleGrantedAuthority(normalized);
    }
}
