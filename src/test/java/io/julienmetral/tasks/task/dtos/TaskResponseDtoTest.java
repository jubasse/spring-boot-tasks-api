package io.julienmetral.tasks.task.dtos;

import io.julienmetral.tasks.identity.dtos.UserPreviewResponseDto;
import io.julienmetral.tasks.identity.entities.User;
import io.julienmetral.tasks.identity.entities.UserStatus;
import io.julienmetral.tasks.task.entities.Task;
import io.julienmetral.tasks.task.entities.TaskEvent;
import io.julienmetral.tasks.task.entities.TaskEventType;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class TaskResponseDtoTest {

    private static final UUID ASSIGNEE_ID = UUID.fromString("00000000-0000-0000-0000-000000000001");
    private static final UUID CREATOR_ID = UUID.fromString("00000000-0000-0000-0000-000000000002");

    private static User activeUser(UUID id, String displayName) {
        User user = new User();
        user.setId(id);
        user.setDisplayName(displayName);
        user.setEmailVerifiedAt(Instant.parse("2026-01-01T00:00:00Z"));
        return user;
    }

    @Test
    void loadedUsersAreShownWithTheirNameAndStatus() {
        Task task = new Task();
        task.setAssignedTo(activeUser(ASSIGNEE_ID, "Alice"));
        task.setCreatedBy(activeUser(CREATOR_ID, "Bob"));

        TaskResponseDto dto = new TaskResponseDto(task);

        assertThat(dto.assignedTo())
                .isEqualTo(new UserPreviewResponseDto(ASSIGNEE_ID, "Alice", UserStatus.ACTIVE));
        assertThat(dto.createdBy())
                .isEqualTo(new UserPreviewResponseDto(CREATOR_ID, "Bob", UserStatus.ACTIVE));
    }

    @Test
    void softDeletedUsersAreShownAsDeletedFromTheIdColumns() {
        // A soft-deleted user leaves the association null; only the read-only id columns are left
        Task task = new Task();
        ReflectionTestUtils.setField(task, "assignedToId", ASSIGNEE_ID);
        ReflectionTestUtils.setField(task, "createdById", CREATOR_ID);

        TaskResponseDto dto = new TaskResponseDto(task);

        assertThat(dto.assignedTo())
                .isEqualTo(new UserPreviewResponseDto(ASSIGNEE_ID, null, UserStatus.DELETED));
        assertThat(dto.createdBy())
                .isEqualTo(new UserPreviewResponseDto(CREATOR_ID, null, UserStatus.DELETED));
    }

    @Test
    void unassignedTaskWithoutCreatorHasNullPreviews() {
        TaskResponseDto dto = new TaskResponseDto(new Task());

        assertThat(dto.assignedTo()).isNull();
        assertThat(dto.createdBy()).isNull();
    }

    @Test
    void taskEventActorIsShownFromTheAssociation() {
        TaskEvent event = new TaskEvent();
        event.setType(TaskEventType.CREATED);
        event.setActor(activeUser(CREATOR_ID, "Bob"));
        event.setPayload(Map.of());

        TaskEventResponseDto dto = new TaskEventResponseDto(event);

        assertThat(dto.actor()).isEqualTo(new UserPreviewResponseDto(CREATOR_ID, "Bob", UserStatus.ACTIVE));
        assertThat(dto.type()).isEqualTo(TaskEventType.CREATED);
    }

    @Test
    void softDeletedTaskEventActorIsShownAsDeletedFromTheIdColumn() {
        TaskEvent event = new TaskEvent();
        ReflectionTestUtils.setField(event, "actorId", CREATOR_ID);

        TaskEventResponseDto dto = new TaskEventResponseDto(event);

        assertThat(dto.actor()).isEqualTo(new UserPreviewResponseDto(CREATOR_ID, null, UserStatus.DELETED));
    }
}
