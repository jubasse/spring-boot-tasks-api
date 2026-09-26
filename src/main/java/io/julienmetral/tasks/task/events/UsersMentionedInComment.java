package io.julienmetral.tasks.task.events;

import java.util.Set;
import java.util.UUID;

/** Published when a comment is posted or edited, with only the users it mentions for the first time. */
public record UsersMentionedInComment(
        UUID taskId,
        String reference,
        String title,
        UUID commentId,
        String body,
        UUID authorId,
        Set<UUID> mentionedUserIds
) {
}
