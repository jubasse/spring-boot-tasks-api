package io.julienmetral.tasks.identity.security;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.HexFormat;

/**
 * Random bearer tokens (refresh, email verification) that are stored only as a hash.
 * <p>
 * A token carries 256 bits of entropy, so a fast SHA-256 hash is enough: unlike a password it cannot be
 * brute-forced, and a leaked table of hashes yields no usable token.
 */
public final class OpaqueTokens {

    private static final SecureRandom RANDOM = new SecureRandom();

    private static final int TOKEN_BYTES = 32;

    private OpaqueTokens() {
    }

    /** A new URL-safe token (43 characters). */
    public static String generate() {
        byte[] bytes = new byte[TOKEN_BYTES];

        RANDOM.nextBytes(bytes);

        return Base64
                .getUrlEncoder()
                .withoutPadding()
                .encodeToString(bytes);
    }

    /** The hex-encoded SHA-256 hash stored in the database (64 characters). */
    public static String hash(String token) {
        try {
            byte[] digest = MessageDigest
                    .getInstance("SHA-256")
                    .digest(token.getBytes(StandardCharsets.UTF_8));

            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException exception) {
            // Every Java platform is required to support SHA-256
            throw new IllegalStateException(exception);
        }
    }
}
