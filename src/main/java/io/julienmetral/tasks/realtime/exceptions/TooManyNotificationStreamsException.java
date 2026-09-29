package io.julienmetral.tasks.realtime.exceptions;

public class TooManyNotificationStreamsException extends RuntimeException {

    public TooManyNotificationStreamsException(int maxPerUser) {
        super("Too many open notification streams: at most " + maxPerUser + " per user");
    }
}
