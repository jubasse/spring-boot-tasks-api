package io.julienmetral.tasks.export.services;

import io.julienmetral.tasks.export.batch.CsvExportJobs;
import io.julienmetral.tasks.export.batch.PublishExport;
import io.julienmetral.tasks.export.entities.DataExport;
import io.julienmetral.tasks.export.entities.DataExportStatus;
import io.julienmetral.tasks.export.entities.DataExportType;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.batch.core.BatchStatus;
import org.springframework.batch.core.job.Job;
import org.springframework.batch.core.job.JobExecution;
import org.springframework.batch.core.job.JobInstance;
import org.springframework.batch.core.job.parameters.JobParameters;
import org.springframework.batch.core.job.parameters.JobParametersBuilder;
import org.springframework.batch.core.launch.JobInstanceAlreadyCompleteException;
import org.springframework.batch.core.launch.JobOperator;
import org.springframework.batch.core.repository.JobRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class DataExportRunnerTest {

    private static final UUID EXPORT_ID = UUID.fromString("00000000-0000-0000-0000-0000000000e1");

    private static final JobParameters PARAMETERS = new JobParametersBuilder()
            .addString(PublishExport.EXPORT_ID, EXPORT_ID.toString())
            .toJobParameters();

    @Mock
    private DataExportService exportService;

    @Mock
    private JobOperator jobOperator;

    @Mock
    private JobRepository jobRepository;

    @Mock
    private Job tasksCsvExport;

    @Mock
    private Job usersCsvExport;

    private DataExportRunner runner;

    @BeforeEach
    void setUp() {
        runner = new DataExportRunner(exportService, jobOperator, jobRepository, tasksCsvExport, usersCsvExport);
    }

    private void claims(DataExportType type) {
        DataExport export = new DataExport();
        export.setId(EXPORT_ID);
        export.setType(type);
        export.setStatus(DataExportStatus.RUNNING);
        when(exportService.claim(EXPORT_ID)).thenReturn(Optional.of(export));
    }

    @Test
    void claimedTasksExportStartsTheTasksJobWithTheExportIdAsItsOnlyParameter() throws Exception {
        claims(DataExportType.TASKS_CSV);
        when(tasksCsvExport.getName()).thenReturn(CsvExportJobs.TASKS_JOB);

        runner.run(EXPORT_ID);

        ArgumentCaptor<JobParameters> parameters = ArgumentCaptor.forClass(JobParameters.class);
        verify(jobOperator).start(eq(tasksCsvExport), parameters.capture());
        assertThat(parameters.getValue()).isEqualTo(PARAMETERS);
        assertThat(parameters.getValue().parameters()).singleElement()
                .satisfies(parameter -> assertThat(parameter.identifying()).isTrue());
        verify(jobRepository).getJobInstance(CsvExportJobs.TASKS_JOB, PARAMETERS);
        verifyNoInteractions(usersCsvExport);
    }

    @Test
    void claimedUsersExportStartsTheUsersJob() throws Exception {
        claims(DataExportType.USERS_CSV);
        when(usersCsvExport.getName()).thenReturn(CsvExportJobs.USERS_JOB);

        runner.run(EXPORT_ID);

        verify(jobOperator).start(usersCsvExport, PARAMETERS);
    }

    @Test
    void exportThatThisInstanceDidNotClaimStartsNothing() {
        when(exportService.claim(EXPORT_ID)).thenReturn(Optional.empty());

        runner.run(EXPORT_ID);

        verifyNoInteractions(jobOperator, jobRepository);
        verify(exportService, never()).fail(any(), any());
    }

    @Test
    void runLeftStartedByAStoppedInstanceIsRecoveredBeforeTheJobRestarts() throws Exception {
        claims(DataExportType.TASKS_CSV);
        when(tasksCsvExport.getName()).thenReturn(CsvExportJobs.TASKS_JOB);
        JobInstance instance = new JobInstance(7L, CsvExportJobs.TASKS_JOB);
        JobExecution interrupted = execution(11L, instance, BatchStatus.STARTED);
        JobExecution failedEarlier = execution(10L, instance, BatchStatus.FAILED);
        when(jobRepository.getJobInstance(CsvExportJobs.TASKS_JOB, PARAMETERS)).thenReturn(instance);
        when(jobRepository.getJobExecutions(instance)).thenReturn(List.of(interrupted, failedEarlier));

        runner.run(EXPORT_ID);

        InOrder order = inOrder(jobOperator);
        order.verify(jobOperator).recover(interrupted);
        order.verify(jobOperator).start(tasksCsvExport, PARAMETERS);
        verify(jobOperator, never()).recover(failedEarlier);
    }

    @Test
    void jobThatCannotStartFailsTheExportWithTheKindOfRefusal() throws Exception {
        claims(DataExportType.TASKS_CSV);
        when(tasksCsvExport.getName()).thenReturn(CsvExportJobs.TASKS_JOB);
        when(jobOperator.start(tasksCsvExport, PARAMETERS))
                .thenThrow(new JobInstanceAlreadyCompleteException("instance of export " + EXPORT_ID));

        runner.run(EXPORT_ID);

        verify(exportService).fail(EXPORT_ID, "JobInstanceAlreadyCompleteException");
    }

    private static JobExecution execution(long id, JobInstance instance, BatchStatus status) {
        JobExecution execution = new JobExecution(id, instance, PARAMETERS);
        execution.setStatus(status);
        return execution;
    }
}
