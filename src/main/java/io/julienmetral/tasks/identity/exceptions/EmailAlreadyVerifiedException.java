package io.julienmetral.tasks.identity.exceptions;

public class EmailAlreadyVerifiedException extends RuntimeException {

    public EmailAlreadyVerifiedException() {
        super("The email address is already verified");
    }
}
