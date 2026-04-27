package com.project.habit_tracker.security;

import jakarta.servlet.DispatcherType;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;

import java.util.Arrays;
import java.util.List;
import java.util.stream.Collectors;

@Configuration
public class SecurityConfig {

    @Value("${app.cors.allowedOrigins:}")
    private String allowedOrigins;

    @Value("${spring.profiles.active:local}")
    private String activeProfile;

    @Bean
    public SecurityFilterChain filterChain(HttpSecurity http, JwtAuthFilter jwtAuthFilter) throws Exception {
        return http
                .csrf(csrf -> csrf.disable())
                .cors(cors -> {}) // uses corsConfigurationSource() bean
                .sessionManagement(sm -> sm.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(auth -> auth
                        .dispatcherTypeMatchers(DispatcherType.ASYNC, DispatcherType.ERROR).permitAll()
                        .requestMatchers(HttpMethod.OPTIONS, "/**").permitAll()
                        // Spring Boot's BasicErrorController is mapped at /error;
                        // it must be permit-all so the JSON error body renders
                        // instead of being swallowed by `denyAll`.
                        .requestMatchers("/error").permitAll()
                        // Actuator health for load-balancer probes.
                        .requestMatchers("/actuator/health", "/actuator/health/**").permitAll()
                        .requestMatchers("/api/auth/**").permitAll()
                        .requestMatchers("/api/habits/**").authenticated()
                        .requestMatchers("/api/tasks/**").authenticated()
                        .requestMatchers("/api/accountability/**").authenticated()
                        .requestMatchers("/api/devices/**").authenticated()
                        .requestMatchers("/api/users/**").authenticated()
                        .requestMatchers("/api/me/**").authenticated()
                        .requestMatchers("/api/me").authenticated()
                        .anyRequest().denyAll()
                )
                .addFilterBefore(jwtAuthFilter, UsernamePasswordAuthenticationFilter.class)
                .build();
    }

    @Bean
    public CorsConfigurationSource corsConfigurationSource() {
        List<String> origins = Arrays.stream(allowedOrigins.split(","))
                .map(String::trim)
                .filter(s -> !s.isBlank())
                .collect(Collectors.toList());

        // Fail-fast in non-local profiles if the deployer forgot to set
        // CORS_ALLOWED_ORIGINS — combined with allowCredentials(true), an
        // empty origin list silently falls back to the dev default which
        // would let any localhost browser hit the prod API with cookies.
        boolean isLocalProfile = activeProfile == null
                || activeProfile.isBlank()
                || activeProfile.equalsIgnoreCase("local")
                || activeProfile.equalsIgnoreCase("test");
        if (origins.isEmpty()) {
            if (!isLocalProfile) {
                throw new IllegalStateException(
                    "CORS_ALLOWED_ORIGINS must be set explicitly in profile=" + activeProfile +
                    ". Refusing to start with credential-allowing CORS and an empty origin list."
                );
            }
            // Dev convenience only.
            origins = List.of("http://localhost:3000", "http://localhost:5173");
        }

        CorsConfiguration cfg = new CorsConfiguration();
        cfg.setAllowedOrigins(origins);
        cfg.setAllowedMethods(List.of("GET", "POST", "PUT", "PATCH", "DELETE", "OPTIONS"));
        // Explicit allowlist instead of "*" — required when allowCredentials
        // is true (browsers reject the wildcard with credentials anyway) and
        // narrows the surface against header-injection-based CSRF variants.
        cfg.setAllowedHeaders(List.of(
                "Authorization",
                "Content-Type",
                "Accept",
                "Last-Event-ID",
                "X-Requested-With"
        ));
        cfg.setExposedHeaders(List.of("Authorization"));
        cfg.setAllowCredentials(true);

        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/**", cfg);
        return source;
    }
}
