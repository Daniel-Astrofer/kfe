package source.kfe.runtime;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;

import java.util.Arrays;
import java.util.List;

@Configuration
@EnableWebSecurity
@EnableMethodSecurity
@ConditionalOnProperty(name = "kfe.standalone", havingValue = "true")
public class KfeStandaloneSecurityConfiguration {

    @Bean
    public SecurityFilterChain kfeSecurityFilterChain(
            HttpSecurity http,
            KfeJwtAuthenticationFilter jwtAuthenticationFilter,
            CorsConfigurationSource corsConfigurationSource) throws Exception {
        http
                .cors(cors -> cors.configurationSource(corsConfigurationSource))
                .csrf(AbstractHttpConfigurer::disable)
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(auth -> auth
                        .requestMatchers(
                                "/",
                                "/healthz",
                                "/health/live",
                                "/health/ready",
                                "/health/dependencies",
                                "/actuator/health",
                                "/actuator/health/**",
                                "/api/public/kfe/**",
                                "/internal/kfe/**",
                                "/error")
                        .permitAll()
                        .requestMatchers("/api/admin/kfe/**").hasRole("ADMIN")
                        .requestMatchers("/kfe/**").authenticated()
                        .anyRequest().denyAll())
                .addFilterBefore(jwtAuthenticationFilter, UsernamePasswordAuthenticationFilter.class);
        return http.build();
    }

    @Bean
    public KfeJwtVerifier kfeJwtVerifier(
            @Value("${api.secret.token.secret}") String secret,
            ObjectProvider<StringRedisTemplate> redisTemplate,
            @Value("${kfe.security.jwt.revocation-check-enabled:true}") boolean revocationCheckEnabled) {
        return new KfeJwtVerifier(secret, redisTemplate, revocationCheckEnabled);
    }

    @Bean
    public KfeJwtAuthenticationFilter kfeJwtAuthenticationFilter(KfeJwtVerifier jwtVerifier) {
        return new KfeJwtAuthenticationFilter(jwtVerifier);
    }

    @Bean
    public CorsConfigurationSource corsConfigurationSource(
            @Value("${app.cors.allowed-origins:http://localhost:3000,http://localhost:3001,http://localhost:8080,http://localhost:8081,http://localhost:8082,http://localhost:30080,http://localhost:30082,http://127.0.0.1:3000,http://127.0.0.1:3001,http://127.0.0.1:8080,http://127.0.0.1:8081,http://127.0.0.1:8082,http://127.0.0.1:30080,http://127.0.0.1:30082}") String allowedOrigins) {
        List<String> origins = Arrays.stream(allowedOrigins.split(","))
                .map(String::trim)
                .filter(origin -> !origin.isEmpty())
                .toList();
        if (origins.isEmpty() || origins.contains("*")) {
            throw new IllegalStateException("app.cors.allowed-origins must explicitly list trusted origins");
        }

        CorsConfiguration configuration = new CorsConfiguration();
        configuration.setAllowedOrigins(origins);
        configuration.setAllowedMethods(List.of("GET", "POST", "PUT", "PATCH", "DELETE", "OPTIONS"));
        configuration.setAllowedHeaders(List.of(
                "Authorization",
                "Content-Type",
                "Digest",
                "X-Correlation-Id",
                "X-Request-Id",
                "X-Requested-With",
                "X-Idempotency-Key",
                "Idempotency-Key",
                "X-Tx-Hash",
                "X-Device-Hash"));
        configuration.setExposedHeaders(List.of("X-Correlation-Id", "X-Request-Id"));
        configuration.setAllowCredentials(true);

        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/**", configuration);
        return source;
    }
}
