package io.julienmetral.tasks.notification.webhook;

import org.springframework.security.crypto.encrypt.AesGcmBytesEncryptor;
import org.springframework.security.crypto.encrypt.BytesEncryptor;

import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.Base64;

/**
 * Creates the signing secrets of webhook endpoints, and encrypts them for the database with AES-256-GCM.
 * <p>
 * A secret follows the Standard Webhooks format: {@code whsec_} and the Base64 of 32 random bytes. Receivers
 * decode the part after the prefix to get the HMAC key.
 */
public class WebhookSecrets {

    private static final String PREFIX = "whsec_";

    // Names the key that encrypted a value, so a later key can be introduced without re-encrypting everything at once
    private static final String KEY_VERSION = "v1:";

    private static final int SECRET_BYTES = 32;

    private final BytesEncryptor encryptor;

    private final SecureRandom random = new SecureRandom();

    public WebhookSecrets(String base64Key) {
        byte[] key;

        try {
            key = base64Key == null ? new byte[0] : Base64.getDecoder().decode(base64Key);
        } catch (IllegalArgumentException notBase64) {
            key = new byte[0];
        }

        if (key.length < 32) {
            throw new IllegalStateException("webhooks.encryption-key (WEBHOOK_ENCRYPTION_KEY) must be the Base64 of "
                    + "at least 32 bytes; generate one with: openssl rand -base64 32");
        }

        this.encryptor = AesGcmBytesEncryptor.withSecretKey(new SecretKeySpec(key, 0, 32, "AES")).build();
    }

    public String generate() {
        byte[] secret = new byte[SECRET_BYTES];
        random.nextBytes(secret);

        return PREFIX + Base64.getEncoder().encodeToString(secret);
    }

    public String encrypt(String secret) {
        return KEY_VERSION + Base64.getEncoder().encodeToString(
                encryptor.encrypt(secret.getBytes(StandardCharsets.UTF_8)));
    }

    public String decrypt(String encrypted) {
        if (!encrypted.startsWith(KEY_VERSION)) {
            throw new IllegalStateException("Webhook secret encrypted with an unknown key version");
        }

        return new String(
                encryptor.decrypt(Base64.getDecoder().decode(encrypted.substring(KEY_VERSION.length()))),
                StandardCharsets.UTF_8
        );
    }
}
