package io.julienmetral.tasks.export.batch;

import io.julienmetral.tasks.export.services.DataExportService;
import lombok.RequiredArgsConstructor;
import org.springframework.batch.core.BatchStatus;
import org.springframework.batch.core.job.JobExecution;
import org.springframework.batch.core.listener.JobExecutionListener;
import org.springframework.stereotype.Component;

import java.util.UUID;

/** Deletes the run's local file whatever happened, and records a failed run on its export. */
@Component
@RequiredArgsConstructor
class DataExportJobListener implements JobExecutionListener {

    private final DataExportService exportService;

    @Override
    public void afterJob(JobExecution execution) {
        UUID exportId = UUID.fromString(execution.getJobParameters().getString(PublishExport.EXPORT_ID));

        ExportFiles.deleteAll(exportId);

        if (execution.getStatus() != BatchStatus.COMPLETED) {
            exportService.fail(exportId, failureOf(execution));
        }
    }

    // The exception's class, never its message, which can carry data of the rows
    private static String failureOf(JobExecution execution) {
        return execution.getAllFailureExceptions().stream()
                .findFirst()
                .map(failure -> failure.getClass().getSimpleName())
                .orElse(execution.getExitStatus().getExitCode());
    }
}
