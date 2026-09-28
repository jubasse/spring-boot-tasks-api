package io.julienmetral.tasks.notification.exceptions;

public class WebhookNotSignedException extends RuntimeException {

    public WebhookNotSignedException() {
        super("A Slack webhook has no signing secret: Slack messages are not signed");
    }
}
