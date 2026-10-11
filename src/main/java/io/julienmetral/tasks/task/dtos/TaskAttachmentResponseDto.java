package io.julienmetral.tasks.task.dtos;

import io.julienmetral.tasks.identity.dtos.UserProfileResponseDto;
import io.julienmetral.tasks.media.model.Media;
import io.julienmetral.tasks.media.services.MediaUrls;
import io.julienmetral.tasks.task.entities.TaskAttachment;
import java.time.Instant;
import java.util.UUID;
import org.jspecify.annotations.Nullable;

public record TaskAttachmentResponseDto(
        UUID id,
        String filename,
        String contentType,
        long sizeBytes,
        @Nullable UserProfileResponseDto uploadedBy,
        @Nullable UUID commentId,
        Instant createdAt,
        String downloadUrl) {

    public TaskAttachmentResponseDto(TaskAttachment attachment, MediaUrls mediaUrls) {
        this(attachment, attachment.getMedia(), mediaUrls);
    }

    private TaskAttachmentResponseDto(TaskAttachment attachment, Media media, MediaUrls mediaUrls) {
        this(
                attachment.getId(),
                media.getOriginalFilename(),
                media.getContentType(),
                media.getSizeBytes(),
                UserProfileResponseDto.ofNullable(media.getUploadedBy(), mediaUrls),
                attachment.getComment() == null ? null : attachment.getComment().getId(),
                attachment.getCreatedAt(),
                mediaUrls.of(media));
    }
}
