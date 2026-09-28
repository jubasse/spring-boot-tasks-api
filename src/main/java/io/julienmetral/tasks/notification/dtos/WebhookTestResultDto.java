package io.julienmetral.tasks.notification.dtos;

import io.julienmetral.tasks.notification.webhook.WebhookTestResult;
import io.swagger.v3.oas.annotations.media.Schema;

public record WebhookTestResultDto(
        @Schema(description = "Whether the receiver answered with a 2xx status")
        boolean delivered,

        @Schema(description = "Status of the answer, absent when none came back")
        Integer statusCode,

        @Schema(description = "Why no answer came back", example = "Timeout")
        String error,

        long durationMillis
) {

    public WebhookTestResultDto(WebhookTestResult result) {
        this(result.delivered(), result.statusCode(), result.error(), result.durationMillis());
    }
}
