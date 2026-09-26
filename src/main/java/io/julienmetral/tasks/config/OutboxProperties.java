package io.julienmetral.tasks.config;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

import java.time.Duration;

/**
 * @param pollInterval   how often the relay looks for messages that could not be published right after commit
 * @param batchSize      how many messages one poll publishes at most
 * @param maxRetryDelay  upper bound of the delay between two attempts to publish the same message
 * @param confirmTimeout how long to wait for the broker to confirm a message
 * @param retention      how long published messages are kept before being deleted
 * @param purgeCron      when published messages older than the retention are deleted (Spring cron, six fields)
 */
@Validated
@ConfigurationProperties(prefix = "messaging.outbox")
public record OutboxProperties(
        @NotNull Duration pollInterval,
        @Positive int batchSize,
        @NotNull Duration maxRetryDelay,
        @NotNull Duration confirmTimeout,
        @NotNull Duration retention,
        @NotBlank String purgeCron
) {
}
