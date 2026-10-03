package io.julienmetral.tasks.export.services;

import io.julienmetral.tasks.config.ScheduledJobLocks;
import io.julienmetral.tasks.export.ExportProperties;
import io.julienmetral.tasks.export.repositories.BatchMetadataQueries;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBooleanProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Clock;

@Slf4j
@Component
@RequiredArgsConstructor
@ConditionalOnBooleanProperty(name = "exports.purge-enabled", matchIfMissing = true)
public class DataExportPurgeJob {

    private final DataExportService exportService;
    private final BatchMetadataQueries batchMetadataQueries;
    private final ExportProperties properties;
    private final Clock clock;

    @Scheduled(cron = "${exports.purge-cron}")
    @SchedulerLock(name = ScheduledJobLocks.DATA_EXPORT_PURGE, lockAtMostFor = "PT1H", lockAtLeastFor = "PT5M")
    public void purge() {
        int files = exportService.purge();
        int executions = batchMetadataQueries.deleteExecutionsEndedBefore(
                clock.instant().minus(properties.historyRetention()));

        if (files > 0 || executions > 0) {
            log.info("Exports purged: {} expired files, {} Spring Batch executions", files, executions);
        }
    }
}
