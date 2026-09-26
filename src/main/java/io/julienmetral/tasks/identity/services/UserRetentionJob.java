package io.julienmetral.tasks.identity.services;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
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
    public void run() {
        UserRetentionReport report = retentionService.apply();

        if (report.skipped()) {
            log.info("User retention skipped: another instance is running it");
            return;
        }

        log.info(
                "User retention: {} deleted users anonymized, {} inactive accounts warned, {} deleted",
                report.anonymized(),
                report.warned(),
                report.deleted()
        );
    }
}
