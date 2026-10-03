package io.julienmetral.tasks.realtime.messaging;

import java.time.Instant;
import java.util.UUID;

/**
 * A change to a task, sent to the clients in its room. It says what happened and who did it, not the new state:
 * clients read the task again through the API, which keeps authorization and presigned links in one place.
 *
 * @param eventId the row of the task's history, null for a deletion, which has none
 * @param type    a history event type ({@code COMMENT_ADDED}...), or {@code DELETED}
 */
public record TaskRoomEvent(
        UUID taskId,
        UUID eventId,
        String type,
        UUID actorId,
        Instant occurredAt
) {

    public static final String DELETED = "DELETED";
}
