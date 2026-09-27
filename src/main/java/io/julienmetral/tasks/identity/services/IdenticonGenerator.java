package io.julienmetral.tasks.identity.services;

import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.UUID;

/**
 * A geometric figure (head and shoulders) on a coloured background, derived from the profile id alone: the same id
 * always gives the same image, and the image reveals nothing about the account.
 */
@Component
public class IdenticonGenerator {

    public String svg(UUID profileId) {
        byte[] hash = sha256(profileId.toString());
        int hue = ((hash[0] & 0xFF) << 8 | (hash[1] & 0xFF)) % 360;
        int saturation = 45 + (hash[2] & 0xFF) % 25;

        return """
                <svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 128 128" width="128" height="128" role="img">\
                <rect width="128" height="128" fill="hsl(%d,%d%%,52%%)"/>\
                <circle cx="64" cy="50" r="22" fill="hsl(%d,%d%%,90%%)"/>\
                <path d="M24 118c0-26 18-40 40-40s40 14 40 40z" fill="hsl(%d,%d%%,90%%)"/>\
                </svg>""".formatted(hue, saturation, hue, saturation, hue, saturation);
    }

    private static byte[] sha256(String value) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8));
        } catch (NoSuchAlgorithmException exception) {
            // Every Java platform is required to support SHA-256
            throw new IllegalStateException(exception);
        }
    }
}
