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
 */
@Validated
@ConfigurationProperties(prefix = "webhooks")
public record WebhookProperties(
        String encryptionKey,
        @DefaultValue("true") boolean requireHttps,
        @DefaultValue("5") @Min(1) int maxPerUser,
        @DefaultValue("24h") @NotNull Duration previousSecretValidity
) {
}
