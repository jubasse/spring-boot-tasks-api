package io.julienmetral.tasks.task.security;

import io.julienmetral.tasks.identity.security.CurrentUser;
import io.julienmetral.tasks.task.repositories.TaskAttachmentRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Component;

import java.util.UUID;

@Component("taskAttachmentAuthorization")
@RequiredArgsConstructor
public class TaskAttachmentAuthorization {

    private final CurrentUser currentUser;
    private final TaskAttachmentRepository attachmentRepository;

    public boolean currentUserUploaded(UUID attachmentId, Authentication authentication) {
        return currentUser
                .getId(authentication)
                .map(userId -> attachmentRepository.existsByIdAndMediaUploadedById(attachmentId, userId))
                .orElse(false);
    }
}
