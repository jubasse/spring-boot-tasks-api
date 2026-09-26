package io.julienmetral.tasks.media.exceptions;

public class AntivirusUnavailableException extends RuntimeException {

    public AntivirusUnavailableException(Throwable cause) {
        super("The antivirus is temporarily unavailable", cause);
    }

    public AntivirusUnavailableException(String detail) {
        super("The antivirus is temporarily unavailable: " + detail);
    }
}
