package io.julienmetral.tasks.export.services;

import io.julienmetral.tasks.config.ScheduledJobLocks;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBooleanProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** Resumes the exports of an instance that stopped while running them, once their lease has run out. */
@Slf4j
@Component
@RequiredArgsConstructor
@ConditionalOnBooleanProperty(name = "exports.recovery-enabled", matchIfMissing = true)
public class DataExportRecoveryJob {

    private final DataExportService exportService;

    @Scheduled(fixedDelayString = "${exports.recovery-interval}")
    @SchedulerLock(name = ScheduledJobLocks.DATA_EXPORT_RECOVERY, lockAtMostFor = "PT4M", lockAtLeastFor = "PT30S")
    public void requeueInterrupted() {
        int requeued = exportService.requeueInterrupted();

        if (requeued > 0) {
            log.info("Interrupted exports queued again: {}", requeued);
        }
    }
}
