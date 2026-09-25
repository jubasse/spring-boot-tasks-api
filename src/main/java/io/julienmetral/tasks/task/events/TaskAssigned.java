package io.julienmetral.tasks.task.events;

import java.util.UUID;

/** Published when a task gets a new assignee, on creation or reassignment. {@code actorId} is who made the change. */
public record TaskAssigned(
        UUID taskId,
        String reference,
        String title,
        UUID assigneeId,
        UUID actorId
) {
}
