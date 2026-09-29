package io.julienmetral.tasks.realtime.stomp;

import io.julienmetral.tasks.identity.entities.UserStatus;
import io.julienmetral.tasks.identity.security.CurrentUser;
import io.julienmetral.tasks.identity.security.UserStatusLookup;
import io.julienmetral.tasks.task.repositories.TaskRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.security.authorization.AuthorizationDecision;
import org.springframework.security.authorization.AuthorizationManager;
import org.springframework.security.authorization.AuthorizationResult;
import org.springframework.security.core.Authentication;
import org.springframework.security.messaging.access.intercept.MessageAuthorizationContext;
import org.springframework.stereotype.Component;

import java.util.Optional;
import java.util.UUID;
import java.util.function.Supplier;

/** A subscription to a task's room: the rule of the task endpoints (an active account) and a task that exists. */
@Component
@RequiredArgsConstructor
class TaskRoomAuthorization implements AuthorizationManager<MessageAuthorizationContext<?>> {

    static final String TASK_ID = "taskId";

    private final CurrentUser currentUser;
    private final UserStatusLookup userStatusLookup;
    private final TaskRepository taskRepository;

    @Override
    public AuthorizationResult authorize(
            Supplier<? extends Authentication> authentication,
            MessageAuthorizationContext<?> context
    ) {
        Optional<UUID> userId = currentUser.getId(authentication.get());
        Optional<UUID> taskId = parse(context.getVariables().get(TASK_ID));

        return new AuthorizationDecision(userId.isPresent()
                && taskId.isPresent()
                && userStatusLookup.statusOf(userId.get()) == UserStatus.ACTIVE
                && taskRepository.existsById(taskId.get()));
    }

    private static Optional<UUID> parse(String taskId) {
        try {
            return Optional.ofNullable(taskId).map(UUID::fromString);
        } catch (IllegalArgumentException notAnId) {
            return Optional.empty();
        }
    }
}
