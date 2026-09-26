package io.julienmetral.tasks.media.model;

/**
 * @param skipped              another instance held the cleanup lock, so nothing was done
 * @param detachedAttachments  attachments removed from tasks deleted before the retention period
 * @param detachedAvatars      profile photos removed from users deleted before the retention period
 * @param deletedMedia         media rows deleted because nothing references them any more
 * @param deletedOrphanObjects stored objects deleted because no media row points to them
 */
public record MediaCleanupReport(
        boolean skipped,
        int detachedAttachments,
        int detachedAvatars,
        int deletedMedia,
        int deletedOrphanObjects
) {

    public static MediaCleanupReport skippedRun() {
        return new MediaCleanupReport(true, 0, 0, 0, 0);
    }
}
