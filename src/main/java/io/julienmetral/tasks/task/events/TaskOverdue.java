package io.julienmetral.tasks.task.events;

import java.time.Instant;
import java.util.UUID;

/** Published by the reminder job, once per task, due date and assignee, when an open task passes its due date. */
public record TaskOverdue(
        UUID taskId,
        String reference,
        String title,
        Instant dueAt,
        UUID assigneeId
) {
}
