package io.julienmetral.tasks.task.dtos;

import io.julienmetral.tasks.identity.dtos.UserPreviewResponseDto;
import io.julienmetral.tasks.identity.entities.UserStatus;
import io.julienmetral.tasks.identity.entities.UserSummary;
import io.julienmetral.tasks.media.model.Media;
import io.julienmetral.tasks.media.services.MediaUrls;
import io.julienmetral.tasks.task.entities.Task;
import io.julienmetral.tasks.task.entities.TaskEvent;
import io.julienmetral.tasks.task.entities.TaskEventType;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

import static io.julienmetral.tasks.support.UserSummaries.active;
import static io.julienmetral.tasks.support.UserSummaries.summary;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class TaskResponseDtoTest {

    private static final UUID ASSIGNEE_ID = UUID.fromString("00000000-0000-0000-0000-000000000001");
    private static final UUID CREATOR_ID = UUID.fromString("00000000-0000-0000-0000-000000000002");
    private static final Instant VERIFIED_AT = Instant.parse("2026-01-01T00:00:00Z");
    private static final Instant DELETED_AT = Instant.parse("2026-02-01T00:00:00Z");
    private static final String ASSIGNEE_AVATAR_URL = "https://storage.example/avatar/alice";
    private static final String CREATOR_AVATAR_URL = "https://storage.example/avatar/bob";

    private final MediaUrls mediaUrls = mock(MediaUrls.class);

    private UserSummary withAvatar(UserSummary user, String url) {
        Media avatar = new Media();
        ReflectionTestUtils.setField(user, "avatar", avatar);
        when(mediaUrls.of(avatar)).thenReturn(url);
        return user;
    }

    @Test
    void usersAreShownWithTheirNameAndStatus() {
        Task task = new Task();
        task.setAssignedTo(active(ASSIGNEE_ID, "Alice"));
        task.setCreatedBy(active(CREATOR_ID, "Bob"));

        TaskResponseDto dto = new TaskResponseDto(task, mediaUrls);

        assertThat(dto.assignedTo())
                .isEqualTo(new UserPreviewResponseDto(ASSIGNEE_ID, "Alice", UserStatus.ACTIVE, null));
        assertThat(dto.createdBy())
                .isEqualTo(new UserPreviewResponseDto(CREATOR_ID, "Bob", UserStatus.ACTIVE, null));
    }

    @Test
    void usersAreShownWithTheirOwnAvatarUrl() {
        Task task = new Task();
        task.setAssignedTo(withAvatar(active(ASSIGNEE_ID, "Alice"), ASSIGNEE_AVATAR_URL));
        task.setCreatedBy(withAvatar(active(CREATOR_ID, "Bob"), CREATOR_AVATAR_URL));

        TaskResponseDto dto = new TaskResponseDto(task, mediaUrls);

        assertThat(dto.assignedTo().avatarUrl()).isEqualTo(ASSIGNEE_AVATAR_URL);
        assertThat(dto.createdBy().avatarUrl()).isEqualTo(CREATOR_AVATAR_URL);
    }

    @Test
    void softDeletedUsersKeepTheirNameAndAreShownAsDeleted() {
        Task task = new Task();
        task.setAssignedTo(summary(ASSIGNEE_ID, "Alice", true, VERIFIED_AT, DELETED_AT));
        task.setCreatedBy(summary(CREATOR_ID, "Bob", true, VERIFIED_AT, DELETED_AT));

        TaskResponseDto dto = new TaskResponseDto(task, mediaUrls);

        assertThat(dto.assignedTo())
                .isEqualTo(new UserPreviewResponseDto(ASSIGNEE_ID, "Alice", UserStatus.DELETED, null));
        assertThat(dto.createdBy())
                .isEqualTo(new UserPreviewResponseDto(CREATOR_ID, "Bob", UserStatus.DELETED, null));
    }

    @Test
    void unassignedTaskWithoutCreatorHasNullPreviews() {
        TaskResponseDto dto = new TaskResponseDto(new Task(), mediaUrls);

        assertThat(dto.assignedTo()).isNull();
        assertThat(dto.createdBy()).isNull();
        verifyNoInteractions(mediaUrls);
    }

    @Test
    void taskEventActorIsShownWithItsNameAndStatus() {
        TaskEvent event = new TaskEvent();
        event.setType(TaskEventType.CREATED);
        event.setActor(active(CREATOR_ID, "Bob"));
        event.setPayload(Map.of());

        TaskEventResponseDto dto = new TaskEventResponseDto(event, mediaUrls);

        assertThat(dto.actor()).isEqualTo(new UserPreviewResponseDto(CREATOR_ID, "Bob", UserStatus.ACTIVE, null));
        assertThat(dto.type()).isEqualTo(TaskEventType.CREATED);
    }

    @Test
    void taskEventActorIsShownWithItsAvatarUrl() {
        TaskEvent event = new TaskEvent();
        event.setActor(withAvatar(active(CREATOR_ID, "Bob"), CREATOR_AVATAR_URL));

        TaskEventResponseDto dto = new TaskEventResponseDto(event, mediaUrls);

        assertThat(dto.actor().avatarUrl()).isEqualTo(CREATOR_AVATAR_URL);
    }

    @Test
    void softDeletedTaskEventActorKeepsItsNameAndIsShownAsDeleted() {
        TaskEvent event = new TaskEvent();
        event.setActor(summary(CREATOR_ID, "Bob", true, VERIFIED_AT, DELETED_AT));

        TaskEventResponseDto dto = new TaskEventResponseDto(event, mediaUrls);

        assertThat(dto.actor()).isEqualTo(new UserPreviewResponseDto(CREATOR_ID, "Bob", UserStatus.DELETED, null));
    }

    @Test
    void taskEventWithoutActorHasNullActor() {
        assertThat(new TaskEventResponseDto(new TaskEvent(), mediaUrls).actor()).isNull();
    }
}
