package io.julienmetral.tasks.export.batch;

import io.julienmetral.tasks.export.services.DataExportService;
import io.julienmetral.tasks.media.exceptions.StorageUnavailableException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.batch.core.job.JobExecution;
import org.springframework.batch.core.job.JobInstance;
import org.springframework.batch.core.job.parameters.JobParametersBuilder;
import org.springframework.batch.core.scope.context.ChunkContext;
import org.springframework.batch.core.scope.context.StepContext;
import org.springframework.batch.core.step.StepContribution;
import org.springframework.batch.core.step.StepExecution;
import org.springframework.batch.infrastructure.repeat.RepeatStatus;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;

@ExtendWith(MockitoExtension.class)
class PublishExportTest {

    private static final String WRITE_STEP = "tasksCsvWrite";

    private static final Instant NOW = Instant.parse("2030-03-04T23:30:00Z");

    private final UUID exportId = UUID.randomUUID();

    @Mock
    private DataExportService exportService;

    private JobExecution job;

    private StepExecution publishStep;

    @BeforeEach
    void setUp() {
        job = new JobExecution(
                1L,
                new JobInstance(1L, CsvExportJobs.TASKS_JOB),
                new JobParametersBuilder().addString(PublishExport.EXPORT_ID, exportId.toString()).toJobParameters()
        );
        publishStep = step(3L, "tasksCsvExportPublish", 0);
    }

    private StepExecution step(long id, String name, long writeCount) {
        StepExecution step = new StepExecution(id, name, job);
        step.setWriteCount(writeCount);
        job.addStepExecution(step);
        return step;
    }

    private RepeatStatus publish(Clock clock) {
        return new PublishExport(exportService, clock, WRITE_STEP, "tasks")
                .execute(new StepContribution(publishStep), new ChunkContext(new StepContext(publishStep)));
    }

    @Test
    void storesTheWrittenCsvWithTheRowCountOfTheWriteStep() {
        step(2L, WRITE_STEP, 1234);

        RepeatStatus status = publish(Clock.fixed(NOW, ZoneOffset.UTC));

        assertThat(status).isEqualTo(RepeatStatus.FINISHED);
        verify(exportService).complete(
                exportId, ExportFiles.of(exportId, "csv"), "tasks-2030-03-04.csv", "text/csv", 1234L);
    }

    @Test
    void writesOfOtherStepsAreNotCounted() {
        step(2L, WRITE_STEP, 10);
        step(4L, "anotherStep", 99);

        publish(Clock.fixed(NOW, ZoneOffset.UTC));

        verify(exportService).complete(eq(exportId), eq(ExportFiles.of(exportId, "csv")), anyString(),
                eq("text/csv"), eq(10L));
    }

    @Test
    void emptyExportIsPublishedWithNoRow() {
        step(2L, WRITE_STEP, 0);

        publish(Clock.fixed(NOW, ZoneOffset.UTC));

        verify(exportService).complete(eq(exportId), eq(ExportFiles.of(exportId, "csv")), anyString(),
                eq("text/csv"), eq(0L));
    }

    @Test
    void fileIsNamedAfterTheDateOfTheClock() {
        step(2L, WRITE_STEP, 1);

        publish(Clock.fixed(NOW, ZoneId.of("Asia/Tokyo")));

        verify(exportService).complete(eq(exportId), eq(ExportFiles.of(exportId, "csv")),
                eq("tasks-2030-03-05.csv"), eq("text/csv"), anyLong());
    }

    @Test
    void fileThatCannotBeStoredFailsTheStep() {
        step(2L, WRITE_STEP, 1);
        StorageUnavailableException failure = new StorageUnavailableException(new RuntimeException("down"));
        doThrow(failure).when(exportService).complete(eq(exportId), eq(ExportFiles.of(exportId, "csv")), anyString(),
                anyString(), anyLong());

        assertThatThrownBy(() -> publish(Clock.fixed(NOW, ZoneOffset.UTC))).isSameAs(failure);
    }
}
