package io.julienmetral.tasks.task.events;

import java.util.UUID;

/** Published when a task is (soft-)deleted; assigneeId is who it was assigned to, if anyone. {@code actorId} is who made the change. */
public record TaskDeleted(
        UUID taskId,
        String reference,
        String title,
        UUID assigneeId,
        UUID actorId
) {
}
