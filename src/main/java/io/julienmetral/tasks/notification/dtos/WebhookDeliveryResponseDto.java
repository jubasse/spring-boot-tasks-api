package io.julienmetral.tasks.notification.dtos;

import io.julienmetral.tasks.notification.entities.WebhookDelivery;
import io.julienmetral.tasks.notification.entities.WebhookDeliveryStatus;
import io.julienmetral.tasks.notification.entities.WebhookEvent;
import io.swagger.v3.oas.annotations.media.Schema;

import java.time.Instant;
import java.util.UUID;

public record WebhookDeliveryResponseDto(
        @Schema(description = "Also the webhook-id header of every attempt")
        UUID id,
        WebhookEvent event,
        WebhookDeliveryStatus status,
        int attempts,

        @Schema(description = "HTTP status of the last attempt, absent when no response came back")
        Integer lastStatusCode,

        @Schema(description = "Why the last attempt got no response or could not go on",
                example = "Timeout")
        String lastError,

        @Schema(description = "When the next attempt is due, while the delivery is pending")
        Instant nextAttemptAt,
        Instant lastAttemptAt,
        Instant deliveredAt,
        Instant createdAt
) {

    public WebhookDeliveryResponseDto(WebhookDelivery delivery) {
        this(
                delivery.getId(),
                delivery.getEvent(),
                delivery.getStatus(),
                delivery.getAttempts(),
                delivery.getLastStatusCode(),
                delivery.getLastError(),
                delivery.getStatus() == WebhookDeliveryStatus.PENDING ? delivery.getNextAttemptAt() : null,
                delivery.getLastAttemptAt(),
                delivery.getDeliveredAt(),
                delivery.getCreatedAt()
        );
    }
}
