package io.julienmetral.tasks.export.services;

import io.julienmetral.tasks.export.batch.CsvExportJobs;
import io.julienmetral.tasks.export.batch.PublishExport;
import io.julienmetral.tasks.export.entities.DataExport;
import io.julienmetral.tasks.export.entities.DataExportType;
import lombok.extern.slf4j.Slf4j;
import org.springframework.batch.core.job.Job;
import org.springframework.batch.core.job.JobExecution;
import org.springframework.batch.core.job.JobExecutionException;
import org.springframework.batch.core.job.JobInstance;
import org.springframework.batch.core.job.parameters.JobParameters;
import org.springframework.batch.core.job.parameters.JobParametersBuilder;
import org.springframework.batch.core.launch.JobOperator;
import org.springframework.batch.core.repository.JobRepository;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;

import java.util.Map;
import java.util.Optional;
import java.util.UUID;

@Slf4j
@Service
public class DataExportRunner {

    private final DataExportService exportService;
    private final JobOperator jobOperator;
    private final JobRepository jobRepository;
    private final Map<DataExportType, Job> jobs;

    public DataExportRunner(
            DataExportService exportService,
            JobOperator jobOperator,
            JobRepository jobRepository,
            @Qualifier(CsvExportJobs.TASKS_JOB) Job tasksCsvExport,
            @Qualifier(CsvExportJobs.USERS_JOB) Job usersCsvExport
    ) {
        this.exportService = exportService;
        this.jobOperator = jobOperator;
        this.jobRepository = jobRepository;
        this.jobs = Map.of(DataExportType.TASKS_CSV, tasksCsvExport, DataExportType.USERS_CSV, usersCsvExport);
    }

    /**
     * Runs the export's job on this thread once this instance has claimed the export; a duplicate message, or an
     * export another instance runs, does nothing. Called outside any transaction: Spring Batch refuses to start a job
     * inside one, and every step commits on its own.
     * <p>
     * A run interrupted on an instance that stopped is left STARTED in Batch's tables: it is recovered first, so the
     * job instance restarts rather than being refused as already running.
     */
    public void run(UUID exportId) {
        Optional<DataExport> claimed = exportService.claim(exportId);

        if (claimed.isEmpty()) {
            log.debug("Export {} is not waiting to run here", exportId);
            return;
        }

        Job job = jobs.get(claimed.get().getType());
        JobParameters parameters = new JobParametersBuilder()
                .addString(PublishExport.EXPORT_ID, exportId.toString())
                .toJobParameters();

        recoverInterruptedRuns(job, parameters);

        try {
            jobOperator.start(job, parameters);
        } catch (JobExecutionException refused) {
            log.warn("Export {} could not start", exportId, refused);
            exportService.fail(exportId, refused.getClass().getSimpleName());
        }
    }

    private void recoverInterruptedRuns(Job job, JobParameters parameters) {
        JobInstance instance = jobRepository.getJobInstance(job.getName(), parameters);

        if (instance == null) {
            return;
        }

        for (JobExecution execution : jobRepository.getJobExecutions(instance)) {
            if (execution.isRunning()) {
                jobOperator.recover(execution);
            }
        }
    }
}
