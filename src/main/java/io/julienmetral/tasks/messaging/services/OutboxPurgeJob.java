package io.julienmetral.tasks.messaging.services;

import io.julienmetral.tasks.config.ScheduledJobLocks;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBooleanProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

// Apart from OutboxRelayJob, so that switching the purge off leaves the poller running
@Slf4j
@Component
@RequiredArgsConstructor
@ConditionalOnBooleanProperty(name = "messaging.outbox.purge-enabled", matchIfMissing = true)
public class OutboxPurgeJob {

    private final OutboxRelay relay;

    @Scheduled(cron = "${messaging.outbox.purge-cron}")
    @SchedulerLock(
            name = ScheduledJobLocks.OUTBOX_PURGE,
            lockAtMostFor = "PT30M",
            lockAtLeastFor = "PT1M")
    public void deletePublished() {
        int deleted = relay.deletePublished();

        if (deleted > 0) {
            log.info("Outbox: {} published messages deleted", deleted);
        }
    }
}
