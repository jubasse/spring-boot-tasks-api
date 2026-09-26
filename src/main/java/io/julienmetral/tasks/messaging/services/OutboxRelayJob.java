package io.julienmetral.tasks.messaging.services;

import io.julienmetral.tasks.config.OutboxProperties;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Slf4j
@Component
@RequiredArgsConstructor
public class OutboxRelayJob {

    private final OutboxRelay relay;
    private final OutboxProperties properties;

    /** Catches the messages that could not be published right after their commit (broker down, crash). */
    @Scheduled(fixedDelayString = "${messaging.outbox.poll-interval}")
    public void publishDue() {
        int published;

        do {
            published = relay.publishDue();
        } while (published == properties.batchSize());
    }

    @Scheduled(cron = "${messaging.outbox.purge-cron}")
    public void deletePublished() {
        int deleted = relay.deletePublished();

        if (deleted > 0) {
            log.info("Outbox: {} published messages deleted", deleted);
        }
    }
}
