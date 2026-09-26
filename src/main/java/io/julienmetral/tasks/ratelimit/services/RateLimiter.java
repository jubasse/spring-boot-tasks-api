package io.julienmetral.tasks.ratelimit.services;

import io.julienmetral.tasks.config.RateLimitProperties;
import io.julienmetral.tasks.config.RateLimitProperties.Limit;
import io.julienmetral.tasks.ratelimit.exceptions.RateLimitExceededException;
import io.julienmetral.tasks.ratelimit.repositories.RateLimitQueries;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Locale;
import java.util.UUID;

/**
 * Fixed-window limits on the endpoints anyone can call. Each check counts the request in a transaction of its own,
 * committed before the request is handled, so a request that then fails (a wrong password, for example) still counts.
 */
@Service
@RequiredArgsConstructor
public class RateLimiter {

    private final RateLimitQueries queries;
    private final RateLimitProperties properties;
    private final Clock clock;

    /** @throws RateLimitExceededException when either the address or the email is over its login limit */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void login(String clientAddress, String email) {
        consume("login:ip:" + clientAddress, properties.loginPerIp());
        consume("login:email:" + normalized(email), properties.loginPerEmail());
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void signUp(String clientAddress) {
        consume("sign-up:ip:" + clientAddress, properties.signUpPerIp());
    }

    // Counted per email whether or not an account uses it, so that a 429 reveals nothing about the account
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void passwordResetRequest(String clientAddress, String email) {
        consume("password-reset:ip:" + clientAddress, properties.passwordResetPerIp());
        consume("password-reset:email:" + normalized(email), properties.passwordResetPerEmail());
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void passwordResetConfirm(String clientAddress) {
        consume("password-reset:ip:" + clientAddress, properties.passwordResetPerIp());
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void verificationResend(UUID userId) {
        consume("verification-resend:user:" + userId, properties.verificationResendPerUser());
    }

    private void consume(String key, Limit limit) {
        if (!properties.enabled()) {
            return;
        }

        Instant now = clock.instant();
        long windowMillis = limit.window().toMillis();
        Instant windowStart = Instant.ofEpochMilli(Math.floorDiv(now.toEpochMilli(), windowMillis) * windowMillis);

        if (queries.increment(key, windowStart) > limit.requests()) {
            throw new RateLimitExceededException(Duration.between(now, windowStart.plus(limit.window())));
        }
    }

    private static String normalized(String email) {
        return email == null ? "" : email.trim().toLowerCase(Locale.ROOT);
    }
}
