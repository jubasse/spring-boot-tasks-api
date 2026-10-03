package io.julienmetral.tasks.export.services;

import io.julienmetral.tasks.export.ExportProperties;
import io.julienmetral.tasks.export.repositories.BatchMetadataQueries;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith({MockitoExtension.class, OutputCaptureExtension.class})
class DataExportPurgeJobTest {

    private static final Instant NOW = Instant.parse("2030-01-31T04:30:00Z");

    private static final Duration HISTORY_RETENTION = Duration.ofDays(30);

    @Mock
    private DataExportService exportService;

    @Mock
    private BatchMetadataQueries batchMetadataQueries;

    private DataExportPurgeJob job;

    @BeforeEach
    void setUp() {
        ExportProperties properties = new ExportProperties(
                "https://app.example/exports",
                Duration.ofDays(7),
                HISTORY_RETENTION,
                500,
                Duration.ofMinutes(15),
                3,
                true,
                "0 30 4 * * *",
                true,
                Duration.ofMinutes(5),
                1000
        );
        job = new DataExportPurgeJob(exportService, batchMetadataQueries, properties, Clock.fixed(NOW, ZoneOffset.UTC));
    }

    @Test
    void purgesTheFilesThenTheBatchHistoryOlderThanTheHistoryRetention(CapturedOutput output) {
        when(exportService.purge()).thenReturn(2);
        when(batchMetadataQueries.deleteExecutionsEndedBefore(any())).thenReturn(5);

        job.purge();

        verify(exportService).purge();
        verify(batchMetadataQueries).deleteExecutionsEndedBefore(NOW.minus(HISTORY_RETENTION));
        assertThat(output).contains("Exports purged: 2 expired files, 5 Spring Batch executions");
    }

    @Test
    void runThatPurgedNothingLogsNothing(CapturedOutput output) {
        job.purge();

        assertThat(output).doesNotContain("Exports purged");
    }
}
