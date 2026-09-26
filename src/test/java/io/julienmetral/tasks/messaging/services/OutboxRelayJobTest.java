package io.julienmetral.tasks.messaging.services;

import io.julienmetral.tasks.config.OutboxProperties;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

@ExtendWith({MockitoExtension.class, OutputCaptureExtension.class})
class OutboxRelayJobTest {

    private static final int BATCH_SIZE = 3;

    @Mock
    private OutboxRelay relay;

    private OutboxRelayJob job;

    @BeforeEach
    void setUp() {
        OutboxProperties properties = new OutboxProperties(
                Duration.ofSeconds(5),
                BATCH_SIZE,
                Duration.ofMinutes(10),
                Duration.ofSeconds(5),
                Duration.ofDays(7),
                "0 0 * * * *"
        );

        job = new OutboxRelayJob(relay, properties);
    }

    @Test
    void publishDueStopsAfterABatchThatIsNotFull() {
        when(relay.publishDue()).thenReturn(2);

        job.publishDue();

        verify(relay).publishDue();
        verifyNoMoreInteractions(relay);
    }

    @Test
    void publishDueWithNothingDueRunsASingleBatch() {
        when(relay.publishDue()).thenReturn(0);

        job.publishDue();

        verify(relay, times(1)).publishDue();
    }

    @Test
    void publishDueKeepsPublishingWhileBatchesAreFull() {
        when(relay.publishDue()).thenReturn(BATCH_SIZE, BATCH_SIZE, 1);

        job.publishDue();

        verify(relay, times(3)).publishDue();
    }

    @Test
    void publishDueStopsAfterAFullBatchFollowedByAnEmptyOne() {
        when(relay.publishDue()).thenReturn(BATCH_SIZE, 0);

        job.publishDue();

        verify(relay, times(2)).publishDue();
    }

    @Test
    void deletePublishedLogsHowManyRowsWereDeleted(CapturedOutput output) {
        when(relay.deletePublished()).thenReturn(4);

        job.deletePublished();

        verify(relay).deletePublished();
        assertThat(output).contains("Outbox: 4 published messages deleted");
    }

    @Test
    void deletePublishedLogsNothingWhenNothingWasDeleted(CapturedOutput output) {
        when(relay.deletePublished()).thenReturn(0);

        job.deletePublished();

        assertThat(output).doesNotContain("published messages deleted");
    }
}
