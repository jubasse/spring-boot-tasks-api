package io.julienmetral.tasks.task.dtos;

import io.julienmetral.tasks.identity.dtos.UserPreviewResponseDto;
import io.julienmetral.tasks.identity.entities.UserStatus;
import io.julienmetral.tasks.media.model.Media;
import io.julienmetral.tasks.media.services.MediaUrls;
import io.julienmetral.tasks.task.entities.TaskAttachment;
import io.julienmetral.tasks.task.entities.TaskComment;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static io.julienmetral.tasks.support.UserSummaries.active;
import static io.julienmetral.tasks.support.UserSummaries.summary;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class TaskCommentResponseDtoTest {

    private static final Instant CREATED_AT = Instant.parse("2026-03-01T10:15:30Z");
    private static final Instant EDITED_AT = Instant.parse("2026-03-01T11:00:00Z");
    private static final Instant VERIFIED_AT = Instant.parse("2026-01-01T00:00:00Z");

    private final MediaUrls mediaUrls = mock(MediaUrls.class);
    private final UUID commentId = UUID.randomUUID();
    private final UUID authorId = UUID.randomUUID();
    private final TaskComment comment = new TaskComment();

    TaskCommentResponseDtoTest() {
        comment.setId(commentId);
        comment.setAuthor(active(authorId, "Ada"));
        comment.setBody("Ping <@" + authorId + ">");
        comment.setCreatedAt(CREATED_AT);
    }

    private TaskAttachment attachment(String filename) {
        Media media = new Media();
        media.setOriginalFilename(filename);
        TaskAttachment attachment = new TaskAttachment();
        attachment.setId(UUID.randomUUID());
        attachment.setMedia(media);
        attachment.setComment(comment);
        when(mediaUrls.of(media)).thenReturn("https://storage.example/" + filename);
        return attachment;
    }

    @Test
    void mapsIdBodyAuthorAndTimestamps() {
        comment.setEditedAt(EDITED_AT);

        TaskCommentResponseDto dto = new TaskCommentResponseDto(comment, mediaUrls);

        assertThat(dto.id()).isEqualTo(commentId);
        assertThat(dto.body()).isEqualTo("Ping <@" + authorId + ">");
        assertThat(dto.author()).isEqualTo(new UserPreviewResponseDto(authorId, "Ada", UserStatus.ACTIVE, null));
        assertThat(dto.createdAt()).isEqualTo(CREATED_AT);
        assertThat(dto.editedAt()).isEqualTo(EDITED_AT);
    }

    @Test
    void editedAtIsNullForACommentNeverEdited() {
        assertThat(new TaskCommentResponseDto(comment, mediaUrls).editedAt()).isNull();
    }

    @Test
    void commentWithoutMentionsOrFilesHasEmptyLists() {
        TaskCommentResponseDto dto = new TaskCommentResponseDto(comment, mediaUrls);

        assertThat(dto.mentions()).isEmpty();
        assertThat(dto.attachments()).isEmpty();
    }

    @Test
    void deletedAuthorKeepsTheirNameAndShowsDeleted() {
        comment.setAuthor(summary(authorId, "Ada", true, VERIFIED_AT, Instant.parse("2026-02-01T00:00:00Z")));

        TaskCommentResponseDto dto = new TaskCommentResponseDto(comment, mediaUrls);

        assertThat(dto.author()).isEqualTo(new UserPreviewResponseDto(authorId, "Ada", UserStatus.DELETED, null));
    }

    @Test
    void mentionsAreSortedByDisplayName() {
        UUID zoe = UUID.randomUUID();
        UUID bob = UUID.randomUUID();
        UUID mia = UUID.randomUUID();
        comment.getMentions().add(active(zoe, "Zoe"));
        comment.getMentions().add(active(bob, "Bob"));
        comment.getMentions().add(active(mia, "Mia"));

        TaskCommentResponseDto dto = new TaskCommentResponseDto(comment, mediaUrls);

        assertThat(dto.mentions())
                .extracting(UserPreviewResponseDto::displayName)
                .containsExactly("Bob", "Mia", "Zoe");
        assertThat(dto.mentions())
                .extracting(UserPreviewResponseDto::id)
                .containsExactly(bob, mia, zoe);
    }

    @Test
    void mentionedUsersShowTheirCurrentStatus() {
        UUID disabledId = UUID.randomUUID();
        comment.getMentions().add(summary(disabledId, "Dan", false, VERIFIED_AT, null));

        TaskCommentResponseDto dto = new TaskCommentResponseDto(comment, mediaUrls);

        assertThat(dto.mentions())
                .containsExactly(new UserPreviewResponseDto(disabledId, "Dan", UserStatus.DISABLED, null));
    }

    @Test
    void attachmentsAreMappedInTheCommentOrder() {
        TaskAttachment first = attachment("first.pdf");
        TaskAttachment second = attachment("second.png");
        comment.getAttachments().addAll(List.of(first, second));

        TaskCommentResponseDto dto = new TaskCommentResponseDto(comment, mediaUrls);

        assertThat(dto.attachments())
                .extracting(TaskAttachmentResponseDto::id, TaskAttachmentResponseDto::filename,
                        TaskAttachmentResponseDto::commentId, TaskAttachmentResponseDto::downloadUrl)
                .containsExactly(
                        tuple(
                                first.getId(), "first.pdf", commentId, "https://storage.example/first.pdf"),
                        tuple(
                                second.getId(), "second.png", commentId, "https://storage.example/second.png")
                );
    }
}
