package io.julienmetral.tasks.support;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.security.crypto.password.PasswordEncoder;

/**
 * Password hashes for users that tests store directly, computed once per raw password and JVM.
 *
 * <p>Argon2id is slow on purpose (19 MiB and two passes per hash), and the integration tests store
 * hundreds of users: hashing each one again cost time for nothing. One hash serves every user with
 * the same password, since it carries its own salt and parameters: logins still verify a real
 * Argon2id hash made by the application's encoder. Every context configures the same encoder, so a
 * hash cached by one context verifies in another.
 *
 * <p>Warning: not for tests of hashing itself (a fresh salt per hash, the upgrade of legacy BCrypt
 * hashes): they need the encoder's own output.
 */
public final class TestPasswords {

    private static final Map<String, String> HASHES = new ConcurrentHashMap<>();

    private TestPasswords() {}

    /**
     * The hash of this password, made by the context's {@code PasswordEncoder} bean the first time.
     */
    public static String hash(PasswordEncoder passwordEncoder, String rawPassword) {
        return HASHES.computeIfAbsent(rawPassword, passwordEncoder::encode);
    }
}
