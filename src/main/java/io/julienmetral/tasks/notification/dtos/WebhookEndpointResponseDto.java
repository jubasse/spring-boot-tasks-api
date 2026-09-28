package io.julienmetral.tasks.notification.dtos;

import io.julienmetral.tasks.notification.entities.WebhookDisabledReason;
import io.julienmetral.tasks.notification.entities.WebhookEndpoint;
import io.julienmetral.tasks.notification.entities.WebhookEvent;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public record WebhookEndpointResponseDto(
        UUID id,
        String url,
        List<WebhookEvent> events,
        boolean enabled,
        WebhookDisabledReason disabledReason,
        Instant disabledAt,
        Instant previousSecretExpiresAt,
        Instant createdAt,
        Instant updatedAt
) {

    public WebhookEndpointResponseDto(WebhookEndpoint endpoint) {
        this(
                endpoint.getId(),
                endpoint.getUrl(),
                endpoint.getEvents().stream().sorted().toList(),
                endpoint.isEnabled(),
                endpoint.getDisabledReason(),
                endpoint.getDisabledAt(),
                endpoint.getPreviousSecretExpiresAt(),
                endpoint.getCreatedAt(),
                endpoint.getUpdatedAt()
        );
    }
}
