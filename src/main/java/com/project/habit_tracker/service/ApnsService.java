package com.project.habit_tracker.service;

import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.SignatureAlgorithm;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyFactory;
import java.security.PrivateKey;
import java.security.spec.PKCS8EncodedKeySpec;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.Date;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Sends push notifications to macOS and iOS devices via APNs token-based auth.
 *
 * <h2>Configuration</h2>
 *
 * Two ways to provide the .p8 private key — pick whichever fits your deploy:
 *   - {@code apns.private-key-path} → absolute filesystem path to the .p8 file
 *   - {@code apns.private-key} → full PEM contents inline (newlines escaped to \n)
 *
 * Plus the rest:
 *   apns.team-id          10-char Apple Developer Team ID
 *   apns.key-id           10-char Key ID shown next to the .p8 in the portal
 *   apns.bundle-id-macos  e.g. jashanveer.Forma
 *   apns.bundle-id-ios    e.g. jashanveer.forma-ios
 *   apns.sandbox          true for development builds, false for App Store
 *
 * If any of team-id / key-id is blank or the key fails to load, the service
 * logs a warning and silently skips sends — the rest of the app keeps working
 * via SSE.
 *
 * <h2>JWT caching</h2>
 *
 * Apple lets the auth token live up to 60 minutes; rebuilding it on every
 * push is wasteful. We refresh whenever the cached token is older than
 * 50 minutes, leaving a 10-minute safety margin.
 */
@Service
public class ApnsService {

    private static final Logger log = LoggerFactory.getLogger(ApnsService.class);
    private static final Duration JWT_TTL = Duration.ofMinutes(50);

    @Value("${apns.team-id:}")
    private String teamId;

    @Value("${apns.key-id:}")
    private String keyId;

    /**
     * Inline PEM contents — escape newlines as \n if you embed in a single
     * env-var line. Optional if {@code apns.private-key-path} is set.
     */
    @Value("${apns.private-key:}")
    private String privateKeyPem;

    /**
     * Absolute filesystem path to the .p8. Convenient for local dev when
     * Apple's download lives in {@code ~/Downloads/AuthKey_*.p8}. Takes
     * precedence over {@code apns.private-key} when both are set.
     */
    @Value("${apns.private-key-path:}")
    private String privateKeyPath;

    @Value("${apns.bundle-id-macos:jashanveer.Forma}")
    private String bundleIdMacos;

    @Value("${apns.bundle-id-ios:jashanveer.forma-ios}")
    private String bundleIdIos;

    @Value("${apns.sandbox:true}")
    private boolean sandbox;

    private final HttpClient httpClient = HttpClient.newBuilder()
            .version(HttpClient.Version.HTTP_2)
            .connectTimeout(Duration.ofSeconds(8))
            .build();

    private PrivateKey signingKey;
    private final AtomicReference<CachedJwt> jwtCache = new AtomicReference<>();

    @PostConstruct
    void loadKey() {
        if (teamId.isBlank() || keyId.isBlank()) {
            log.info("APNs disabled — apns.team-id or apns.key-id is empty.");
            return;
        }
        try {
            String pem;
            if (privateKeyPath != null && !privateKeyPath.isBlank()) {
                pem = Files.readString(Path.of(privateKeyPath));
                log.info("APNs private key loaded from {}", privateKeyPath);
            } else if (privateKeyPem != null && !privateKeyPem.isBlank()) {
                // Allow multi-line .env values that escape newlines as `\n`.
                pem = privateKeyPem.replace("\\n", "\n");
                log.info("APNs private key loaded from inline property");
            } else {
                log.warn("APNs disabled — neither apns.private-key-path nor apns.private-key is set.");
                return;
            }
            this.signingKey = parsePkcs8Ec(pem);
        } catch (Exception e) {
            log.error("APNs disabled — failed to load private key: {}", e.getMessage());
            this.signingKey = null;
        }
    }

    public boolean isConfigured() {
        return signingKey != null && !teamId.isBlank() && !keyId.isBlank();
    }

    /**
     * Sends a nudge push to a device. {@code platform} comes from the
     * {@code device_tokens.platform} column ("macos" or "ios") and selects
     * which bundle-id to use as the {@code apns-topic} header — Apple
     * rejects pushes whose topic doesn't match the receiver app's bundle.
     */
    public void sendNudge(String deviceToken, String platform, String senderName, String message) {
        if (!isConfigured()) {
            log.debug("APNs not configured — skipping push for token {}…", shortToken(deviceToken));
            return;
        }
        String topic = topicFor(platform);
        try {
            String jwt = currentJwt();
            String host = sandbox ? "api.sandbox.push.apple.com" : "api.push.apple.com";
            String url = "https://" + host + "/3/device/" + deviceToken;
            String payload = buildPayload(senderName, message);

            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(url))
                    .header("authorization", "bearer " + jwt)
                    .header("apns-topic", topic)
                    .header("apns-push-type", "alert")
                    .header("apns-expiration", "0")
                    .header("apns-priority", "10")
                    .header("content-type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(payload))
                    .build();

            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            int status = response.statusCode();
            if (status == 200) {
                log.info("APNs push sent to {}… ({} via {})", shortToken(deviceToken), platform, topic);
            } else if (status == 410 || status == 400) {
                // 410 BadDeviceToken / 400 with "BadDeviceToken" reason → the token
                // was revoked. Caller may want to evict it from device_tokens.
                log.warn("APNs token rejected ({}) for {}…: {}", status, shortToken(deviceToken), response.body());
            } else {
                log.warn("APNs push failed ({}) for {}…: {}", status, shortToken(deviceToken), response.body());
            }
        } catch (Exception e) {
            log.error("APNs push error for {}…: {}", shortToken(deviceToken), e.getMessage());
        }
    }

    private String topicFor(String platform) {
        if (platform == null) return bundleIdIos;
        return switch (platform.toLowerCase(Locale.ROOT)) {
            case "macos", "mac", "osx" -> bundleIdMacos;
            default -> bundleIdIos;
        };
    }

    private String currentJwt() {
        CachedJwt cached = jwtCache.get();
        Instant now = Instant.now();
        if (cached != null && cached.issuedAt.plus(JWT_TTL).isAfter(now)) {
            return cached.token;
        }
        synchronized (this) {
            cached = jwtCache.get();
            if (cached != null && cached.issuedAt.plus(JWT_TTL).isAfter(now)) {
                return cached.token;
            }
            String fresh = buildJwt(now);
            jwtCache.set(new CachedJwt(fresh, now));
            return fresh;
        }
    }

    private String buildJwt(Instant issuedAt) {
        return Jwts.builder()
                .setHeaderParam("kid", keyId)
                .setIssuer(teamId)
                .setIssuedAt(Date.from(issuedAt))
                .signWith(signingKey, SignatureAlgorithm.ES256)
                .compact();
    }

    private static PrivateKey parsePkcs8Ec(String pem) throws Exception {
        String cleaned = pem
                .replace("-----BEGIN PRIVATE KEY-----", "")
                .replace("-----END PRIVATE KEY-----", "")
                .replaceAll("[\\s\\r\\n]", "");
        byte[] keyBytes = Base64.getDecoder().decode(cleaned);
        PKCS8EncodedKeySpec spec = new PKCS8EncodedKeySpec(keyBytes);
        return KeyFactory.getInstance("EC").generatePrivate(spec);
    }

    private static String buildPayload(String senderName, String message) {
        String title = escapeJson("Nudge from " + senderName);
        String body  = escapeJson(message);
        return "{\"aps\":{\"alert\":{\"title\":\"" + title + "\",\"body\":\"" + body
                + "\"},\"sound\":\"default\",\"badge\":1}}";
    }

    private static String escapeJson(String value) {
        return value
                .replace("\\", "\\\\")
                .replace("\"", "\\\"")
                .replace("\n", "\\n")
                .replace("\r", "");
    }

    private static String shortToken(String token) {
        return token != null && token.length() > 8 ? token.substring(0, 8) : token;
    }

    private record CachedJwt(String token, Instant issuedAt) {}
}
