package io.julienmetral.tasks.export.batch;

import io.julienmetral.tasks.export.services.DataExportService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.batch.core.job.JobExecution;
import org.springframework.batch.core.job.JobInstance;
import org.springframework.batch.core.job.parameters.JobParametersBuilder;
import org.springframework.batch.core.scope.context.StepSynchronizationManager;
import org.springframework.batch.core.step.StepExecution;
import org.springframework.batch.infrastructure.item.Chunk;

import java.util.UUID;

import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

@ExtendWith(MockitoExtension.class)
class DataExportLeaseRenewalTest {

    private final UUID exportId = UUID.randomUUID();

    @Mock
    private DataExportService exportService;

    @InjectMocks
    private DataExportLeaseRenewal renewal;

    @AfterEach
    void leaveTheStep() {
        StepSynchronizationManager.close();
    }

    private StepExecution step(String name) {
        JobExecution job = new JobExecution(
                1L,
                new JobInstance(1L, CsvExportJobs.TASKS_JOB),
                new JobParametersBuilder().addString(PublishExport.EXPORT_ID, exportId.toString()).toJobParameters()
        );
        StepExecution step = new StepExecution(2L, name, job);
        job.addStepExecution(step);
        return step;
    }

    @Test
    void leaseOfTheExportIsRenewedAsAStepStarts() {
        renewal.beforeStep(step("tasksCsvWrite"));

        verify(exportService).renewLease(exportId);
    }

    @Test
    void leaseOfTheExportOfTheRunningStepIsRenewedAfterEachChunk() {
        StepSynchronizationManager.register(step("tasksCsvWrite"));

        renewal.afterChunk(new Chunk<>("first", "second"));
        renewal.afterChunk(new Chunk<>("third"));

        verify(exportService, times(2)).renewLease(exportId);
    }

    @Test
    void chunkOutsideAStepRenewsNothing() {
        renewal.afterChunk(new Chunk<>("orphan"));

        verifyNoInteractions(exportService);
    }
}
