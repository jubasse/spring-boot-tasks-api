package io.julienmetral.tasks.task.events;

import io.julienmetral.tasks.task.entities.TaskEventType;

import java.time.Instant;
import java.util.UUID;

/** Published for every row written to a task's history, in the transaction that writes it. */
public record TaskEventRecorded(
        UUID taskId,
        UUID eventId,
        TaskEventType type,
        UUID actorId,
        Instant occurredAt
) {
}
