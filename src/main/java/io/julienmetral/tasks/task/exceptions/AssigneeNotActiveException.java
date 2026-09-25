package io.julienmetral.tasks.task.exceptions;

import io.julienmetral.tasks.identity.entities.UserStatus;

import java.util.UUID;

/** Tasks can only be assigned to enabled users with a verified email. */
public class AssigneeNotActiveException extends RuntimeException {

    public AssigneeNotActiveException(UUID userId, UserStatus status) {
        super("User " + userId + " cannot be assigned a task: account is " + status);
    }
}
