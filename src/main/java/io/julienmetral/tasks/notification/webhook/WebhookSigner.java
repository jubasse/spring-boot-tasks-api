package io.julienmetral.tasks.notification.webhook;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.time.Instant;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * The headers of the Standard Webhooks scheme: {@code webhook-id}, {@code webhook-timestamp} and
 * {@code webhook-signature}, an HMAC-SHA256 of {@code id.timestamp.body} per secret, so a receiver in the middle of a
 * rotation accepts either.
 */
public class WebhookSigner {

    private static final String SECRET_PREFIX = "whsec_";

    public Map<String, String> headers(String messageId, Instant timestamp, byte[] body, List<String> secrets) {
        String seconds = String.valueOf(timestamp.getEpochSecond());
        byte[] signed = concat((messageId + "." + seconds + ".").getBytes(StandardCharsets.UTF_8), body);

        Map<String, String> headers = new LinkedHashMap<>();
        headers.put("webhook-id", messageId);
        headers.put("webhook-timestamp", seconds);
        headers.put("webhook-signature", secrets.stream()
                .map(secret -> "v1," + Base64.getEncoder().encodeToString(hmac(secret, signed)))
                .collect(Collectors.joining(" ")));

        return headers;
    }

    private static byte[] hmac(String secret, byte[] content) {
        byte[] key = Base64.getDecoder().decode(secret.substring(SECRET_PREFIX.length()));

        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(key, "HmacSHA256"));

            return mac.doFinal(content);
        } catch (GeneralSecurityException unavailable) {
            throw new IllegalStateException("HmacSHA256 is not available", unavailable);
        }
    }

    private static byte[] concat(byte[] first, byte[] second) {
        byte[] joined = new byte[first.length + second.length];
        System.arraycopy(first, 0, joined, 0, first.length);
        System.arraycopy(second, 0, joined, first.length, second.length);

        return joined;
    }
}
