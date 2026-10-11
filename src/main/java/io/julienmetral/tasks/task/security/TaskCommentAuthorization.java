package io.julienmetral.tasks.task.security;

import io.julienmetral.tasks.identity.security.CurrentUser;
import io.julienmetral.tasks.task.repositories.TaskCommentRepository;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Component;

@Component("taskCommentAuthorization")
@RequiredArgsConstructor
public class TaskCommentAuthorization {

    private final CurrentUser currentUser;
    private final TaskCommentRepository commentRepository;

    public boolean currentUserWrote(UUID commentId, Authentication authentication) {
        return currentUser
                .getId(authentication)
                .map(userId -> commentRepository.existsByIdAndAuthorId(commentId, userId))
                .orElse(false);
    }
}
