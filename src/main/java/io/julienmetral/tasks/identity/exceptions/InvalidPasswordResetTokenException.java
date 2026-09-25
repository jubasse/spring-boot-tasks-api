package io.julienmetral.tasks.identity.exceptions;

/** Unknown, expired or already used reset token, or an account that can no longer sign in. Generic on purpose. */
public class InvalidPasswordResetTokenException extends RuntimeException {

    public InvalidPasswordResetTokenException() {
        super("The password reset token is invalid, expired or already used");
    }
}
