package com.project.habit_tracker.security;

import com.project.habit_tracker.entity.User;
import com.project.habit_tracker.repository.UserRepository;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.ExpiredJwtException;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.Collections;

@Component
public class JwtAuthFilter extends OncePerRequestFilter {

    private final JwtService jwtService;
    private final UserRepository userRepo;

    public JwtAuthFilter(JwtService jwtService, UserRepository userRepo) {
        this.jwtService = jwtService;
        this.userRepo = userRepo;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {

        String auth = request.getHeader("Authorization");
        if (auth == null || !auth.startsWith("Bearer ")) {
            chain.doFilter(request, response);
            return;
        }

        String token = auth.substring("Bearer ".length()).trim();
        try {
            Claims claims = jwtService.parse(token).getBody();
            String type = claims.get("type", String.class);
            if (!"access".equals(type)) {
                chain.doFilter(request, response);
                return;
            }
            Long userId = Long.valueOf(claims.getSubject());

            User user = userRepo.findById(userId).orElse(null);
            if (user != null) {
                var principal = new AuthPrincipal(user.getId(), user.getEmail(), user.getUsername());
                var authentication = new UsernamePasswordAuthenticationToken(principal, null, Collections.emptyList());
                SecurityContextHolder.getContext().setAuthentication(authentication);
            }
        } catch (ExpiredJwtException expired) {
            // Distinguish "expired but otherwise valid" from "tampered /
            // malformed" so the iOS/macOS client can branch:
            //   - expired → trigger refresh-token flow
            //   - invalid → drop session, force re-sign-in
            // Without this header both cases looked like a generic 401 and
            // the client had no signal to choose between them.
            response.setHeader(
                "WWW-Authenticate",
                "Bearer error=\"invalid_token\", error_description=\"expired\""
            );
            // Fall through unauthenticated; SecurityConfig returns the 401.
        } catch (Exception ignored) {
            response.setHeader(
                "WWW-Authenticate",
                "Bearer error=\"invalid_token\", error_description=\"malformed\""
            );
            // Fall through unauthenticated.
        }

        chain.doFilter(request, response);
    }

    public record AuthPrincipal(Long userId, String email, String username) {
    }
}
