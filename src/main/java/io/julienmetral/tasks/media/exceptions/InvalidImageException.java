package io.julienmetral.tasks.media.exceptions;

public class InvalidImageException extends RuntimeException {

    public InvalidImageException(String detail) {
        super("The image cannot be used: " + detail);
    }
}
