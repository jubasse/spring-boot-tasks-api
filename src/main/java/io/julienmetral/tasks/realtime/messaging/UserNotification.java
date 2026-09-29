package io.julienmetral.tasks.realtime.messaging;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

/**
 * A task notification broadcast to every instance, which hands it to the recipient's open streams. {@code type},
 * {@code timestamp} and {@code data} are the webhook payload's.
 */
public record UserNotification(
        UUID recipientId,
        String type,
        Instant timestamp,
        Map<String, Object> data
) {
}
