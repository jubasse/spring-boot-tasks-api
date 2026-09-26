package io.julienmetral.tasks.task.dtos;

import io.julienmetral.tasks.identity.dtos.UserPreviewResponseDto;
import io.julienmetral.tasks.identity.entities.UserStatus;
import io.julienmetral.tasks.task.entities.Task;
import io.julienmetral.tasks.task.entities.TaskEvent;
import io.julienmetral.tasks.task.entities.TaskEventType;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

import static io.julienmetral.tasks.support.UserSummaries.active;
import static io.julienmetral.tasks.support.UserSummaries.summary;
import static org.assertj.core.api.Assertions.assertThat;

class TaskResponseDtoTest {

    private static final UUID ASSIGNEE_ID = UUID.fromString("00000000-0000-0000-0000-000000000001");
    private static final UUID CREATOR_ID = UUID.fromString("00000000-0000-0000-0000-000000000002");
    private static final Instant VERIFIED_AT = Instant.parse("2026-01-01T00:00:00Z");
    private static final Instant DELETED_AT = Instant.parse("2026-02-01T00:00:00Z");

    @Test
    void usersAreShownWithTheirNameAndStatus() {
        Task task = new Task();
        task.setAssignedTo(active(ASSIGNEE_ID, "Alice"));
        task.setCreatedBy(active(CREATOR_ID, "Bob"));

        TaskResponseDto dto = new TaskResponseDto(task);

        assertThat(dto.assignedTo())
                .isEqualTo(new UserPreviewResponseDto(ASSIGNEE_ID, "Alice", UserStatus.ACTIVE));
        assertThat(dto.createdBy())
                .isEqualTo(new UserPreviewResponseDto(CREATOR_ID, "Bob", UserStatus.ACTIVE));
    }

    @Test
    void softDeletedUsersKeepTheirNameAndAreShownAsDeleted() {
        Task task = new Task();
        task.setAssignedTo(summary(ASSIGNEE_ID, "Alice", true, VERIFIED_AT, DELETED_AT));
        task.setCreatedBy(summary(CREATOR_ID, "Bob", true, VERIFIED_AT, DELETED_AT));

        TaskResponseDto dto = new TaskResponseDto(task);

        assertThat(dto.assignedTo())
                .isEqualTo(new UserPreviewResponseDto(ASSIGNEE_ID, "Alice", UserStatus.DELETED));
        assertThat(dto.createdBy())
                .isEqualTo(new UserPreviewResponseDto(CREATOR_ID, "Bob", UserStatus.DELETED));
    }

    @Test
    void unassignedTaskWithoutCreatorHasNullPreviews() {
        TaskResponseDto dto = new TaskResponseDto(new Task());

        assertThat(dto.assignedTo()).isNull();
        assertThat(dto.createdBy()).isNull();
    }

    @Test
    void taskEventActorIsShownWithItsNameAndStatus() {
        TaskEvent event = new TaskEvent();
        event.setType(TaskEventType.CREATED);
        event.setActor(active(CREATOR_ID, "Bob"));
        event.setPayload(Map.of());

        TaskEventResponseDto dto = new TaskEventResponseDto(event);

        assertThat(dto.actor()).isEqualTo(new UserPreviewResponseDto(CREATOR_ID, "Bob", UserStatus.ACTIVE));
        assertThat(dto.type()).isEqualTo(TaskEventType.CREATED);
    }

    @Test
    void softDeletedTaskEventActorKeepsItsNameAndIsShownAsDeleted() {
        TaskEvent event = new TaskEvent();
        event.setActor(summary(CREATOR_ID, "Bob", true, VERIFIED_AT, DELETED_AT));

        TaskEventResponseDto dto = new TaskEventResponseDto(event);

        assertThat(dto.actor()).isEqualTo(new UserPreviewResponseDto(CREATOR_ID, "Bob", UserStatus.DELETED));
    }

    @Test
    void taskEventWithoutActorHasNullActor() {
        assertThat(new TaskEventResponseDto(new TaskEvent()).actor()).isNull();
    }
}
