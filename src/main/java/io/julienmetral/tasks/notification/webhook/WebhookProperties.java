package io.julienmetral.tasks.notification.webhook;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.validation.annotation.Validated;

import java.time.Duration;

/**
 * @param encryptionKey          Base64 AES key of at least 32 bytes that encrypts the signing secrets in the
 *                               database ({@code WEBHOOK_ENCRYPTION_KEY}); {@link WebhookSecrets} checks it at
 *                               startup. Warning: changing it makes every stored secret unreadable.
 * @param requireHttps           refuse URLs that are not HTTPS on port 443; only a development machine turns it off,
 *                               to reach a local receiver over HTTP
 * @param maxPerUser             endpoints a user can declare
 * @param previousSecretValidity how long a rotated secret keeps signing deliveries, so receivers can switch
 * @param concurrentDeliveries   deliveries attempted at the same time by one instance
 * @param deliveryLease          how long an attempt may run before its delivery counts as due again; above the
 *                               HTTP timeouts, so a slow attempt is not sent twice
 * @param retryEnabled           whether this instance sends the due retries ({@link WebhookRetryJob})
 * @param retryPollInterval      how often the due retries are looked for
 * @param retryBatchSize         retries queued per poll
 * @param disableAfter           how long an endpoint can fail every attempt before it is disabled and its owner
 *                               emailed
 * @param deliveryRetention      how long deliveries stay listed; they hold names and comment excerpts
 * @param purgeEnabled           whether this instance deletes the deliveries older than the retention
 * @param purgeCron              when the old deliveries are deleted (Spring cron, six fields)
 * @param circuitBreaker         the circuit breaker of each receiving host
 * @param concurrentCallsPerHost deliveries one instance sends to the same host at once, so a slow receiver cannot
 *                               hold every delivery consumer
 */
@Validated
@ConfigurationProperties(prefix = "webhooks")
public record WebhookProperties(
        String encryptionKey,
        @DefaultValue("true") boolean requireHttps,
        @DefaultValue("5") @Min(1) int maxPerUser,
        @DefaultValue("24h") @NotNull Duration previousSecretValidity,
        @DefaultValue("4") @Min(1) int concurrentDeliveries,
        @DefaultValue("5m") @NotNull Duration deliveryLease,
        @DefaultValue("true") boolean retryEnabled,
        @DefaultValue("30s") @NotNull Duration retryPollInterval,
        @DefaultValue("100") @Min(1) int retryBatchSize,
        @DefaultValue("3d") @NotNull Duration disableAfter,
        @DefaultValue("30d") @NotNull Duration deliveryRetention,
        @DefaultValue("true") boolean purgeEnabled,
        @DefaultValue("0 45 3 * * *") String purgeCron,
        @DefaultValue @Valid @NotNull HostCircuitBreaker circuitBreaker,
        @DefaultValue("2") @Min(1) int concurrentCallsPerHost
) {

    /**
     * @param failureRateThreshold percentage of failed calls, within the window, that opens the breaker
     * @param minimumCalls         calls within the window before the rate counts, so one failure of a quiet host
     *                             does not open it
     * @param window               how far back calls count
     * @param openDuration         how long an open breaker refuses calls before letting one through to test the host
     */
    public record HostCircuitBreaker(
            @DefaultValue("50") @Min(1) @Max(100) int failureRateThreshold,
            @DefaultValue("5") @Min(1) int minimumCalls,
            @DefaultValue("60s") @NotNull Duration window,
            @DefaultValue("1m") @NotNull Duration openDuration
    ) {
    }
}
