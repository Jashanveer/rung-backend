package com.project.habit_tracker.service;

import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.SignatureAlgorithm;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.security.KeyFactory;
import java.security.PrivateKey;
import java.security.spec.PKCS8EncodedKeySpec;
import java.time.Duration;
import java.util.Base64;
import java.util.Date;

/**
 * Sends push notifications to macOS and iOS devices via Apple Push Notification service (APNs).
 *
 * Configuration required in application.properties (or env vars):
 *   apns.team-id=<10-char Apple Developer Team ID>
 *   apns.key-id=<10-char Key ID shown in Apple Developer portal>
 *   apns.private-key=<contents of the .p8 file, newlines replaced with \n>
 *   apns.bundle-id=<your app's bundle identifier>
 *   apns.sandbox=true   (true for development/TestFlight, false for App Store)
 *
 * If any of team-id, key-id, or private-key are blank the service logs a debug
 * message and silently skips sending — the app continues to work via SSE.
 */
@Service
public class ApnsService {

    private static final Logger log = LoggerFactory.getLogger(ApnsService.class);

    @Value("${apns.team-id:}")
    private String teamId;

    @Value("${apns.key-id:}")
    private String keyId;

    @Value("${apns.private-key:}")
    private String privateKeyPem;

    @Value("${apns.bundle-id:com.jashanveer.habit-tracker-macos}")
    private String bundleId;

    @Value("${apns.sandbox:true}")
    private boolean sandbox;

    private final HttpClient httpClient = HttpClient.newBuilder()
            .version(HttpClient.Version.HTTP_2)
            .connectTimeout(Duration.ofSeconds(8))
            .build();

    public boolean isConfigured() {
        return !teamId.isBlank() && !keyId.isBlank() && !privateKeyPem.isBlank();
    }

    /**
     * Sends a nudge push notification to a device.
     *
     * @param deviceToken hex APNs device token
     * @param senderName  display name of the mentor/mentee who sent the nudge
     * @param message     nudge message body
     */
    public void sendNudge(String deviceToken, String senderName, String message) {
        if (!isConfigured()) {
            log.debug("APNs not configured — skipping push for token {}…", shortToken(deviceToken));
            return;
        }

        try {
            String jwt = buildJwt();
            String host = sandbox ? "api.sandbox.push.apple.com" : "api.push.apple.com";
            String url = "https://" + host + "/3/device/" + deviceToken;

            String payload = buildPayload(senderName, message);

            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(url))
                    .header("authorization", "bearer " + jwt)
                    .header("apns-topic", bundleId)
                    .header("apns-push-type", "alert")
                    .header("apns-expiration", "0")
                    .header("apns-priority", "10")
                    .header("content-type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(payload))
                    .build();

            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());

            if (response.statusCode() == 200) {
                log.info("APNs push sent to {}…", shortToken(deviceToken));
            } else {
                log.warn("APNs push failed ({}) for {}…: {}", response.statusCode(), shortToken(deviceToken), response.body());
            }
        } catch (Exception e) {
            log.error("APNs push error for {}…: {}", shortToken(deviceToken), e.getMessage());
        }
    }

    private String buildPayload(String senderName, String message) {
        String title = escapeJson("Nudge from " + senderName);
        String body  = escapeJson(message);
        return "{\"aps\":{\"alert\":{\"title\":\"" + title + "\",\"body\":\"" + body + "\"},\"sound\":\"default\",\"badge\":1}}";
    }

    private String buildJwt() throws Exception {
        String cleaned = privateKeyPem
                .replace("-----BEGIN PRIVATE KEY-----", "")
                .replace("-----END PRIVATE KEY-----", "")
                .replaceAll("[\\s\\n\\r]", "");
        byte[] keyBytes = Base64.getDecoder().decode(cleaned);
        PKCS8EncodedKeySpec spec = new PKCS8EncodedKeySpec(keyBytes);
        PrivateKey privateKey = KeyFactory.getInstance("EC").generatePrivate(spec);

        return Jwts.builder()
                .setHeaderParam("kid", keyId)
                .setIssuer(teamId)
                .setIssuedAt(new Date())
                .signWith(privateKey, SignatureAlgorithm.ES256)
                .compact();
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
}
