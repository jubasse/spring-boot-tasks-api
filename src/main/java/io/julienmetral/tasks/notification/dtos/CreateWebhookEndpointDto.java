package io.julienmetral.tasks.notification.dtos;

import io.julienmetral.tasks.notification.entities.WebhookEvent;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.util.Set;

public record CreateWebhookEndpointDto(
        @Schema(example = "https://hooks.example.com/tasks")
        @NotBlank @Size(max = 2048) String url,

        @NotEmpty Set<@NotNull WebhookEvent> events
) {
}
