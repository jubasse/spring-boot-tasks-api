package io.julienmetral.tasks.ratelimit.exceptions;

import lombok.Getter;

import java.time.Duration;

@Getter
public class RateLimitExceededException extends RuntimeException {

    private final Duration retryAfter;

    public RateLimitExceededException(Duration retryAfter) {
        super("Too many requests, try again in " + Math.max(1, retryAfter.toSeconds()) + " seconds");
        this.retryAfter = retryAfter;
    }
}
