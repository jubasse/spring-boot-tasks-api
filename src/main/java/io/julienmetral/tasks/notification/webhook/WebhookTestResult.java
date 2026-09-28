package io.julienmetral.tasks.notification.webhook;

/**
 * @param delivered      whether the receiver answered with a 2xx status
 * @param statusCode     the status of the answer, null when none came back
 * @param error          why no answer came back ({@code Timeout}, {@code DestinationNotAllowed}, ...)
 * @param durationMillis how long the call took
 */
public record WebhookTestResult(boolean delivered, Integer statusCode, String error, long durationMillis) {
}
