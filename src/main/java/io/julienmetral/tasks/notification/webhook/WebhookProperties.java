package io.julienmetral.tasks.notification.webhook;

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
        @DefaultValue("100") @Min(1) int retryBatchSize
) {
}
