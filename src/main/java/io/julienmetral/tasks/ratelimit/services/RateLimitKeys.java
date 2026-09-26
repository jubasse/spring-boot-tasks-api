package io.julienmetral.tasks.ratelimit.services;

import java.net.Inet6Address;
import java.net.InetAddress;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.Locale;
import java.util.UUID;

/**
 * Counter keys. Addresses and emails are stored as SHA-256 hashes: the key keeps a fixed length (a long email, or
 * one that lower-casing lengthens, overflowed the column and the request failed with 409), and the table holds no
 * address or email in clear.
 */
public final class RateLimitKeys {

    private static final int IPV6_PREFIX_BYTES = 8;

    private RateLimitKeys() {
    }

    public static String address(String endpoint, String clientAddress) {
        return endpoint + ":ip:" + sha256(client(clientAddress));
    }

    public static String email(String endpoint, String email) {
        return endpoint + ":email:" + sha256(email == null ? "" : email.trim().toLowerCase(Locale.ROOT));
    }

    public static String user(String endpoint, UUID userId) {
        return endpoint + ":user:" + userId;
    }

    // An IPv6 client usually holds a whole /64: counting each address apart would let it rotate through them
    private static String client(String clientAddress) {
        try {
            InetAddress address = InetAddress.ofLiteral(clientAddress);

            if (address instanceof Inet6Address) {
                return HexFormat.of().formatHex(Arrays.copyOf(address.getAddress(), IPV6_PREFIX_BYTES)) + "/64";
            }

            return address.getHostAddress();
        } catch (IllegalArgumentException notALiteral) {
            return clientAddress;
        }
    }

    private static String sha256(String value) {
        try {
            byte[] hash = MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8));

            return HexFormat.of().formatHex(hash);
        } catch (NoSuchAlgorithmException exception) {
            // Every Java platform is required to support SHA-256
            throw new IllegalStateException(exception);
        }
    }
}
