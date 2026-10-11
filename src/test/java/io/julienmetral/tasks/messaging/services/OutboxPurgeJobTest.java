package io.julienmetral.tasks.messaging.services;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith({MockitoExtension.class, OutputCaptureExtension.class})
class OutboxPurgeJobTest {

    @Mock
    private OutboxRelay relay;

    @InjectMocks
    private OutboxPurgeJob job;

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
