package io.julienmetral.tasks.task.events;

import java.util.Set;
import java.util.UUID;
import org.jspecify.annotations.Nullable;

/**
 * Published when a comment is posted. {@code mentionedUserIds} lets a listener skip the assignee
 * when a {@link UsersMentionedInComment} already reaches them.
 */
public record TaskCommentAdded(
        UUID taskId,
        String reference,
        String title,
        UUID commentId,
        String body,
        @Nullable UUID assigneeId,
        UUID authorId,
        Set<UUID> mentionedUserIds) {}
