package io.julienmetral.tasks.media.services;

import io.julienmetral.tasks.media.model.MediaCleanupReport;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBooleanProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Slf4j
@Component
@RequiredArgsConstructor
@ConditionalOnBooleanProperty(name = "media.cleanup.enabled", matchIfMissing = true)
public class MediaCleanupJob {

    private final MediaCleanupService cleanupService;

    @Scheduled(cron = "${media.cleanup.cron}")
    public void run() {
        MediaCleanupReport report = cleanupService.cleanUp();

        if (report.skipped()) {
            log.info("Media cleanup skipped: another instance is running it");
            return;
        }

        log.info(
                "Media cleanup: {} attachments and {} avatars detached, {} media rows and {} orphan objects deleted",
                report.detachedAttachments(),
                report.detachedAvatars(),
                report.deletedMedia(),
                report.deletedOrphanObjects()
        );
    }
}
