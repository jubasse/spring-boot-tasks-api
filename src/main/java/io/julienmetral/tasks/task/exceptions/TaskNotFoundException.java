package io.julienmetral.tasks.task.exceptions;

import java.util.UUID;

public class TaskNotFoundException extends RuntimeException {

    public TaskNotFoundException(UUID id) {
        super("Task not found with id: " + id);
    }

    public TaskNotFoundException(String reference) {
        super("Task not found with reference: " + reference);
    }
}
