package io.julienmetral.tasks.notification.webhook;

import org.jspecify.annotations.Nullable;

/**
 * @param delivered whether the receiver answered with a 2xx status
 * @param statusCode the status of the answer, null when none came back
 * @param error why no answer came back ({@code Timeout}, {@code DestinationNotAllowed}, ...), or
 *     {@code SecretUnreadable} when nothing was sent
 * @param durationMillis how long the call took
 */
public record WebhookTestResult(
        boolean delivered,
        @Nullable Integer statusCode,
        @Nullable String error,
        long durationMillis) {}
