package io.julienmetral.tasks.task.events;

import java.util.UUID;

/** Published when a task is reassigned away from its previous assignee. {@code actorId} is who made the change. */
public record TaskUnassigned(
        UUID taskId,
        String reference,
        String title,
        UUID previousAssigneeId,
        UUID actorId
) {
}
