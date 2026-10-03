package io.julienmetral.tasks.notification.events;

import io.julienmetral.tasks.notification.entities.WebhookEvent;

import java.util.Map;
import java.util.UUID;

/**
 * One task notification for one recipient, published inside the transaction of the change once the rules every
 * channel shares have passed (see {@code TaskNotificationPublisher}). Webhooks and notification streams listen to it.
 *
 * @param data the {@code data} object of the payload: {@code task}, {@code actor} (null for a reminder), and the
 *             event's details ({@code comment}, {@code reason}, {@code dueAt})
 */
public record TaskNotificationCreated(
        WebhookEvent event,
        UUID recipientId,
        Map<String, Object> data
) {
}
