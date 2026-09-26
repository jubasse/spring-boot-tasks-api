package io.julienmetral.tasks.task.dtos;

import io.julienmetral.tasks.identity.dtos.UserProfileResponseDto;
import io.julienmetral.tasks.identity.entities.UserStatus;
import io.julienmetral.tasks.identity.entities.UserProfile;
import io.julienmetral.tasks.media.services.MediaUrls;
import io.julienmetral.tasks.task.entities.Task;
import io.julienmetral.tasks.task.entities.TaskEvent;
import io.julienmetral.tasks.task.entities.TaskEventType;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

import static io.julienmetral.tasks.support.UserProfiles.active;
import static io.julienmetral.tasks.support.UserProfiles.profile;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class TaskResponseDtoTest {

    private static final UUID ASSIGNEE_ID = UUID.fromString("00000000-0000-0000-0000-000000000001");
    private static final UUID CREATOR_ID = UUID.fromString("00000000-0000-0000-0000-000000000002");
    private static final String ASSIGNEE_AVATAR_URL = "https://storage.example/avatar/alice";
    private static final String CREATOR_AVATAR_URL = "https://storage.example/avatar/bob";

    private final MediaUrls mediaUrls = mock(MediaUrls.class);

    TaskResponseDtoTest() {
        when(mediaUrls.avatarOf(any(UserProfile.class)))
                .thenAnswer(invocation -> identiconUrl(invocation.<UserProfile>getArgument(0).getId()));
    }

    private static String identiconUrl(UUID id) {
        return "/api/v1/identicons/" + id;
    }

    private UserProfile withAvatar(UserProfile user, String url) {
        when(mediaUrls.avatarOf(user)).thenReturn(url);
        return user;
    }

    @Test
    void usersAreShownWithTheirNameAndStatus() {
        Task task = new Task();
        task.setAssignedTo(active(ASSIGNEE_ID, "Alice"));
        task.setCreatedBy(active(CREATOR_ID, "Bob"));

        TaskResponseDto dto = new TaskResponseDto(task, mediaUrls);

        assertThat(dto.assignedTo())
                .isEqualTo(new UserProfileResponseDto(
                        ASSIGNEE_ID, "Alice", UserStatus.ACTIVE, identiconUrl(ASSIGNEE_ID)));
        assertThat(dto.createdBy())
                .isEqualTo(new UserProfileResponseDto(CREATOR_ID, "Bob", UserStatus.ACTIVE, identiconUrl(CREATOR_ID)));
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
        task.setAssignedTo(profile(ASSIGNEE_ID, "Alice", UserStatus.DELETED));
        task.setCreatedBy(profile(CREATOR_ID, "Bob", UserStatus.DELETED));

        TaskResponseDto dto = new TaskResponseDto(task, mediaUrls);

        assertThat(dto.assignedTo())
                .isEqualTo(new UserProfileResponseDto(
                        ASSIGNEE_ID, "Alice", UserStatus.DELETED, identiconUrl(ASSIGNEE_ID)));
        assertThat(dto.createdBy())
                .isEqualTo(new UserProfileResponseDto(CREATOR_ID, "Bob", UserStatus.DELETED, identiconUrl(CREATOR_ID)));
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

        assertThat(dto.actor())
                .isEqualTo(new UserProfileResponseDto(CREATOR_ID, "Bob", UserStatus.ACTIVE, identiconUrl(CREATOR_ID)));
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
        event.setActor(profile(CREATOR_ID, "Bob", UserStatus.DELETED));

        TaskEventResponseDto dto = new TaskEventResponseDto(event, mediaUrls);

        assertThat(dto.actor())
                .isEqualTo(new UserProfileResponseDto(CREATOR_ID, "Bob", UserStatus.DELETED, identiconUrl(CREATOR_ID)));
    }

    @Test
    void taskEventWithoutActorHasNullActor() {
        assertThat(new TaskEventResponseDto(new TaskEvent(), mediaUrls).actor()).isNull();
    }
}
