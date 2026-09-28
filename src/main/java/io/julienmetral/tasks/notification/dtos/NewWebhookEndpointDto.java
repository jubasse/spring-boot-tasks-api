package io.julienmetral.tasks.notification.dtos;

import io.julienmetral.tasks.notification.entities.WebhookEndpoint;
import io.julienmetral.tasks.notification.entities.WebhookEvent;
import io.julienmetral.tasks.notification.entities.WebhookKind;
import io.julienmetral.tasks.notification.webhook.WebhookUrls;
import io.swagger.v3.oas.annotations.media.Schema;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/** A webhook just declared: the only response that carries its secret, besides a rotation. */
public record NewWebhookEndpointDto(
        UUID id,
        WebhookKind kind,

        @Schema(description = "Masked for a Slack webhook, whose URL is its credential")
        String url,
        List<WebhookEvent> events,
        boolean enabled,
        Instant createdAt,

        @Schema(description = "Signing secret in the Standard Webhooks format; it is never shown again. Absent for a "
                + "Slack webhook, whose messages are not signed",
                example = "whsec_MfKQ9r8GKYqrTwjUPD8ILPZIo2LaLaSw")
        String secret
) {

    public NewWebhookEndpointDto(WebhookEndpoint endpoint, String secret) {
        this(
                endpoint.getId(),
                endpoint.getKind(),
                WebhookUrls.shown(endpoint),
                endpoint.getEvents().stream().sorted().toList(),
                endpoint.isEnabled(),
                endpoint.getCreatedAt(),
                endpoint.getKind() == WebhookKind.SLACK ? null : secret
        );
    }
}
