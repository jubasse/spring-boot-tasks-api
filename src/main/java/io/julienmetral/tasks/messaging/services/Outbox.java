package io.julienmetral.tasks.messaging.services;

import io.julienmetral.tasks.messaging.entities.OutboxMessage;
import io.julienmetral.tasks.messaging.repositories.OutboxMessageRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.json.JsonMapper;

import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class Outbox {

    private final OutboxMessageRepository repository;
    private final OutboxRelay relay;
    private final JsonMapper jsonMapper;
    private final Clock clock;

    /**
     * Writes the message in the caller's transaction, so it exists only if the business change commits. It is
     * published right after the commit; if the broker cannot take it then, {@link OutboxRelayJob} retries later.
     * Delivery is at least once.
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public void enqueue(String queue, Object message) {
        Instant now = clock.instant();
        OutboxMessage row = new OutboxMessage();

        row.setQueue(queue);
        row.setType(message.getClass().getName());
        row.setPayload(jsonMapper.convertValue(message, new TypeReference<Map<String, Object>>() {
        }));
        row.setCreatedAt(now);
        row.setNextAttemptAt(now);

        UUID id = repository.save(row).getId();

        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                relay.publishNow(List.of(id));
            }
        });
    }
}
