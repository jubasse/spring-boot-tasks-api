package io.julienmetral.tasks.task.services;

import io.julienmetral.tasks.identity.entities.User;
import io.julienmetral.tasks.identity.exceptions.UserNotFoundException;
import io.julienmetral.tasks.identity.repositories.UserRepository;
import io.julienmetral.tasks.identity.security.CurrentUser;
import io.julienmetral.tasks.task.dtos.CreateTaskDto;
import io.julienmetral.tasks.task.dtos.UpdateTaskDto;
import io.julienmetral.tasks.task.entities.Task;
import io.julienmetral.tasks.task.entities.TaskPriority;
import io.julienmetral.tasks.task.entities.TaskStatus;
import io.julienmetral.tasks.task.exceptions.TaskNotFoundException;
import io.julienmetral.tasks.task.exceptions.TaskReferenceAlreadyExistsException;
import io.julienmetral.tasks.task.repositories.TaskRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class TaskServiceTest {

    private static final UUID TASK_ID = UUID.fromString("00000000-0000-0000-0000-00000000000a");
    private static final Instant OLD = Instant.parse("2020-01-01T00:00:00Z");

    @Mock
    private TaskRepository taskRepository;

    @Mock
    private TaskEventService taskEventService;

    @Mock
    private UserRepository userRepository;

    @Mock
    private CurrentUser currentUser;

    @InjectMocks
    private TaskService taskService;

    private static User user(UUID id) {
        User user = new User();
        user.setId(id);
        user.setDisplayName("User " + id);
        return user;
    }

    private Task stubTask() {
        Task task = new Task();
        task.setId(TASK_ID);
        task.setReference("TASK-1");
        task.setTitle("Title");
        task.setDescription("Desc");
        task.setPriority(TaskPriority.LOW);
        task.setDueAt(OLD);
        when(taskRepository.findById(TASK_ID)).thenReturn(Optional.of(task));
        return task;
    }

    private void stubMissingTask() {
        when(taskRepository.findById(TASK_ID)).thenReturn(Optional.empty());
    }

    // --- create ---

    @Test
    void createPopulatesTaskSetsCreatorAndAssigneeAndRecordsEvent() {
        UUID creatorId = UUID.randomUUID();
        UUID assigneeId = UUID.randomUUID();
        User creator = user(creatorId);
        User assignee = user(assigneeId);
        Instant due = Instant.parse("2030-01-01T00:00:00Z");
        var dto = new CreateTaskDto("TASK-1", "Title", "Desc", TaskPriority.HIGH, due, assigneeId);

        when(taskRepository.existsByReferenceIncludingDeleted("TASK-1")).thenReturn(false);
        when(currentUser.getId()).thenReturn(Optional.of(creatorId));
        when(userRepository.findById(creatorId)).thenReturn(Optional.of(creator));
        when(userRepository.findById(assigneeId)).thenReturn(Optional.of(assignee));
        Task saved = new Task();
        when(taskRepository.save(any(Task.class))).thenReturn(saved);

        Task result = taskService.create(dto);

        ArgumentCaptor<Task> captor = ArgumentCaptor.forClass(Task.class);
        verify(taskRepository).save(captor.capture());
        Task built = captor.getValue();
        assertThat(built.getReference()).isEqualTo("TASK-1");
        assertThat(built.getTitle()).isEqualTo("Title");
        assertThat(built.getDescription()).isEqualTo("Desc");
        assertThat(built.getPriority()).isEqualTo(TaskPriority.HIGH);
        assertThat(built.getDueAt()).isEqualTo(due);
        assertThat(built.getStatus()).isEqualTo(TaskStatus.TO_DO);
        assertThat(built.getCreatedBy()).isSameAs(creator);
        assertThat(built.getAssignedTo()).isSameAs(assignee);

        assertThat(result).isSameAs(saved);
        verify(taskEventService).created(saved);
    }

    @Test
    void createWithoutAuthenticatedUserOrAssigneeLeavesThemNull() {
        var dto = new CreateTaskDto("TASK-2", "Title", null, null, null, null);
        when(taskRepository.existsByReferenceIncludingDeleted("TASK-2")).thenReturn(false);
        when(currentUser.getId()).thenReturn(Optional.empty());
        when(taskRepository.save(any(Task.class))).thenAnswer(inv -> inv.getArgument(0));

        Task result = taskService.create(dto);

        assertThat(result.getCreatedBy()).isNull();
        assertThat(result.getAssignedTo()).isNull();
        assertThat(result.getPriority()).isEqualTo(TaskPriority.MEDIUM);
        verifyNoInteractions(userRepository);
        verify(taskEventService).created(result);
    }

    @Test
    void createWhenAuthenticatedUserNoLongerExistsLeavesCreatorNull() {
        UUID creatorId = UUID.randomUUID();
        var dto = new CreateTaskDto("TASK-3", "Title", null, null, null, null);
        when(taskRepository.existsByReferenceIncludingDeleted("TASK-3")).thenReturn(false);
        when(currentUser.getId()).thenReturn(Optional.of(creatorId));
        when(userRepository.findById(creatorId)).thenReturn(Optional.empty());
        when(taskRepository.save(any(Task.class))).thenAnswer(inv -> inv.getArgument(0));

        assertThat(taskService.create(dto).getCreatedBy()).isNull();
    }

    @Test
    void createDefaultsPriorityToMediumWhenDtoPriorityIsNull() {
        // The CreateTaskDto canonical constructor already defaults priority, so the service's own
        // null fallback is only reachable with a mocked record.
        CreateTaskDto dto = mock(CreateTaskDto.class);
        when(dto.reference()).thenReturn("TASK-4");
        when(dto.priority()).thenReturn(null);
        when(taskRepository.existsByReferenceIncludingDeleted("TASK-4")).thenReturn(false);
        when(currentUser.getId()).thenReturn(Optional.empty());
        when(taskRepository.save(any(Task.class))).thenAnswer(inv -> inv.getArgument(0));

        assertThat(taskService.create(dto).getPriority()).isEqualTo(TaskPriority.MEDIUM);
    }

    @Test
    void createWithDuplicateReferenceThrows() {
        var dto = new CreateTaskDto("TASK-1", "Title", null, null, null, null);
        when(taskRepository.existsByReferenceIncludingDeleted("TASK-1")).thenReturn(true);

        assertThatThrownBy(() -> taskService.create(dto))
                .isInstanceOf(TaskReferenceAlreadyExistsException.class)
                .hasMessageContaining("TASK-1");

        verify(taskRepository, never()).save(any());
        verifyNoInteractions(taskEventService);
    }

    @Test
    void createWithUnknownAssigneeThrows() {
        UUID assigneeId = UUID.randomUUID();
        var dto = new CreateTaskDto("TASK-1", "Title", null, null, null, assigneeId);
        when(taskRepository.existsByReferenceIncludingDeleted("TASK-1")).thenReturn(false);
        when(currentUser.getId()).thenReturn(Optional.empty());
        when(userRepository.findById(assigneeId)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> taskService.create(dto))
                .isInstanceOf(UserNotFoundException.class)
                .hasMessageContaining(assigneeId.toString());

        verify(taskRepository, never()).save(any());
        verifyNoInteractions(taskEventService);
    }

    // --- find ---

    @Test
    void findByIdReturnsTask() {
        Task task = stubTask();

        assertThat(taskService.findById(TASK_ID)).isSameAs(task);
    }

    @Test
    void findByIdThrowsWhenMissing() {
        stubMissingTask();

        assertThatThrownBy(() -> taskService.findById(TASK_ID))
                .isInstanceOf(TaskNotFoundException.class)
                .hasMessageContaining(TASK_ID.toString());
    }

    @Test
    void findByReferenceReturnsTask() {
        Task task = new Task();
        when(taskRepository.findByReference("TASK-1")).thenReturn(Optional.of(task));

        assertThat(taskService.findByReference("TASK-1")).isSameAs(task);
    }

    @Test
    void findByReferenceThrowsWhenMissing() {
        when(taskRepository.findByReference("NOPE")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> taskService.findByReference("NOPE"))
                .isInstanceOf(TaskNotFoundException.class)
                .hasMessageContaining("NOPE");
    }

    // --- update ---

    @Test
    void updateAppliesAllProvidedFields() {
        Task task = stubTask();
        Instant due = Instant.parse("2031-01-01T00:00:00Z");

        Task result = taskService.update(TASK_ID, new UpdateTaskDto("New", "New desc", TaskPriority.URGENT, due));

        assertThat(result).isSameAs(task);
        assertThat(task.getTitle()).isEqualTo("New");
        assertThat(task.getDescription()).isEqualTo("New desc");
        assertThat(task.getPriority()).isEqualTo(TaskPriority.URGENT);
        assertThat(task.getDueAt()).isEqualTo(due);
        verify(taskEventService).updated(task);
    }

    @Test
    void updateWithAllNullFieldsKeepsExistingValues() {
        Task task = stubTask();

        taskService.update(TASK_ID, new UpdateTaskDto(null, null, null, null));

        assertThat(task.getTitle()).isEqualTo("Title");
        assertThat(task.getDescription()).isEqualTo("Desc");
        assertThat(task.getPriority()).isEqualTo(TaskPriority.LOW);
        assertThat(task.getDueAt()).isEqualTo(OLD);
        verify(taskEventService).updated(task);
    }

    @Test
    void updateThrowsWhenTaskMissing() {
        stubMissingTask();

        assertThatThrownBy(() -> taskService.update(TASK_ID, new UpdateTaskDto("x", null, null, null)))
                .isInstanceOf(TaskNotFoundException.class);
        verifyNoInteractions(taskEventService);
    }

    // --- changeStatus ---

    @Test
    void changeStatusToSameStatusIsNoOp() {
        Task task = stubTask();
        task.setStatus(TaskStatus.IN_PROGRESS);

        Task result = taskService.changeStatus(TASK_ID, TaskStatus.IN_PROGRESS);

        assertThat(result).isSameAs(task);
        assertThat(task.getStatus()).isEqualTo(TaskStatus.IN_PROGRESS);
        verifyNoInteractions(taskEventService);
    }

    @Test
    void changeStatusToDoneSetsCompletedAtAndClearsCancellation() {
        Task task = stubTask();
        task.setStatus(TaskStatus.CANCELLED);
        task.setCancelledAt(OLD);
        task.setCancelledReason("reason");

        taskService.changeStatus(TASK_ID, TaskStatus.DONE);

        assertThat(task.getStatus()).isEqualTo(TaskStatus.DONE);
        assertThat(task.getCompletedAt()).isNotNull().isAfter(OLD);
        assertThat(task.getCancelledAt()).isNull();
        assertThat(task.getCancelledReason()).isNull();
        verify(taskEventService).statusChanged(task, TaskStatus.CANCELLED, TaskStatus.DONE);
    }

    @Test
    void changeStatusToCancelledSetsCancelledAtAndClearsCompletedAt() {
        Task task = stubTask();
        task.setStatus(TaskStatus.DONE);
        task.setCompletedAt(OLD);

        taskService.changeStatus(TASK_ID, TaskStatus.CANCELLED);

        assertThat(task.getStatus()).isEqualTo(TaskStatus.CANCELLED);
        assertThat(task.getCancelledAt()).isNotNull().isAfter(OLD);
        assertThat(task.getCompletedAt()).isNull();
        verify(taskEventService).statusChanged(task, TaskStatus.DONE, TaskStatus.CANCELLED);
    }

    @ParameterizedTest
    @EnumSource(value = TaskStatus.class, names = {"IN_PROGRESS", "IN_REVIEW", "BLOCKED", "ARCHIVED"})
    void changeStatusToOtherStatusClearsCompletionAndCancellation(TaskStatus target) {
        Task task = stubTask();
        task.setStatus(TaskStatus.DONE);
        task.setCompletedAt(OLD);
        task.setCancelledAt(OLD);
        task.setCancelledReason("reason");

        taskService.changeStatus(TASK_ID, target);

        assertThat(task.getStatus()).isEqualTo(target);
        assertThat(task.getCompletedAt()).isNull();
        assertThat(task.getCancelledAt()).isNull();
        assertThat(task.getCancelledReason()).isNull();
        verify(taskEventService).statusChanged(task, TaskStatus.DONE, target);
    }

    @Test
    void changeStatusThrowsWhenTaskMissing() {
        stubMissingTask();

        assertThatThrownBy(() -> taskService.changeStatus(TASK_ID, TaskStatus.DONE))
                .isInstanceOf(TaskNotFoundException.class);
        verifyNoInteractions(taskEventService);
    }

    // --- assign ---

    @Test
    void assignUnassignedTaskRecordsNullPreviousAssignee() {
        Task task = stubTask();
        UUID userId = UUID.randomUUID();
        User assignee = user(userId);
        when(userRepository.findById(userId)).thenReturn(Optional.of(assignee));

        Task result = taskService.assign(TASK_ID, userId);

        assertThat(result.getAssignedTo()).isSameAs(assignee);
        verify(taskEventService).assignmentChanged(task, null, userId);
    }

    @Test
    void reassignRecordsPreviousAssignee() {
        Task task = stubTask();
        UUID previousId = UUID.randomUUID();
        task.setAssignedTo(user(previousId));
        UUID userId = UUID.randomUUID();
        User assignee = user(userId);
        when(userRepository.findById(userId)).thenReturn(Optional.of(assignee));

        taskService.assign(TASK_ID, userId);

        assertThat(task.getAssignedTo()).isSameAs(assignee);
        verify(taskEventService).assignmentChanged(task, previousId, userId);
    }

    @Test
    void assignToSameUserIsNoOp() {
        Task task = stubTask();
        UUID userId = UUID.randomUUID();
        User current = user(userId);
        task.setAssignedTo(current);

        Task result = taskService.assign(TASK_ID, userId);

        assertThat(result.getAssignedTo()).isSameAs(current);
        verifyNoInteractions(userRepository, taskEventService);
    }

    @Test
    void assignToUnknownUserThrows() {
        Task task = stubTask();
        UUID userId = UUID.randomUUID();
        when(userRepository.findById(userId)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> taskService.assign(TASK_ID, userId))
                .isInstanceOf(UserNotFoundException.class);

        assertThat(task.getAssignedTo()).isNull();
        verifyNoInteractions(taskEventService);
    }

    @Test
    void assignThrowsWhenTaskMissing() {
        stubMissingTask();

        assertThatThrownBy(() -> taskService.assign(TASK_ID, UUID.randomUUID()))
                .isInstanceOf(TaskNotFoundException.class);
    }

    // --- archive / unarchive ---

    @Test
    void archiveSetsArchivedAtAndRecordsEvent() {
        Task task = stubTask();

        taskService.archive(TASK_ID);

        assertThat(task.getArchivedAt()).isNotNull();
        verify(taskEventService).archived(task);
    }

    @Test
    void archiveAlreadyArchivedTaskIsNoOp() {
        Task task = stubTask();
        task.setArchivedAt(OLD);

        taskService.archive(TASK_ID);

        assertThat(task.getArchivedAt()).isEqualTo(OLD);
        verifyNoInteractions(taskEventService);
    }

    @Test
    void unarchiveClearsArchivedAtAndRecordsEvent() {
        Task task = stubTask();
        task.setArchivedAt(OLD);

        taskService.unarchive(TASK_ID);

        assertThat(task.getArchivedAt()).isNull();
        verify(taskEventService).unarchived(task);
    }

    @Test
    void unarchiveNotArchivedTaskIsNoOp() {
        stubTask();

        taskService.unarchive(TASK_ID);

        verifyNoInteractions(taskEventService);
    }

    @Test
    void archiveThrowsWhenTaskMissing() {
        stubMissingTask();

        assertThatThrownBy(() -> taskService.archive(TASK_ID)).isInstanceOf(TaskNotFoundException.class);
    }

    @Test
    void unarchiveThrowsWhenTaskMissing() {
        stubMissingTask();

        assertThatThrownBy(() -> taskService.unarchive(TASK_ID)).isInstanceOf(TaskNotFoundException.class);
    }

    // --- cancel ---

    @Test
    void cancelSetsCancellationFieldsClearsCompletionAndRecordsEvent() {
        Task task = stubTask();
        task.setStatus(TaskStatus.DONE);
        task.setCompletedAt(OLD);

        Task result = taskService.cancel(TASK_ID, "no longer needed");

        assertThat(result).isSameAs(task);
        assertThat(task.getStatus()).isEqualTo(TaskStatus.CANCELLED);
        assertThat(task.getCancelledAt()).isNotNull();
        assertThat(task.getCancelledReason()).isEqualTo("no longer needed");
        assertThat(task.getCompletedAt()).isNull();
        verify(taskEventService).cancelled(task, TaskStatus.DONE, "no longer needed");
    }

    @Test
    void cancelThrowsWhenTaskMissing() {
        stubMissingTask();

        assertThatThrownBy(() -> taskService.cancel(TASK_ID, "r")).isInstanceOf(TaskNotFoundException.class);
        verifyNoInteractions(taskEventService);
    }

    // --- delete ---

    @Test
    void deleteDeletesLoadedTask() {
        Task task = stubTask();

        taskService.delete(TASK_ID);

        verify(taskRepository).delete(task);
    }

    @Test
    void deleteThrowsWhenTaskMissing() {
        stubMissingTask();

        assertThatThrownBy(() -> taskService.delete(TASK_ID)).isInstanceOf(TaskNotFoundException.class);
        verify(taskRepository, never()).delete(any(Task.class));
    }
}
