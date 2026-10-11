package io.julienmetral.tasks.messaging.services;

import io.julienmetral.tasks.config.OutboxProperties;
import lombok.RequiredArgsConstructor;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class OutboxRelayJob {

    private final OutboxRelay relay;
    private final OutboxProperties properties;

    /**
     * Catches the messages that could not be published right after their commit (broker down,
     * crash). Every instance polls, without a scheduler lock: {@code FOR UPDATE SKIP LOCKED} shares
     * the backlog between them. The first poll waits one interval too: polling at startup, a test
     * context would publish the fresh rows of another context sharing the database to its own
     * broker.
     */
    @Scheduled(
            fixedDelayString = "${messaging.outbox.poll-interval}",
            initialDelayString = "${messaging.outbox.poll-interval}")
    public void publishDue() {
        int published;

        do {
            published = relay.publishDue();
        } while (published == properties.batchSize());
    }
}
