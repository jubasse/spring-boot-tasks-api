package io.julienmetral.tasks.notification.exceptions;

public class WebhookLimitReachedException extends RuntimeException {

    public WebhookLimitReachedException(int max) {
        super("A user can declare at most " + max + " webhooks");
    }
}
