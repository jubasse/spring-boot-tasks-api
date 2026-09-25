package io.julienmetral.tasks.task.security;

import io.julienmetral.tasks.identity.security.CurrentUser;
import io.julienmetral.tasks.task.repositories.TaskRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Component;

import java.util.UUID;

@Component("taskAuthorization")
@RequiredArgsConstructor
public class TaskAuthorization {

    private final CurrentUser currentUser;
    private final TaskRepository taskRepository;

    public boolean currentUserIsAssignedTo(
            UUID taskId,
            Authentication authentication
    ) {
        return currentUser
                .getId(authentication)
                .map(userId ->
                        taskRepository.existsByIdAndAssignedToId(
                                taskId,
                                userId
                        )
                )
                .orElse(false);
    }
}