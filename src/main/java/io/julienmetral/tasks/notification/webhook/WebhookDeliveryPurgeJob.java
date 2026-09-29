package io.julienmetral.tasks.notification.webhook;

import io.julienmetral.tasks.config.ScheduledJobLocks;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBooleanProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Slf4j
@Component
@RequiredArgsConstructor
@ConditionalOnBooleanProperty(name = "webhooks.purge-enabled", matchIfMissing = true)
class WebhookDeliveryPurgeJob {

    private final WebhookDeliveryService deliveryService;

    // Public, like every locked job method: the lock is taken by a proxy around the bean
    @Scheduled(cron = "${webhooks.purge-cron}")
    @SchedulerLock(name = ScheduledJobLocks.WEBHOOK_DELIVERY_PURGE, lockAtMostFor = "PT1H", lockAtLeastFor = "PT5M")
    public void purgeOldDeliveries() {
        int deleted = deliveryService.purge();

        if (deleted > 0) {
            log.info("Webhook deliveries purged: {}", deleted);
        }
    }
}
