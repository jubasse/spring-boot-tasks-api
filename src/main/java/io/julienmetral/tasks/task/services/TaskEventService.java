package io.julienmetral.tasks.task.services;

import io.julienmetral.tasks.identity.entities.User;
import io.julienmetral.tasks.identity.repositories.UserRepository;
import io.julienmetral.tasks.identity.security.CurrentUser;
import io.julienmetral.tasks.task.entities.Task;
import io.julienmetral.tasks.task.entities.TaskEvent;
import io.julienmetral.tasks.task.entities.TaskEventType;
import io.julienmetral.tasks.task.entities.TaskStatus;
import io.julienmetral.tasks.task.exceptions.TaskNotFoundException;
import io.julienmetral.tasks.task.repositories.TaskEventRepository;
import io.julienmetral.tasks.task.repositories.TaskRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.json.JsonMapper;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class TaskEventService {

    private final TaskRepository taskRepository;
    private final TaskEventRepository taskEventRepository;
    private final UserRepository userRepository;
    private final CurrentUser currentUser;
    private final JsonMapper jsonMapper;

    @Transactional(propagation = Propagation.MANDATORY)
    public void created(Task task) {
        record(
                task,
                TaskEventType.CREATED,
                null
        );
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public void updated(Task task) {
        record(
                task,
                TaskEventType.UPDATED,
                null
        );
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public void statusChanged(
            Task task,
            TaskStatus from,
            TaskStatus to
    ) {
        record(
                task,
                TaskEventType.STATUS_CHANGED,
                new StatusChangedPayload(
                        from,
                        to
                )
        );
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public void assignmentChanged(
            Task task,
            UUID fromUserId,
            UUID toUserId
    ) {
        record(
                task,
                TaskEventType.ASSIGNED,
                new AssignmentChangedPayload(
                        fromUserId,
                        toUserId
                )
        );
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public void archived(Task task) {
        record(
                task,
                TaskEventType.ARCHIVED,
                null
        );
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public void unarchived(Task task) {
        record(
                task,
                TaskEventType.UNARCHIVED,
                null
        );
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public void cancelled(
            Task task,
            TaskStatus from,
            String reason
    ) {
        record(
                task,
                TaskEventType.CANCELLED,
                new CancelledPayload(
                        from,
                        TaskStatus.CANCELLED,
                        reason
                )
        );
    }

    @Transactional(readOnly = true)
    public Page<TaskEvent> findAllByTaskId(
            UUID taskId,
            Pageable pageable
    ) {
        if (!taskRepository.existsById(taskId)) {
            throw new TaskNotFoundException(taskId);
        }

        return taskEventRepository
                .findAllByTaskIdOrderByOccurredAtDesc(
                        taskId,
                        pageable
                );
    }

    private void record(
            Task task,
            TaskEventType type,
            Object payload
    ) {
        TaskEvent event = new TaskEvent();

        event.setTask(task);
        event.setActor(getCurrentActor());
        event.setType(type);
        event.setOccurredAt(Instant.now());
        event.setPayload(serialize(payload));

        taskEventRepository.save(event);
    }

    private User getCurrentActor() {
        UUID userId = currentUser
                .getId()
                .orElseThrow(() ->
                        new IllegalStateException(
                                "No authenticated user"
                        )
                );

        return userRepository
                .findById(userId)
                .orElseThrow(() ->
                        new IllegalStateException(
                                "Authenticated user not found"
                        )
                );
    }

    private Map<String, Object> serialize(Object payload) {
        if (payload == null) {
            return null;
        }

        return jsonMapper.convertValue(
                payload,
                new TypeReference<>() {
                }
        );
    }

    private record StatusChangedPayload(
            TaskStatus from,
            TaskStatus to
    ) {
    }

    private record AssignmentChangedPayload(
            UUID fromUserId,
            UUID toUserId
    ) {
    }

    private record CancelledPayload(
            TaskStatus from,
            TaskStatus to,
            String reason
    ) {
    }
}