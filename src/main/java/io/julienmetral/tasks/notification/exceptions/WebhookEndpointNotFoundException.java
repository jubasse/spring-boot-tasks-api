package io.julienmetral.tasks.notification.exceptions;

import java.util.UUID;

public class WebhookEndpointNotFoundException extends RuntimeException {

    public WebhookEndpointNotFoundException(UUID webhookId) {
        super("Webhook not found with id: " + webhookId);
    }
}
