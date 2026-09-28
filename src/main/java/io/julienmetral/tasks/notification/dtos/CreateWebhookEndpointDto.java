package io.julienmetral.tasks.notification.dtos;

import io.julienmetral.tasks.notification.entities.WebhookEvent;
import io.julienmetral.tasks.notification.entities.WebhookKind;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.util.Set;

public record CreateWebhookEndpointDto(
        @Schema(description = "WEBHOOK (the default) for a signed JSON event, SLACK for a Slack incoming webhook "
                + "message, whose URL must start with https://hooks.slack.com/services/")
        WebhookKind kind,

        @Schema(example = "https://hooks.example.com/tasks")
        @NotBlank @Size(max = 2048) String url,

        @NotEmpty Set<@NotNull WebhookEvent> events
) {
}
