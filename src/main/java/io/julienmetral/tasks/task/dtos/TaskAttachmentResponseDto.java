package io.julienmetral.tasks.task.dtos;

import io.julienmetral.tasks.identity.dtos.UserPreviewResponseDto;
import io.julienmetral.tasks.media.model.Media;
import io.julienmetral.tasks.media.services.MediaUrls;
import io.julienmetral.tasks.task.entities.TaskAttachment;

import java.time.Instant;
import java.util.UUID;

public record TaskAttachmentResponseDto(
        UUID id,
        String filename,
        String contentType,
        long sizeBytes,
        UserPreviewResponseDto uploadedBy,
        UUID commentId,
        Instant createdAt,
        String downloadUrl
) {

    public TaskAttachmentResponseDto(TaskAttachment attachment, MediaUrls mediaUrls) {
        this(attachment, attachment.getMedia(), mediaUrls);
    }

    private TaskAttachmentResponseDto(TaskAttachment attachment, Media media, MediaUrls mediaUrls) {
        this(
                attachment.getId(),
                media.getOriginalFilename(),
                media.getContentType(),
                media.getSizeBytes(),
                UserPreviewResponseDto.of(media.getUploadedBy(), mediaUrls),
                attachment.getComment() == null ? null : attachment.getComment().getId(),
                attachment.getCreatedAt(),
                mediaUrls.of(media)
        );
    }
}
