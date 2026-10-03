package io.julienmetral.tasks.export.batch;

import io.julienmetral.tasks.export.services.DataExportService;
import io.julienmetral.tasks.media.exceptions.StorageUnavailableException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.batch.core.BatchStatus;
import org.springframework.batch.core.ExitStatus;
import org.springframework.batch.core.job.JobExecution;
import org.springframework.batch.core.job.JobInstance;
import org.springframework.batch.core.job.parameters.JobParametersBuilder;
import org.springframework.batch.core.step.StepExecution;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

@ExtendWith(MockitoExtension.class)
class DataExportJobListenerTest {

    private final UUID exportId = UUID.randomUUID();

    @Mock
    private DataExportService exportService;

    @InjectMocks
    private DataExportJobListener listener;

    @AfterEach
    void deleteLocalFiles() {
        ExportFiles.deleteAll(exportId);
    }

    private JobExecution execution(BatchStatus status) {
        JobExecution execution = new JobExecution(
                1L,
                new JobInstance(1L, CsvExportJobs.TASKS_JOB),
                new JobParametersBuilder().addString(PublishExport.EXPORT_ID, exportId.toString()).toJobParameters()
        );
        execution.setStatus(status);
        return execution;
    }

    private Path writeLocalFile(String name) throws IOException {
        return Files.writeString(ExportFiles.of(exportId, name), "\"id\"\r\n");
    }

    @Test
    void completedRunDeletesTheDirectoryOfItsFilesAndRecordsNothing() throws IOException {
        Path csv = writeLocalFile(PublishExport.CSV_FILE);
        Path json = writeLocalFile("my-data.json");
        Path zip = writeLocalFile("export.zip");

        listener.afterJob(execution(BatchStatus.COMPLETED));

        assertThat(csv).doesNotExist();
        assertThat(json).doesNotExist();
        assertThat(zip).doesNotExist();
        assertThat(csv.getParent()).doesNotExist();
        verifyNoInteractions(exportService);
    }

    @Test
    void failedRunDeletesItsFilesAndRecordsTheClassOfItsFailureNeverItsMessage() throws IOException {
        Path csv = writeLocalFile(PublishExport.CSV_FILE);
        JobExecution execution = execution(BatchStatus.FAILED);
        StepExecution publish = new StepExecution(2L, "tasksCsvExportPublish", execution);
        publish.addFailureException(new StorageUnavailableException(new RuntimeException("jane@example.com")));
        execution.addStepExecution(publish);

        listener.afterJob(execution);

        assertThat(csv.getParent()).doesNotExist();
        verify(exportService).fail(exportId, "StorageUnavailableException");
    }

    @Test
    void failureOfTheJobItselfIsRecordedByItsClass() {
        JobExecution execution = execution(BatchStatus.FAILED);
        execution.addFailureException(new IllegalStateException("Title of a task"));

        listener.afterJob(execution);

        verify(exportService).fail(exportId, "IllegalStateException");
    }

    @Test
    void runEndedWithoutExceptionRecordsItsExitCode() {
        JobExecution execution = execution(BatchStatus.STOPPED);
        execution.setExitStatus(ExitStatus.STOPPED);

        listener.afterJob(execution);

        verify(exportService).fail(exportId, "STOPPED");
    }

    @ParameterizedTest
    @EnumSource(value = BatchStatus.class, names = {"FAILED", "STOPPED", "ABANDONED", "UNKNOWN"})
    void everyRunThatDidNotCompleteFailsTheExport(BatchStatus status) {
        listener.afterJob(execution(status));

        verify(exportService).fail(exportId, ExitStatus.UNKNOWN.getExitCode());
    }

    @Test
    void runWithoutLocalFileEndsNormally() {
        listener.afterJob(execution(BatchStatus.COMPLETED));

        verifyNoInteractions(exportService);
    }
}
