package io.julienmetral.tasks.notification.exceptions;

public class WebhookUrlNotAllowedException extends RuntimeException {

    public WebhookUrlNotAllowedException(String reason) {
        super(reason);
    }
}
