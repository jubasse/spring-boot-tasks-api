package io.julienmetral.tasks.task.dtos;

import io.julienmetral.tasks.identity.dtos.UserPreviewResponseDto;
import io.julienmetral.tasks.media.services.MediaUrls;
import io.julienmetral.tasks.task.entities.TaskComment;

import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;

/** {@code body} keeps the {@code <@user-id>} mention tokens; {@code mentions} gives the users to render them. */
public record TaskCommentResponseDto(
        UUID id,
        String body,
        UserPreviewResponseDto author,
        List<UserPreviewResponseDto> mentions,
        List<TaskAttachmentResponseDto> attachments,
        Instant createdAt,
        Instant editedAt
) {

    public TaskCommentResponseDto(TaskComment comment, MediaUrls mediaUrls) {
        this(
                comment.getId(),
                comment.getBody(),
                UserPreviewResponseDto.of(comment.getAuthor(), mediaUrls),
                comment.getMentions()
                        .stream()
                        .map(user -> UserPreviewResponseDto.of(user, mediaUrls))
                        .sorted(Comparator.comparing(UserPreviewResponseDto::displayName))
                        .toList(),
                comment.getAttachments()
                        .stream()
                        .map(attachment -> new TaskAttachmentResponseDto(attachment, mediaUrls))
                        .toList(),
                comment.getCreatedAt(),
                comment.getEditedAt()
        );
    }
}
