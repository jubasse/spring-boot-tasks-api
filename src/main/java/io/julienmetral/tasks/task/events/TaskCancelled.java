package io.julienmetral.tasks.task.events;

import java.util.UUID;

/**
 * Published when a task is cancelled, through {@code /cancel} (with a reason) or a status change to CANCELLED
 * (without one). {@code actorId} is who made the change.
 */
public record TaskCancelled(
        UUID taskId,
        String reference,
        String title,
        UUID assigneeId,
        String reason,
        UUID actorId
) {
}
