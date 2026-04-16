package com.project.habit_tracker.security;

import io.jsonwebtoken.*;
import io.jsonwebtoken.security.Keys;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.security.Key;
import java.time.Instant;
import java.util.Date;

@Service
public class JwtService {

    private final Key key;
    private final String issuer;
    private final long accessTokenExpirationMinutes;
    private final long refreshTokenExpirationMinutes;

    public JwtService(
            @Value("${app.jwt.secret}") String secret,
            @Value("${app.jwt.issuer}") String issuer,
            @Value("${app.jwt.expirationMinutes}") long accessTokenExpirationMinutes,
            @Value("${app.jwt.refreshExpirationMinutes:43200}") long refreshTokenExpirationMinutes
    ) {
        this.key = Keys.hmacShaKeyFor(secret.getBytes(StandardCharsets.UTF_8));
        this.issuer = issuer;
        this.accessTokenExpirationMinutes = accessTokenExpirationMinutes;
        this.refreshTokenExpirationMinutes = refreshTokenExpirationMinutes;
    }

    public String createAccessToken(Long userId, String email) {
        return createToken(userId, email, "access", accessTokenExpirationMinutes);
    }

    public String createRefreshToken(Long userId, String email) {
        return createToken(userId, email, "refresh", refreshTokenExpirationMinutes);
    }

    public Long parseRefreshToken(String token) {
        Claims claims = parse(token).getBody();
        String type = claims.get("type", String.class);
        if (!"refresh".equals(type)) {
            throw new JwtException("Invalid token type");
        }
        return Long.valueOf(claims.getSubject());
    }

    public long extractExpirationEpochSeconds(String token) {
        return parse(token).getBody().getExpiration().toInstant().getEpochSecond();
    }

    private String createToken(Long userId, String email, String type, long expirationMinutes) {
        Instant now = Instant.now();
        Instant exp = now.plusSeconds(expirationMinutes * 60);

        return Jwts.builder()
                .setIssuer(issuer)
                .setSubject(String.valueOf(userId))
                .claim("email", email)
                .claim("type", type)
                .setIssuedAt(Date.from(now))
                .setExpiration(Date.from(exp))
                .signWith(key, SignatureAlgorithm.HS256)
                .compact();
    }

    public Jws<Claims> parse(String token) {
        return Jwts.parserBuilder()
                .setSigningKey(key)
                .requireIssuer(issuer)
                .build()
                .parseClaimsJws(token);
    }
}
