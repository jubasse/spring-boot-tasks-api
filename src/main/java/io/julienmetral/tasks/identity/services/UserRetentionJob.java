package io.julienmetral.tasks.identity.services;

import io.julienmetral.tasks.config.ScheduledJobLocks;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBooleanProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Slf4j
@Component
@RequiredArgsConstructor
@ConditionalOnBooleanProperty(name = "identity.retention.enabled", matchIfMissing = true)
public class UserRetentionJob {

    private final UserRetentionService retentionService;

    @Scheduled(cron = "${identity.retention.cron}")
    @SchedulerLock(name = ScheduledJobLocks.USER_RETENTION, lockAtMostFor = "PT2H", lockAtLeastFor = "PT5M")
    public void run() {
        UserRetentionReport report = retentionService.apply();

        log.info(
                "User retention: {} deleted users anonymized, {} inactive accounts warned, {} deleted",
                report.anonymized(),
                report.warned(),
                report.deleted()
        );
    }
}
