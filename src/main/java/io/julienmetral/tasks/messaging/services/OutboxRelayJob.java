package io.julienmetral.tasks.messaging.services;

import io.julienmetral.tasks.config.OutboxProperties;
import io.julienmetral.tasks.config.ScheduledJobLocks;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Slf4j
@Component
@RequiredArgsConstructor
public class OutboxRelayJob {

    private final OutboxRelay relay;
    private final OutboxProperties properties;

    /**
     * Catches the messages that could not be published right after their commit (broker down, crash). Every instance
     * polls, without a scheduler lock: {@code FOR UPDATE SKIP LOCKED} shares the backlog between them.
     */
    @Scheduled(fixedDelayString = "${messaging.outbox.poll-interval}")
    public void publishDue() {
        int published;

        do {
            published = relay.publishDue();
        } while (published == properties.batchSize());
    }

    @Scheduled(cron = "${messaging.outbox.purge-cron}")
    @SchedulerLock(name = ScheduledJobLocks.OUTBOX_PURGE, lockAtMostFor = "PT30M", lockAtLeastFor = "PT1M")
    public void deletePublished() {
        int deleted = relay.deletePublished();

        if (deleted > 0) {
            log.info("Outbox: {} published messages deleted", deleted);
        }
    }
}
