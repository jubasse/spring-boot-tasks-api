package io.julienmetral.tasks.notification.exceptions;

import java.util.UUID;

public class WebhookDeliveryNotFoundException extends RuntimeException {

    public WebhookDeliveryNotFoundException(UUID deliveryId) {
        super("Webhook delivery not found with id: " + deliveryId);
    }
}
