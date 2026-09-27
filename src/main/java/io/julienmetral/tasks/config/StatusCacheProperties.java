package io.julienmetral.tasks.config;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import org.hibernate.validator.constraints.time.DurationMax;
import org.hibernate.validator.constraints.time.DurationMin;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

import java.time.Duration;

/**
 * @param ttl         how long an ACTIVE account status is reused before the account is read again: the longest a
 *                    disabled or deleted account keeps task access when the eviction of its status is missed, for
 *                    example on another instance. Bounded to one minute, far below the access token lifetime.
 * @param maximumSize how many account statuses are kept at most
 */
@Validated
@ConfigurationProperties(prefix = "identity.status-cache")
public record StatusCacheProperties(
        @NotNull @DurationMin(seconds = 1) @DurationMax(minutes = 1) Duration ttl,
        @Positive long maximumSize
) {
}
