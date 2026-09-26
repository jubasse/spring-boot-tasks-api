package io.julienmetral.tasks.task.exceptions;

import io.julienmetral.tasks.identity.entities.UserStatus;

import java.util.UUID;

/** Only existing, active users can be mentioned, like only they can be assigned a task. */
public class InvalidMentionException extends RuntimeException {

    private InvalidMentionException(String message) {
        super(message);
    }

    public static InvalidMentionException unknownUser(UUID userId) {
        return new InvalidMentionException("User " + userId + " cannot be mentioned: no such user");
    }

    public static InvalidMentionException inactiveUser(UUID userId, UserStatus status) {
        return new InvalidMentionException("User " + userId + " cannot be mentioned: account is " + status);
    }
}
