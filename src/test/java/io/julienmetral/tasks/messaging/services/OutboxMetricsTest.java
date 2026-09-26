package io.julienmetral.tasks.messaging.services;

import io.julienmetral.tasks.messaging.repositories.OutboxMessageRepository;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class OutboxMetricsTest {

    private static final Instant NOW = Instant.parse("2026-03-10T08:00:00Z");

    private final SimpleMeterRegistry registry = new SimpleMeterRegistry();

    @Mock
    private OutboxMessageRepository repository;

    @BeforeEach
    void bind() {
        new OutboxMetrics(repository, Clock.fixed(NOW, ZoneOffset.UTC)).bindTo(registry);
    }

    @Test
    void bindingDoesNotQueryTheDatabase() {
        verifyNoInteractions(repository);
    }

    @Test
    void pendingGaugeIsTheNumberOfUnpublishedMessages() {
        when(repository.countByPublishedAtIsNull()).thenReturn(7L);

        assertThat(pending().value()).isEqualTo(7.0);
    }

    @Test
    void pendingGaugeIsReadAgainOnEveryScrape() {
        when(repository.countByPublishedAtIsNull()).thenReturn(7L, 2L);

        assertThat(pending().value()).isEqualTo(7.0);
        assertThat(pending().value()).isEqualTo(2.0);
    }

    @Test
    void pendingGaugeIsZeroWhenEverythingIsPublished() {
        when(repository.countByPublishedAtIsNull()).thenReturn(0L);

        assertThat(pending().value()).isZero();
    }

    @Test
    void oldestPendingAgeIsTheSecondsSinceTheOldestUnpublishedMessageWasWritten() {
        when(repository.oldestUnpublishedCreatedAt())
                .thenReturn(Optional.of(NOW.minus(Duration.ofMinutes(3)).minusMillis(250)));

        assertThat(oldestPendingAge().value()).isEqualTo(180.25);
    }

    @Test
    void oldestPendingAgeIsZeroWhenNothingIsPending() {
        when(repository.oldestUnpublishedCreatedAt()).thenReturn(Optional.empty());

        assertThat(oldestPendingAge().value()).isZero();
    }

    @Test
    void oldestPendingAgeIsReadAgainOnEveryScrape() {
        when(repository.oldestUnpublishedCreatedAt())
                .thenReturn(Optional.of(NOW.minusSeconds(40)), Optional.empty());

        assertThat(oldestPendingAge().value()).isEqualTo(40.0);
        assertThat(oldestPendingAge().value()).isZero();
    }

    @Test
    void oldestPendingAgeIsMeasuredInSeconds() {
        assertThat(oldestPendingAge().getId().getBaseUnit()).isEqualTo("seconds");
    }

    private Gauge pending() {
        return registry.get("outbox.messages.pending").gauge();
    }

    private Gauge oldestPendingAge() {
        return registry.get("outbox.messages.oldest.pending.age").gauge();
    }
}
