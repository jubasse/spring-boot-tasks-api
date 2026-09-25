package io.julienmetral.tasks.identity.exceptions;

/** Unknown, expired or already used verification token. The message stays generic on purpose. */
public class InvalidEmailVerificationTokenException extends RuntimeException {

    public InvalidEmailVerificationTokenException() {
        super("The email verification token is invalid, expired or already used");
    }
}
