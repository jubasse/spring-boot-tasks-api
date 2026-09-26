package io.julienmetral.tasks.task.exceptions;

import java.util.UUID;

public class TaskCommentNotFoundException extends RuntimeException {

    public TaskCommentNotFoundException(UUID commentId) {
        super("Comment not found with id: " + commentId);
    }
}
