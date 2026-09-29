package io.julienmetral.tasks.export.batch;

import io.julienmetral.tasks.export.services.DataExportService;
import org.springframework.batch.core.job.JobExecution;
import org.springframework.batch.core.step.StepContribution;
import org.springframework.batch.core.step.StepExecution;
import org.springframework.batch.core.scope.context.ChunkContext;
import org.springframework.batch.core.step.tasklet.Tasklet;
import org.springframework.batch.infrastructure.repeat.RepeatStatus;

import java.time.Clock;
import java.time.LocalDate;
import java.util.UUID;

/** The last step of a CSV export: stores the file written by the step before, in this step's transaction. */
public class PublishExport implements Tasklet {

    public static final String EXPORT_ID = "exportId";

    static final String CSV_FILE = "export.csv";

    private final DataExportService exportService;
    private final Clock clock;
    private final String writeStepName;
    private final String filenamePrefix;

    PublishExport(DataExportService exportService, Clock clock, String writeStepName, String filenamePrefix) {
        this.exportService = exportService;
        this.clock = clock;
        this.writeStepName = writeStepName;
        this.filenamePrefix = filenamePrefix;
    }

    @Override
    public RepeatStatus execute(StepContribution contribution, ChunkContext chunkContext) {
        JobExecution job = chunkContext.getStepContext().getStepExecution().getJobExecution();
        UUID exportId = UUID.fromString(job.getJobParameters().getString(EXPORT_ID));
        long rows = job.getStepExecutions().stream()
                .filter(step -> step.getStepName().equals(writeStepName))
                .mapToLong(StepExecution::getWriteCount)
                .sum();

        exportService.complete(
                exportId,
                ExportFiles.of(exportId, CSV_FILE),
                filenamePrefix + "-" + LocalDate.now(clock) + ".csv",
                "text/csv",
                rows
        );

        return RepeatStatus.FINISHED;
    }
}
