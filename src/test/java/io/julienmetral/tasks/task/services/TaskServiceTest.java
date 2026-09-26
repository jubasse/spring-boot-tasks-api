package io.julienmetral.tasks.task.services;

import io.julienmetral.tasks.identity.entities.User;
import io.julienmetral.tasks.identity.entities.UserStatus;
import io.julienmetral.tasks.identity.entities.UserSummary;
import io.julienmetral.tasks.identity.exceptions.UserNotFoundException;
import io.julienmetral.tasks.identity.repositories.UserRepository;
import io.julienmetral.tasks.identity.repositories.UserSummaryRepository;
import io.julienmetral.tasks.identity.security.CurrentUser;
import io.julienmetral.tasks.task.dtos.CreateTaskDto;
import io.julienmetral.tasks.task.dtos.UpdateTaskDto;
import io.julienmetral.tasks.task.entities.Task;
import io.julienmetral.tasks.task.entities.TaskPriority;
import io.julienmetral.tasks.task.entities.TaskStatus;
import io.julienmetral.tasks.task.events.TaskAssigned;
import io.julienmetral.tasks.task.events.TaskCancelled;
import io.julienmetral.tasks.task.events.TaskDeleted;
import io.julienmetral.tasks.task.events.TaskUnassigned;
import io.julienmetral.tasks.task.exceptions.AssigneeNotActiveException;
import io.julienmetral.tasks.task.exceptions.TaskNotFoundException;
import io.julienmetral.tasks.task.exceptions.TaskReferenceAlreadyExistsException;
import io.julienmetral.tasks.task.repositories.TaskRepository;
import jakarta.persistence.criteria.CriteriaBuilder;
import jakarta.persistence.criteria.CriteriaQuery;
import jakarta.persistence.criteria.Path;
import jakarta.persistence.criteria.Predicate;
import jakarta.persistence.criteria.Root;
import org.junit.jupiter.api.Test;
import org.springframework.context.ApplicationEventPublisher;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.domain.Specification;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static io.julienmetral.tasks.support.UserSummaries.reference;
import static io.julienmetral.tasks.support.UserSummaries.summary;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class TaskServiceTest {

    private static final UUID TASK_ID = UUID.fromString("00000000-0000-0000-0000-00000000000a");
    private static final Instant OLD = Instant.parse("2020-01-01T00:00:00Z");
    private static final UUID ACTOR_ID = UUID.fromString("00000000-0000-0000-0000-0000000000ac");
    private static final Instant VERIFIED_AT = Instant.parse("2026-01-01T00:00:00Z");

    @Mock
    private TaskRepository taskRepository;

    @Mock
    private TaskEventService taskEventService;

    @Mock
    private UserRepository userRepository;

    @Mock
    private UserSummaryRepository userSummaryRepository;

    @Mock
    private CurrentUser currentUser;

    @Mock
    private ApplicationEventPublisher eventPublisher;

    @InjectMocks
    private TaskService taskService;

    /** A user whose status is the given one ({@code DELETED} is not loadable, so it is not a valid input). */
    private static User userWithStatus(UUID id, UserStatus status) {
        User user = user(id);
        switch (status) {
            case UNVERIFIED -> user.setEmailVerifiedAt(null);
            case DISABLED -> user.setEnabled(false);
            default -> {
            }
        }
        assertThat(UserStatus.of(user)).isEqualTo(status);
        return user;
    }

    private static User user(UUID id) {
        User user = new User();
        user.setId(id);
        user.setDisplayName("User " + id);
        // Only enabled users with a verified email can be assigned
        user.setEmailVerifiedAt(VERIFIED_AT);
        return user;
    }

    private static UserSummary softDeleted(UUID id) {
        return summary(id, "Deleted " + id, true, VERIFIED_AT, Instant.parse("2026-02-01T00:00:00Z"));
    }

    private UserSummary stubReference(UUID id) {
        UserSummary reference = reference(id);
        when(userSummaryRepository.getReferenceById(id)).thenReturn(reference);
        return reference;
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

    @Test
    void createPopulatesTaskSetsCreatorAndAssigneeAndRecordsEvent() {
        UUID creatorId = UUID.randomUUID();
        UUID assigneeId = UUID.randomUUID();
        Instant due = Instant.parse("2030-01-01T00:00:00Z");
        var dto = new CreateTaskDto("TASK-1", "Title", "Desc", TaskPriority.HIGH, due, assigneeId);

        when(taskRepository.existsByReferenceIncludingDeleted("TASK-1")).thenReturn(false);
        when(currentUser.getId()).thenReturn(Optional.of(creatorId));
        when(userRepository.existsById(creatorId)).thenReturn(true);
        UserSummary creator = stubReference(creatorId);
        when(userRepository.findById(assigneeId)).thenReturn(Optional.of(user(assigneeId)));
        UserSummary assignee = stubReference(assigneeId);
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
        verifyNoInteractions(userRepository, userSummaryRepository);
        verify(taskEventService).created(result);
        verifyNoInteractions(eventPublisher);
    }

    @Test
    void createWhenAuthenticatedUserNoLongerExistsLeavesCreatorNull() {
        UUID creatorId = UUID.randomUUID();
        var dto = new CreateTaskDto("TASK-3", "Title", null, null, null, null);
        when(taskRepository.existsByReferenceIncludingDeleted("TASK-3")).thenReturn(false);
        when(currentUser.getId()).thenReturn(Optional.of(creatorId));
        when(userRepository.existsById(creatorId)).thenReturn(false);
        when(taskRepository.save(any(Task.class))).thenAnswer(inv -> inv.getArgument(0));

        assertThat(taskService.create(dto).getCreatedBy()).isNull();
        verifyNoInteractions(userSummaryRepository);
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
        verifyNoInteractions(eventPublisher);
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
        verifyNoInteractions(userSummaryRepository);
        verifyNoInteractions(taskEventService);
        verifyNoInteractions(eventPublisher);
    }

    @ParameterizedTest
    @EnumSource(value = UserStatus.class, names = {"UNVERIFIED", "DISABLED"})
    void createWithInactiveAssigneeThrows(UserStatus status) {
        UUID assigneeId = UUID.randomUUID();
        var dto = new CreateTaskDto("TASK-1", "Title", null, null, null, assigneeId);
        when(taskRepository.existsByReferenceIncludingDeleted("TASK-1")).thenReturn(false);
        when(currentUser.getId()).thenReturn(Optional.empty());
        when(userRepository.findById(assigneeId)).thenReturn(Optional.of(userWithStatus(assigneeId, status)));

        assertThatThrownBy(() -> taskService.create(dto))
                .isInstanceOf(AssigneeNotActiveException.class)
                .hasMessageContaining(assigneeId.toString())
                .hasMessageContaining(status.name());

        verify(taskRepository, never()).save(any());
        verifyNoInteractions(userSummaryRepository);
        verifyNoInteractions(taskEventService);
        verifyNoInteractions(eventPublisher);
    }

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

    @Test
    void changeStatusToSameStatusIsNoOp() {
        Task task = stubTask();
        task.setStatus(TaskStatus.IN_PROGRESS);

        Task result = taskService.changeStatus(TASK_ID, TaskStatus.IN_PROGRESS);

        assertThat(result).isSameAs(task);
        assertThat(task.getStatus()).isEqualTo(TaskStatus.IN_PROGRESS);
        verifyNoInteractions(taskEventService);
        verifyNoInteractions(eventPublisher);
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
        verifyNoInteractions(eventPublisher);
    }

    @Test
    void assignUnassignedTaskRecordsNullPreviousAssignee() {
        Task task = stubTask();
        UUID userId = UUID.randomUUID();
        when(userRepository.findById(userId)).thenReturn(Optional.of(user(userId)));
        UserSummary assignee = stubReference(userId);

        Task result = taskService.assign(TASK_ID, userId);

        assertThat(result.getAssignedTo()).isSameAs(assignee);
        verify(taskEventService).assignmentChanged(task, null, userId);
    }

    @Test
    void reassignRecordsPreviousAssignee() {
        Task task = stubTask();
        UUID previousId = UUID.randomUUID();
        task.setAssignedTo(reference(previousId));
        UUID userId = UUID.randomUUID();
        when(userRepository.findById(userId)).thenReturn(Optional.of(user(userId)));
        UserSummary assignee = stubReference(userId);

        taskService.assign(TASK_ID, userId);

        assertThat(task.getAssignedTo()).isSameAs(assignee);
        verify(taskEventService).assignmentChanged(task, previousId, userId);
    }

    @Test
    void assignToSameUserIsNoOp() {
        Task task = stubTask();
        UUID userId = UUID.randomUUID();
        UserSummary current = reference(userId);
        task.setAssignedTo(current);

        Task result = taskService.assign(TASK_ID, userId);

        assertThat(result.getAssignedTo()).isSameAs(current);
        verifyNoInteractions(userRepository, userSummaryRepository, taskEventService);
        verifyNoInteractions(eventPublisher);
    }

    @Test
    void assignToUnknownUserThrows() {
        Task task = stubTask();
        UUID userId = UUID.randomUUID();
        when(userRepository.findById(userId)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> taskService.assign(TASK_ID, userId))
                .isInstanceOf(UserNotFoundException.class);

        assertThat(task.getAssignedTo()).isNull();
        verifyNoInteractions(userSummaryRepository, taskEventService);
        verifyNoInteractions(eventPublisher);
    }

    @ParameterizedTest
    @EnumSource(value = UserStatus.class, names = {"UNVERIFIED", "DISABLED"})
    void assignToInactiveUserThrowsAndKeepsCurrentAssignee(UserStatus status) {
        Task task = stubTask();
        UserSummary previous = reference(UUID.randomUUID());
        task.setAssignedTo(previous);
        UUID userId = UUID.randomUUID();
        when(userRepository.findById(userId)).thenReturn(Optional.of(userWithStatus(userId, status)));

        assertThatThrownBy(() -> taskService.assign(TASK_ID, userId))
                .isInstanceOf(AssigneeNotActiveException.class)
                .hasMessageContaining(userId.toString())
                .hasMessageContaining(status.name());

        assertThat(task.getAssignedTo()).isSameAs(previous);
        verifyNoInteractions(userSummaryRepository, taskEventService);
        verifyNoInteractions(eventPublisher);
    }

    @Test
    void reassignFromSoftDeletedAssigneeRecordsItsIdAsPrevious() {
        Task task = stubTask();
        UUID deletedId = UUID.randomUUID();
        task.setAssignedTo(softDeleted(deletedId));
        UUID userId = UUID.randomUUID();
        when(userRepository.findById(userId)).thenReturn(Optional.of(user(userId)));
        UserSummary assignee = stubReference(userId);

        taskService.assign(TASK_ID, userId);

        assertThat(task.getAssignedTo()).isSameAs(assignee);
        verify(taskEventService).assignmentChanged(task, deletedId, userId);
    }

    @Test
    void assignToSoftDeletedCurrentAssigneeIsNoOp() {
        Task task = stubTask();
        UUID deletedId = UUID.randomUUID();
        UserSummary deleted = softDeleted(deletedId);
        task.setAssignedTo(deleted);

        Task result = taskService.assign(TASK_ID, deletedId);

        assertThat(result).isSameAs(task);
        assertThat(task.getAssignedTo()).isSameAs(deleted);
        verifyNoInteractions(userRepository, userSummaryRepository, taskEventService);
        verifyNoInteractions(eventPublisher);
    }

    @Test
    void assignThrowsWhenTaskMissing() {
        stubMissingTask();

        assertThatThrownBy(() -> taskService.assign(TASK_ID, UUID.randomUUID()))
                .isInstanceOf(TaskNotFoundException.class);
    }

    @SuppressWarnings("unchecked")
    private final Root<Task> root = mock(Root.class);
    private final CriteriaQuery<?> query = mock(CriteriaQuery.class);
    private final CriteriaBuilder builder = mock(CriteriaBuilder.class);
    private final Path<Object> archivedAtPath = mock(Path.class);
    private final Path<Object> statusPath = mock(Path.class);
    private final Path<Object> assignedToPath = mock(Path.class);
    private final Path<Object> assignedToIdPath = mock(Path.class);
    private final Predicate archivedPredicate = mock(Predicate.class);
    private final Predicate statusPredicate = mock(Predicate.class);
    private final Predicate assigneePredicate = mock(Predicate.class);
    private final Predicate conjunction = mock(Predicate.class);
    private final Predicate combined = mock(Predicate.class);

    private Specification<Task> findAllAndCaptureSpecification(
            TaskStatus status, UUID assigneeId, boolean archived) {
        Pageable pageable = PageRequest.of(2, 10);
        Page<Task> page = new PageImpl<>(List.of(new Task()));
        when(taskRepository.findAll(any(Specification.class), eq(pageable))).thenReturn(page);

        assertThat(taskService.findAll(status, assigneeId, archived, pageable)).isSameAs(page);

        ArgumentCaptor<Specification<Task>> captor = ArgumentCaptor.captor();
        verify(taskRepository).findAll(captor.capture(), eq(pageable));
        return captor.getValue();
    }

    @Test
    void findAllWithoutFiltersKeepsOnlyActiveTasks() {
        Specification<Task> specification = findAllAndCaptureSpecification(null, null, false);
        doReturn(archivedAtPath).when(root).get("archivedAt");
        when(builder.isNull(archivedAtPath)).thenReturn(archivedPredicate);
        when(builder.conjunction()).thenReturn(conjunction);
        when(builder.and(archivedPredicate, conjunction, conjunction)).thenReturn(combined);

        assertThat(specification.toPredicate(root, query, builder)).isSameAs(combined);
        verify(root, never()).get("status");
        verify(root, never()).get("assignedTo");
    }

    @Test
    void findAllWithAllFiltersMatchesArchivedStatusAndAssigneeId() {
        UUID assigneeId = UUID.randomUUID();
        Specification<Task> specification = findAllAndCaptureSpecification(TaskStatus.DONE, assigneeId, true);
        doReturn(archivedAtPath).when(root).get("archivedAt");
        doReturn(statusPath).when(root).get("status");
        doReturn(assignedToPath).when(root).get("assignedTo");
        doReturn(assignedToIdPath).when(assignedToPath).get("id");
        when(builder.isNotNull(archivedAtPath)).thenReturn(archivedPredicate);
        when(builder.equal(statusPath, TaskStatus.DONE)).thenReturn(statusPredicate);
        when(builder.equal(assignedToIdPath, assigneeId)).thenReturn(assigneePredicate);
        when(builder.and(archivedPredicate, statusPredicate, assigneePredicate)).thenReturn(combined);

        assertThat(specification.toPredicate(root, query, builder)).isSameAs(combined);
        verify(root, never()).join(any(String.class));
    }

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
        verifyNoInteractions(eventPublisher);
    }

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
        verifyNoInteractions(eventPublisher);
    }

    private <E> E publishedEvent(Class<E> type) {
        ArgumentCaptor<E> captor = ArgumentCaptor.forClass(type);
        verify(eventPublisher).publishEvent(captor.capture());
        return captor.getValue();
    }

    @Test
    void createWithAssigneePublishesTaskAssigned() {
        UUID assigneeId = UUID.randomUUID();
        var dto = new CreateTaskDto("TASK-5", "Title", null, null, null, assigneeId);
        when(taskRepository.existsByReferenceIncludingDeleted("TASK-5")).thenReturn(false);
        when(currentUser.getId()).thenReturn(Optional.of(ACTOR_ID));
        when(userRepository.existsById(ACTOR_ID)).thenReturn(true);
        stubReference(ACTOR_ID);
        when(userRepository.findById(assigneeId)).thenReturn(Optional.of(user(assigneeId)));
        stubReference(assigneeId);
        when(taskRepository.save(any(Task.class))).thenAnswer(inv -> {
            Task task = inv.getArgument(0);
            task.setId(TASK_ID);
            return task;
        });

        taskService.create(dto);

        assertThat(publishedEvent(TaskAssigned.class))
                .isEqualTo(new TaskAssigned(TASK_ID, "TASK-5", "Title", assigneeId, ACTOR_ID));
    }

    @Test
    void createWithAssigneeAndNoAuthenticatedUserPublishesTaskAssignedWithoutActor() {
        UUID assigneeId = UUID.randomUUID();
        var dto = new CreateTaskDto("TASK-6", "Title", null, null, null, assigneeId);
        when(taskRepository.existsByReferenceIncludingDeleted("TASK-6")).thenReturn(false);
        when(currentUser.getId()).thenReturn(Optional.empty());
        when(userRepository.findById(assigneeId)).thenReturn(Optional.of(user(assigneeId)));
        stubReference(assigneeId);
        when(taskRepository.save(any(Task.class))).thenAnswer(inv -> inv.getArgument(0));

        taskService.create(dto);

        assertThat(publishedEvent(TaskAssigned.class))
                .isEqualTo(new TaskAssigned(null, "TASK-6", "Title", assigneeId, null));
    }

    @Test
    void assignUnassignedTaskPublishesOnlyTaskAssigned() {
        stubTask();
        UUID userId = UUID.randomUUID();
        when(userRepository.findById(userId)).thenReturn(Optional.of(user(userId)));
        stubReference(userId);
        when(currentUser.getId()).thenReturn(Optional.of(ACTOR_ID));

        taskService.assign(TASK_ID, userId);

        assertThat(publishedEvent(TaskAssigned.class))
                .isEqualTo(new TaskAssigned(TASK_ID, "TASK-1", "Title", userId, ACTOR_ID));
    }

    @Test
    void reassignPublishesTaskAssignedThenTaskUnassignedForThePreviousAssignee() {
        Task task = stubTask();
        UUID previousId = UUID.randomUUID();
        task.setAssignedTo(reference(previousId));
        UUID userId = UUID.randomUUID();
        when(userRepository.findById(userId)).thenReturn(Optional.of(user(userId)));
        stubReference(userId);
        when(currentUser.getId()).thenReturn(Optional.of(ACTOR_ID));

        taskService.assign(TASK_ID, userId);

        InOrder order = inOrder(eventPublisher);
        order.verify(eventPublisher).publishEvent(new TaskAssigned(TASK_ID, "TASK-1", "Title", userId, ACTOR_ID));
        order.verify(eventPublisher).publishEvent(new TaskUnassigned(TASK_ID, "TASK-1", "Title", previousId, ACTOR_ID));
        order.verifyNoMoreInteractions();
    }

    @Test
    void reassignFromSoftDeletedAssigneePublishesTaskUnassignedWithItsId() {
        Task task = stubTask();
        UUID deletedId = UUID.randomUUID();
        task.setAssignedTo(softDeleted(deletedId));
        UUID userId = UUID.randomUUID();
        when(userRepository.findById(userId)).thenReturn(Optional.of(user(userId)));
        stubReference(userId);
        when(currentUser.getId()).thenReturn(Optional.empty());

        taskService.assign(TASK_ID, userId);

        verify(eventPublisher).publishEvent(new TaskAssigned(TASK_ID, "TASK-1", "Title", userId, null));
        verify(eventPublisher).publishEvent(new TaskUnassigned(TASK_ID, "TASK-1", "Title", deletedId, null));
    }

    @Test
    void changeStatusToCancelledPublishesTaskCancelledWithoutReason() {
        Task task = stubTask();
        UUID assigneeId = UUID.randomUUID();
        task.setAssignedTo(reference(assigneeId));
        when(currentUser.getId()).thenReturn(Optional.of(ACTOR_ID));

        taskService.changeStatus(TASK_ID, TaskStatus.CANCELLED);

        assertThat(publishedEvent(TaskCancelled.class))
                .isEqualTo(new TaskCancelled(TASK_ID, "TASK-1", "Title", assigneeId, null, ACTOR_ID));
    }

    @Test
    void changeStatusToCancelledOnUnassignedTaskPublishesTaskCancelledWithoutAssignee() {
        stubTask();
        when(currentUser.getId()).thenReturn(Optional.empty());

        taskService.changeStatus(TASK_ID, TaskStatus.CANCELLED);

        assertThat(publishedEvent(TaskCancelled.class))
                .isEqualTo(new TaskCancelled(TASK_ID, "TASK-1", "Title", null, null, null));
    }

    @ParameterizedTest
    @EnumSource(value = TaskStatus.class, names = "CANCELLED", mode = EnumSource.Mode.EXCLUDE)
    void changeStatusToAnotherStatusPublishesNothing(TaskStatus target) {
        Task task = stubTask();
        task.setStatus(TaskStatus.CANCELLED);
        task.setAssignedTo(reference(UUID.randomUUID()));

        taskService.changeStatus(TASK_ID, target);

        verifyNoInteractions(eventPublisher);
    }

    @Test
    void changeStatusOfAlreadyCancelledTaskToCancelledPublishesNothing() {
        Task task = stubTask();
        task.setStatus(TaskStatus.CANCELLED);
        task.setAssignedTo(reference(UUID.randomUUID()));

        taskService.changeStatus(TASK_ID, TaskStatus.CANCELLED);

        verifyNoInteractions(eventPublisher);
    }

    @Test
    void cancelPublishesTaskCancelledWithReason() {
        Task task = stubTask();
        UUID assigneeId = UUID.randomUUID();
        task.setAssignedTo(reference(assigneeId));
        when(currentUser.getId()).thenReturn(Optional.of(ACTOR_ID));

        taskService.cancel(TASK_ID, "no longer needed");

        assertThat(publishedEvent(TaskCancelled.class))
                .isEqualTo(new TaskCancelled(TASK_ID, "TASK-1", "Title", assigneeId, "no longer needed", ACTOR_ID));
    }

    @Test
    void cancelUnassignedTaskPublishesTaskCancelledWithoutAssignee() {
        stubTask();
        when(currentUser.getId()).thenReturn(Optional.empty());

        taskService.cancel(TASK_ID, null);

        assertThat(publishedEvent(TaskCancelled.class))
                .isEqualTo(new TaskCancelled(TASK_ID, "TASK-1", "Title", null, null, null));
    }

    @Test
    void deletePublishesTaskDeletedWithTheAssignee() {
        Task task = stubTask();
        UUID assigneeId = UUID.randomUUID();
        task.setAssignedTo(reference(assigneeId));
        when(currentUser.getId()).thenReturn(Optional.of(ACTOR_ID));

        taskService.delete(TASK_ID);

        assertThat(publishedEvent(TaskDeleted.class))
                .isEqualTo(new TaskDeleted(TASK_ID, "TASK-1", "Title", assigneeId, ACTOR_ID));
    }

    @Test
    void deleteTaskOfSoftDeletedAssigneePublishesTaskDeletedWithItsId() {
        Task task = stubTask();
        UUID deletedId = UUID.randomUUID();
        task.setAssignedTo(softDeleted(deletedId));
        when(currentUser.getId()).thenReturn(Optional.empty());

        taskService.delete(TASK_ID);

        assertThat(publishedEvent(TaskDeleted.class))
                .isEqualTo(new TaskDeleted(TASK_ID, "TASK-1", "Title", deletedId, null));
    }

    @Test
    void deleteUnassignedTaskPublishesTaskDeletedWithoutAssignee() {
        stubTask();
        when(currentUser.getId()).thenReturn(Optional.of(ACTOR_ID));

        taskService.delete(TASK_ID);

        assertThat(publishedEvent(TaskDeleted.class))
                .isEqualTo(new TaskDeleted(TASK_ID, "TASK-1", "Title", null, ACTOR_ID));
    }

    @Test
    void updateArchiveAndUnarchivePublishNothing() {
        Task task = stubTask();
        task.setAssignedTo(reference(UUID.randomUUID()));

        taskService.update(TASK_ID, new UpdateTaskDto("New", null, null, null));
        taskService.archive(TASK_ID);
        taskService.unarchive(TASK_ID);

        verifyNoInteractions(eventPublisher);
    }
}
