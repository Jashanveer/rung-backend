package com.project.habit_tracker.security;

import jakarta.persistence.AttributeConverter;
import jakarta.persistence.Converter;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import javax.crypto.Cipher;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.Base64;

/**
 * JPA AttributeConverter that transparently encrypts/decrypts String columns
 * using AES-256-GCM. The key is read from the APP_ENCRYPTION_KEY env var (32 bytes,
 * Base64-encoded). If the env var is blank, values pass through unencrypted so the
 * app still starts in dev without configuration.
 *
 * Stored format: Base64( 12-byte-IV || 16-byte-tag || ciphertext )
 *
 * Soft-migration: if decryption fails (e.g., existing plaintext rows), the raw
 * value is returned as-is. Each subsequent save will re-persist the encrypted form.
 */
@Component
@Converter
public class EncryptedStringConverter implements AttributeConverter<String, String> {

    private static final String ALGORITHM = "AES/GCM/NoPadding";
    private static final int IV_LENGTH   = 12;
    private static final int TAG_BITS    = 128;
    private static final String ENCRYPTED_PREFIX = "ENC:";

    private final SecretKey secretKey;
    private final SecureRandom rng = new SecureRandom();

    public EncryptedStringConverter(@Value("${app.encryption.key:}") String base64Key) {
        if (base64Key == null || base64Key.isBlank()) {
            this.secretKey = null;
        } else {
            byte[] keyBytes = Base64.getDecoder().decode(base64Key);
            if (keyBytes.length != 32) {
                throw new IllegalStateException(
                    "app.encryption.key must be a 32-byte (256-bit) Base64-encoded string"
                );
            }
            this.secretKey = new SecretKeySpec(keyBytes, "AES");
        }
    }

    /**
     * Returns an HMAC-SHA256 hex digest of {@code value}, used as a stable lookup key
     * for encrypted columns (e.g. email). When encryption is disabled the raw value is
     * returned unchanged so plain-text column lookups continue to work.
     */
    public String hash(String value) {
        if (value == null) return null;
        if (secretKey == null) return value;
        try {
            javax.crypto.Mac mac = javax.crypto.Mac.getInstance("HmacSHA256");
            mac.init(secretKey);
            byte[] digest = mac.doFinal(value.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder(64);
            for (byte b : digest) sb.append(String.format("%02x", b));
            return sb.toString();
        } catch (Exception e) {
            throw new IllegalStateException("HMAC failed", e);
        }
    }

    @Override
    public String convertToDatabaseColumn(String plaintext) {
        if (plaintext == null || secretKey == null) return plaintext;
        try {
            byte[] iv = new byte[IV_LENGTH];
            rng.nextBytes(iv);

            Cipher cipher = Cipher.getInstance(ALGORITHM);
            cipher.init(Cipher.ENCRYPT_MODE, secretKey, new GCMParameterSpec(TAG_BITS, iv));
            byte[] ciphertext = cipher.doFinal(plaintext.getBytes(StandardCharsets.UTF_8));

            ByteBuffer buf = ByteBuffer.allocate(IV_LENGTH + ciphertext.length);
            buf.put(iv);
            buf.put(ciphertext);
            return ENCRYPTED_PREFIX + Base64.getEncoder().encodeToString(buf.array());
        } catch (Exception e) {
            throw new IllegalStateException("Encryption failed", e);
        }
    }

    @Override
    public String convertToEntityAttribute(String dbData) {
        if (dbData == null || secretKey == null) return dbData;
        if (!dbData.startsWith(ENCRYPTED_PREFIX)) {
            // Soft migration: plaintext row written before encryption was enabled.
            // Return as-is; it will be re-encrypted on the next save.
            return dbData;
        }
        try {
            byte[] packed = Base64.getDecoder().decode(dbData.substring(ENCRYPTED_PREFIX.length()));
            ByteBuffer buf = ByteBuffer.wrap(packed);

            byte[] iv = new byte[IV_LENGTH];
            buf.get(iv);
            byte[] ciphertext = new byte[buf.remaining()];
            buf.get(ciphertext);

            Cipher cipher = Cipher.getInstance(ALGORITHM);
            cipher.init(Cipher.DECRYPT_MODE, secretKey, new GCMParameterSpec(TAG_BITS, iv));
            return new String(cipher.doFinal(ciphertext), StandardCharsets.UTF_8);
        } catch (Exception e) {
            // Decryption failed — treat as plaintext (handles malformed edge cases).
            return dbData;
        }
    }
}
