package io.julienmetral.tasks.task.dtos;

import io.julienmetral.tasks.identity.dtos.UserPreviewResponseDto;
import io.julienmetral.tasks.identity.entities.UserStatus;
import io.julienmetral.tasks.media.model.Media;
import io.julienmetral.tasks.media.services.MediaUrls;
import io.julienmetral.tasks.task.entities.TaskAttachment;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.UUID;

import static io.julienmetral.tasks.support.UserSummaries.active;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class TaskAttachmentResponseDtoTest {

    private static final Instant CREATED_AT = Instant.parse("2026-03-01T10:15:30Z");

    private final MediaUrls mediaUrls = mock(MediaUrls.class);
    private final UUID attachmentId = UUID.randomUUID();
    private final Media media = new Media();
    private final TaskAttachment attachment = new TaskAttachment();

    TaskAttachmentResponseDtoTest() {
        media.setOriginalFilename("report.pdf");
        media.setContentType("application/pdf");
        media.setSizeBytes(2048);
        attachment.setId(attachmentId);
        attachment.setMedia(media);
        attachment.setCreatedAt(CREATED_AT);
        when(mediaUrls.of(media)).thenReturn("https://storage.example/report.pdf?signature");
    }

    @Test
    void mapsMediaMetadataAndDownloadUrl() {
        TaskAttachmentResponseDto dto = new TaskAttachmentResponseDto(attachment, mediaUrls);

        assertThat(dto.id()).isEqualTo(attachmentId);
        assertThat(dto.filename()).isEqualTo("report.pdf");
        assertThat(dto.contentType()).isEqualTo("application/pdf");
        assertThat(dto.sizeBytes()).isEqualTo(2048);
        assertThat(dto.createdAt()).isEqualTo(CREATED_AT);
        assertThat(dto.downloadUrl()).isEqualTo("https://storage.example/report.pdf?signature");
    }

    @Test
    void exposesUploaderAsPreview() {
        UUID uploaderId = UUID.randomUUID();
        media.setUploadedBy(active(uploaderId, "Ada"));

        TaskAttachmentResponseDto dto = new TaskAttachmentResponseDto(attachment, mediaUrls);

        assertThat(dto.uploadedBy()).isEqualTo(new UserPreviewResponseDto(uploaderId, "Ada", UserStatus.ACTIVE, null));
    }

    @Test
    void uploadedByIsNullWithoutUploader() {
        TaskAttachmentResponseDto dto = new TaskAttachmentResponseDto(attachment, mediaUrls);

        assertThat(dto.uploadedBy()).isNull();
    }
}
