package io.julienmetral.tasks.notification.webhook;

import lombok.RequiredArgsConstructor;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
class WebhookDeliveryListener {

    private final WebhookDeliveryService deliveryService;

    @RabbitListener(queues = WebhookQueues.DELIVER, containerFactory = WebhookQueues.LISTENER_FACTORY)
    void deliver(WebhookDeliveryRequested request) {
        deliveryService.deliver(request.deliveryId());
    }
}
