package io.julienmetral.tasks.notification.webhook;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBooleanProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Slf4j
@Component
@RequiredArgsConstructor
@ConditionalOnBooleanProperty(name = "webhooks.purge-enabled", matchIfMissing = true)
class WebhookDeliveryPurgeJob {

    private final WebhookDeliveryService deliveryService;

    @Scheduled(cron = "${webhooks.purge-cron}")
    void purgeOldDeliveries() {
        int deleted = deliveryService.purge();

        if (deleted > 0) {
            log.info("Webhook deliveries purged: {}", deleted);
        }
    }
}
