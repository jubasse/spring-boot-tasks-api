package io.julienmetral.tasks.notification.webhook;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBooleanProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Slf4j
@Component
@RequiredArgsConstructor
@ConditionalOnBooleanProperty(name = "webhooks.retry-enabled", matchIfMissing = true)
class WebhookRetryJob {

    private final WebhookDeliveryService deliveryService;

    @Scheduled(fixedDelayString = "${webhooks.retry-poll-interval}")
    void enqueueDueRetries() {
        int queued = deliveryService.enqueueDue();

        if (queued > 0) {
            log.info("Webhook retries queued: {}", queued);
        }
    }
}
