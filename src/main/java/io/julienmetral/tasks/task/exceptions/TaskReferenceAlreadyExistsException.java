package io.julienmetral.tasks.task.exceptions;

public class TaskReferenceAlreadyExistsException extends RuntimeException {

    public TaskReferenceAlreadyExistsException(String reference) {
        super("Task reference already exists: " + reference);
    }
}
