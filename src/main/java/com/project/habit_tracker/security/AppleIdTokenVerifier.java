package com.project.habit_tracker.security;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jwts;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;

import java.math.BigInteger;
import java.security.KeyFactory;
import java.security.PublicKey;
import java.security.spec.RSAPublicKeySpec;
import java.time.Instant;
import java.util.Arrays;
import java.util.Base64;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.locks.ReentrantLock;

/**
 * Verifies Apple's identity-token JWS against the Apple JWKS endpoint.
 *
 * Apple signs ID tokens with rotating RSA keys published at
 * <a href="https://appleid.apple.com/auth/keys">Apple's JWKS</a>. We
 * cache them for an hour, refreshing on cache miss or expiry. Verification
 * checks signature + issuer + audience + expiry; the rest is up to the
 * caller.
 */
@Service
public class AppleIdTokenVerifier {

    private static final String APPLE_JWKS_URL = "https://appleid.apple.com/auth/keys";
    private static final String EXPECTED_ISSUER = "https://appleid.apple.com";
    private static final long KEY_CACHE_TTL_SECONDS = 3600;

    private final RestTemplate http = new RestTemplate();
    private final ObjectMapper objectMapper = new ObjectMapper();
    private final ReentrantLock cacheLock = new ReentrantLock();
    private volatile Map<String, PublicKey> keyCache = Map.of();
    private volatile Instant keyCacheExpiresAt = Instant.EPOCH;
    private final Set<String> allowedAudiences;

    public AppleIdTokenVerifier(
            @Value("${app.apple.bundle-ids:jashanveer.Forma,jashanveer.forma-ios}") String bundleIds
    ) {
        this.allowedAudiences = Set.copyOf(
                Arrays.stream(bundleIds.split(","))
                        .map(String::trim)
                        .filter(s -> !s.isEmpty())
                        .toList()
        );
    }

    /**
     * Verifies the JWS, returns the decoded subject + email. Throws
     * `IllegalArgumentException` for any verification failure (bad
     * signature, expired token, wrong issuer/audience, missing kid,
     * etc.) — callers should map this to HTTP 401.
     */
    public AppleIdToken verify(String identityToken) {
        String[] parts = identityToken.split("\\.");
        if (parts.length != 3) {
            throw new IllegalArgumentException("Malformed Apple identity token");
        }

        String kid = readKidFromHeader(parts[0]);
        PublicKey publicKey = fetchKey(kid);

        Claims claims;
        try {
            claims = Jwts.parserBuilder()
                    .setSigningKey(publicKey)
                    .requireIssuer(EXPECTED_ISSUER)
                    .build()
                    .parseClaimsJws(identityToken)
                    .getBody();
        } catch (Exception e) {
            throw new IllegalArgumentException("Invalid Apple identity token: " + e.getMessage(), e);
        }

        if (!audienceMatches(claims.get("aud"))) {
            throw new IllegalArgumentException("Apple identity token audience mismatch");
        }

        String sub = claims.getSubject();
        if (sub == null || sub.isBlank()) {
            throw new IllegalArgumentException("Apple identity token missing sub");
        }

        String email = (String) claims.get("email");
        boolean emailVerified = parseBoolClaim(claims.get("email_verified"));
        return new AppleIdToken(sub, email, emailVerified);
    }

    private String readKidFromHeader(String headerSegment) {
        try {
            byte[] decoded = Base64.getUrlDecoder().decode(headerSegment);
            JsonNode header = objectMapper.readTree(decoded);
            String kid = header.path("kid").asText();
            if (kid.isEmpty()) {
                throw new IllegalArgumentException("Apple identity token missing kid");
            }
            return kid;
        } catch (Exception e) {
            throw new IllegalArgumentException("Bad Apple token header", e);
        }
    }

    private boolean audienceMatches(Object audClaim) {
        if (audClaim instanceof String s) {
            return allowedAudiences.contains(s);
        }
        if (audClaim instanceof List<?> list) {
            return list.stream().anyMatch(allowedAudiences::contains);
        }
        return false;
    }

    private boolean parseBoolClaim(Object value) {
        if (value instanceof Boolean b) return b;
        if (value instanceof String s) return "true".equalsIgnoreCase(s);
        return false;
    }

    private PublicKey fetchKey(String kid) {
        Map<String, PublicKey> snapshot = keyCache;
        if (Instant.now().isBefore(keyCacheExpiresAt) && snapshot.containsKey(kid)) {
            return snapshot.get(kid);
        }
        cacheLock.lock();
        try {
            if (Instant.now().isAfter(keyCacheExpiresAt) || !keyCache.containsKey(kid)) {
                refreshKeys();
            }
            PublicKey key = keyCache.get(kid);
            if (key == null) {
                throw new IllegalStateException("Apple key " + kid + " not found in JWKS");
            }
            return key;
        } finally {
            cacheLock.unlock();
        }
    }

    private void refreshKeys() {
        String body = http.getForObject(APPLE_JWKS_URL, String.class);
        if (body == null) {
            throw new IllegalStateException("Apple JWKS response was empty");
        }
        try {
            JsonNode root = objectMapper.readTree(body);
            JsonNode keys = root.path("keys");
            Map<String, PublicKey> next = new HashMap<>();
            KeyFactory factory = KeyFactory.getInstance("RSA");
            for (JsonNode k : keys) {
                if (!"RSA".equals(k.path("kty").asText())) continue;
                String kid = k.path("kid").asText();
                String n = k.path("n").asText();
                String e = k.path("e").asText();
                if (kid.isEmpty() || n.isEmpty() || e.isEmpty()) continue;
                BigInteger modulus = new BigInteger(1, Base64.getUrlDecoder().decode(n));
                BigInteger exponent = new BigInteger(1, Base64.getUrlDecoder().decode(e));
                PublicKey pk = factory.generatePublic(new RSAPublicKeySpec(modulus, exponent));
                next.put(kid, pk);
            }
            keyCache = Map.copyOf(next);
            keyCacheExpiresAt = Instant.now().plusSeconds(KEY_CACHE_TTL_SECONDS);
        } catch (Exception ex) {
            throw new IllegalStateException("Failed to parse Apple JWKS", ex);
        }
    }

    public record AppleIdToken(String sub, String email, boolean emailVerified) {}
}
