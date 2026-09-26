package io.julienmetral.tasks.ratelimit.exceptions;

import lombok.Getter;

import java.time.Duration;

@Getter
public class RateLimitExceededException extends RuntimeException {

    private final Duration retryAfter;

    public RateLimitExceededException(Duration retryAfter) {
        super(message(retryAfterSeconds(retryAfter)));
        this.retryAfter = retryAfter;
    }

    /** Whole seconds, rounded up: a client that waits a truncated value is refused again. */
    public long retryAfterSeconds() {
        return retryAfterSeconds(retryAfter);
    }

    private static long retryAfterSeconds(Duration retryAfter) {
        return Math.max(1, (retryAfter.toMillis() + 999) / 1000);
    }

    private static String message(long seconds) {
        return "Too many requests, try again in " + seconds + (seconds == 1 ? " second" : " seconds");
    }
}
