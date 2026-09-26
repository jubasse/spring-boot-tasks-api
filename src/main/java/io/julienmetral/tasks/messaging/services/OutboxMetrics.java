package io.julienmetral.tasks.messaging.services;

import io.julienmetral.tasks.messaging.repositories.OutboxMessageRepository;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.binder.MeterBinder;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Duration;

/**
 * Backlog of the outbox, read from the database on every scrape. A growing pending count or an old oldest message
 * means RabbitMQ has been unreachable, or refuses the messages, for a while.
 */
@Component
@RequiredArgsConstructor
public class OutboxMetrics implements MeterBinder {

    private final OutboxMessageRepository repository;
    private final Clock clock;

    @Override
    public void bindTo(MeterRegistry registry) {
        Gauge.builder("outbox.messages.pending", repository, OutboxMessageRepository::countByPublishedAtIsNull)
                .description("Messages written to the outbox and not yet confirmed by RabbitMQ")
                .register(registry);

        Gauge.builder("outbox.messages.oldest.pending.age", this, OutboxMetrics::oldestPendingAgeSeconds)
                .description("Age of the oldest message not yet confirmed by RabbitMQ, 0 when there is none")
                .baseUnit("seconds")
                .register(registry);
    }

    private double oldestPendingAgeSeconds() {
        return repository.oldestUnpublishedCreatedAt()
                .map(createdAt -> Duration.between(createdAt, clock.instant()).toMillis() / 1000.0)
                .orElse(0.0);
    }
}
