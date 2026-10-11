package io.julienmetral.tasks.notification.dtos;

import io.swagger.v3.oas.annotations.media.Schema;
import java.time.Instant;

public record WebhookSecretDto(
        @Schema(
                        description = "The new signing secret; it is never shown again",
                        example = "whsec_MfKQ9r8GKYqrTwjUPD8ILPZIo2LaLaSw")
                String secret,
        @Schema(
                        description =
                                "Until then, deliveries carry a signature with the previous secret too; null when the "
                                        + "previous secret could no longer be read, after a change of the server's encryption key")
                Instant previousSecretExpiresAt) {}
