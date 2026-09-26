package io.julienmetral.tasks.media.exceptions;

public class InfectedMediaException extends RuntimeException {

    public InfectedMediaException(String threat) {
        super("The file was rejected by the antivirus: " + threat);
    }
}
