package io.julienmetral.tasks.media.exceptions;

public class StorageUnavailableException extends RuntimeException {

    public StorageUnavailableException(Throwable cause) {
        super("File storage is temporarily unavailable", cause);
    }
}
