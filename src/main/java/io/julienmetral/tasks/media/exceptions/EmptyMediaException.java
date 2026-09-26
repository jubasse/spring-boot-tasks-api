package io.julienmetral.tasks.media.exceptions;

public class EmptyMediaException extends RuntimeException {

    public EmptyMediaException() {
        super("The file is empty");
    }
}
