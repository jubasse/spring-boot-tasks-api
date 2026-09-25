package io.julienmetral.tasks.task.services;

import io.julienmetral.tasks.identity.entities.User;
import io.julienmetral.tasks.identity.entities.UserStatus;
import io.julienmetral.tasks.identity.exceptions.UserNotFoundException;
import io.julienmetral.tasks.identity.repositories.UserRepository;
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
import lombok.RequiredArgsConstructor;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class TaskService {

    private final TaskRepository taskRepository;
    private final TaskEventService taskEventService;
    private final UserRepository userRepository;
    private final CurrentUser currentUser;
    private final ApplicationEventPublisher eventPublisher;

    @Transactional
    public Task create(CreateTaskDto dto) {

        if (taskRepository.existsByReferenceIncludingDeleted(dto.reference())) {
            throw new TaskReferenceAlreadyExistsException(dto.reference());
        }

        Task task = new Task();

        task.setReference(dto.reference());
        task.setTitle(dto.title());
        task.setDescription(dto.description());
        task.setPriority(
            dto.priority() != null
                ? dto.priority()
                : TaskPriority.MEDIUM
        );
        task.setDueAt(dto.dueAt());

        currentUser.getId()
                .flatMap(userRepository::findById)
                .ifPresent(task::setCreatedBy);

        if (dto.assignedTo() != null) {
            task.setAssignedTo(getAssignableUser(dto.assignedTo()));
        }

        Task savedTask = taskRepository.save(task);

        taskEventService.created(savedTask);

        if (savedTask.currentAssigneeId() != null) {
            eventPublisher.publishEvent(new TaskAssigned(
                    savedTask.getId(), savedTask.getReference(), savedTask.getTitle(),
                    savedTask.currentAssigneeId(), actorId()
            ));
        }

        return savedTask;
    }

    /**
     * @param status     only tasks in this status, or all statuses when null
     * @param assigneeId only tasks assigned to this user, or all tasks when null
     * @param archived   archived tasks when true, active ones otherwise
     */
    @Transactional(readOnly = true)
    public Page<Task> findAll(
            TaskStatus status,
            UUID assigneeId,
            boolean archived,
            Pageable pageable
    ) {
        Specification<Task> specification = (root, query, builder) -> builder.and(
                archived
                        ? builder.isNotNull(root.get("archivedAt"))
                        : builder.isNull(root.get("archivedAt")),
                status == null
                        ? builder.conjunction()
                        : builder.equal(root.get("status"), status),
                // The read-only id column avoids joining users, which @SoftDelete would filter
                assigneeId == null
                        ? builder.conjunction()
                        : builder.equal(root.get("assignedToId"), assigneeId)
        );

        return taskRepository.findAll(specification, pageable);
    }

    @Transactional(readOnly = true)
    public Task findById(UUID id) {
        return taskRepository.findById(id)
            .orElseThrow(() -> new TaskNotFoundException(id));
    }

    @Transactional(readOnly = true)
    public Task findByReference(String reference) {
        return taskRepository.findByReference(reference)
            .orElseThrow(() -> new TaskNotFoundException(reference));
    }

    @Transactional
    public Task update(UUID id, UpdateTaskDto dto) {
        Task task = getTask(id);

        if (dto.title() != null) {
            task.setTitle(dto.title());
        }

        if (dto.description() != null) {
            task.setDescription(dto.description());
        }

        if (dto.priority() != null) {
            task.setPriority(dto.priority());
        }

        if (dto.dueAt() != null) {
            task.setDueAt(dto.dueAt());
        }

        taskEventService.updated(task);

        return task;
    }

    @Transactional
    public Task changeStatus(UUID id, TaskStatus status) {
        Task task = getTask(id);
        Instant now = Instant.now();

        if (task.getStatus() == status) {
            return task;
        }

        TaskStatus previousStatus =
                task.getStatus();

        task.setStatus(status);

        switch (status) {
            case DONE -> {
                task.setCompletedAt(now);
                task.setCancelledAt(null);
                task.setCancelledReason(null);
            }

            case CANCELLED -> {
                task.setCancelledAt(now);
                task.setCompletedAt(null);
            }

            default -> {
                task.setCompletedAt(null);
                task.setCancelledAt(null);
                task.setCancelledReason(null);
            }
        }

        taskEventService.statusChanged(
                task,
                previousStatus,
                status
        );

        if (status == TaskStatus.CANCELLED) {
            eventPublisher.publishEvent(new TaskCancelled(
                    task.getId(), task.getReference(), task.getTitle(),
                    task.currentAssigneeId(), null, actorId()
            ));
        }

        return task;
    }

    @Transactional
    public Task assign(UUID id, UUID userId) {
        Task task = getTask(id);

        UUID currentAssignedToId = task.currentAssigneeId();

        if (Objects.equals(currentAssignedToId, userId)) {
            return task;
        }

        task.setAssignedTo(getAssignableUser(userId));

        taskEventService.assignmentChanged(
                task,
                currentAssignedToId,
                userId
        );

        eventPublisher.publishEvent(new TaskAssigned(
                task.getId(), task.getReference(), task.getTitle(), userId, actorId()
        ));

        if (currentAssignedToId != null) {
            eventPublisher.publishEvent(new TaskUnassigned(
                    task.getId(), task.getReference(), task.getTitle(), currentAssignedToId, actorId()
            ));
        }

        return task;
    }

    @Transactional
    public Task archive(UUID id) {
        Task task = getTask(id);

        if (task.getArchivedAt() != null) {
            return task;
        }

        task.setArchivedAt(Instant.now());

        taskEventService.archived(task);

        return task;
    }

    @Transactional
    public Task unarchive(UUID id) {
        Task task = getTask(id);

        if (task.getArchivedAt() == null) {
            return task;
        }

        task.setArchivedAt(null);

        taskEventService.unarchived(task);

        return task;
    }

    @Transactional
    public Task cancel(UUID id, String reason) {
        Task task = getTask(id);

        TaskStatus previousStatus =
                task.getStatus();

        task.setStatus(TaskStatus.CANCELLED);
        task.setCancelledAt(Instant.now());
        task.setCancelledReason(reason);

        task.setCompletedAt(null);

        taskEventService.cancelled(
                task,
                previousStatus,
                reason
        );

        eventPublisher.publishEvent(new TaskCancelled(
                task.getId(), task.getReference(), task.getTitle(),
                task.currentAssigneeId(), reason, actorId()
        ));

        return task;
    }

    @Transactional
    public void delete(UUID id) {
        Task task = getTask(id);

        taskRepository.delete(task);

        eventPublisher.publishEvent(new TaskDeleted(
                task.getId(), task.getReference(), task.getTitle(), task.currentAssigneeId(), actorId()
        ));
    }

    private UUID actorId() {
        return currentUser
                .getId()
                .orElse(null);
    }

    // Only enabled users with a verified email can work on tasks, so only they can be assigned
    private User getAssignableUser(UUID userId) {
        User user = userRepository
                .findById(userId)
                .orElseThrow(() -> new UserNotFoundException(userId));

        UserStatus status = UserStatus.of(user);

        if (status != UserStatus.ACTIVE) {
            throw new AssigneeNotActiveException(userId, status);
        }

        return user;
    }

    private Task getTask(UUID id) {
        return taskRepository.findById(id)
            .orElseThrow(() -> new TaskNotFoundException(id));
    }
}