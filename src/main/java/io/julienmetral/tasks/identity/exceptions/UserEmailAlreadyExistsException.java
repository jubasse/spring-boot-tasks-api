package io.julienmetral.tasks.identity.exceptions;

public class UserEmailAlreadyExistsException extends RuntimeException {

    public UserEmailAlreadyExistsException(String email) {
        super("User already exists with email: " + email);
    }
}
