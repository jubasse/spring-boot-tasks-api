package io.julienmetral.tasks.identity.exceptions;

/** Unknown, expired, revoked or replayed refresh token. The message stays generic on purpose. */
public class InvalidRefreshTokenException extends RuntimeException {

    public InvalidRefreshTokenException() {
        super("The refresh token is invalid, expired or revoked");
    }
}
