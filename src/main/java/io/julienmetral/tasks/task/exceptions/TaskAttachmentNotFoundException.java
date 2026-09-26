package io.julienmetral.tasks.task.exceptions;

import java.util.UUID;

public class TaskAttachmentNotFoundException extends RuntimeException {

    public TaskAttachmentNotFoundException(UUID attachmentId) {
        super("Attachment not found with id: " + attachmentId);
    }
}
