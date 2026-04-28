package com.project.rung.security;

import io.jsonwebtoken.*;
import io.jsonwebtoken.security.Keys;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.security.Key;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
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
        if (secret == null || secret.isBlank()) {
            throw new IllegalStateException(
                "app.jwt.secret / JWT_SECRET env var is not set. " +
                "For local dev add it to src/main/resources/application-local.properties"
            );
        }
        if (secret.length() < 32) {
            throw new IllegalStateException("JWT secret must be at least 32 characters");
        }
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
                .setId(java.util.UUID.randomUUID().toString())
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

    /** SHA-256 hex digest of a raw token string — safe to store in the DB. */
    public String hashToken(String token) {
        try {
            byte[] hash = MessageDigest.getInstance("SHA-256")
                    .digest(token.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder(64);
            for (byte b : hash) sb.append(String.format("%02x", b));
            return sb.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }
}
