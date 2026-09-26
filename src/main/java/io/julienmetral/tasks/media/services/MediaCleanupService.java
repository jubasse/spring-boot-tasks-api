package io.julienmetral.tasks.media.services;

import io.julienmetral.tasks.config.MediaCleanupProperties;
import io.julienmetral.tasks.media.model.MediaCleanupReport;
import io.julienmetral.tasks.media.repositories.MediaCleanupQueries;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.time.Clock;
import java.time.Instant;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Purges files nobody can reach any more: attachments of tasks and profile photos of users soft-deleted for longer
 * than the retention period, media rows that nothing references, and stored objects without a media row (left behind
 * by a crash between upload and commit). Rows go in the transaction, objects only after it commits; an object that
 * fails to delete is caught by the next run's sweep.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class MediaCleanupService {

    private final MediaCleanupQueries queries;
    private final ObjectStorage objectStorage;
    private final MediaCleanupProperties properties;
    private final Clock clock;

    @Transactional
    public MediaCleanupReport cleanUp() {
        // Several instances may run the schedule: only the one holding the lock works
        if (!queries.tryLock()) {
            return MediaCleanupReport.skippedRun();
        }

        Instant now = clock.instant();
        Instant retentionCutoff = now.minus(properties.retention());
        Instant graceCutoff = now.minus(properties.orphanGracePeriod());

        int detachedAttachments = queries.detachAttachmentsOfTasksDeletedBefore(retentionCutoff).size();
        int detachedAvatars = queries.detachAvatarsOfUsersDeletedBefore(retentionCutoff).size();
        List<String> deletedMediaKeys = queries.deleteUnreferencedMediaCreatedBefore(graceCutoff);

        List<String> oldObjects = objectStorage.listKeysModifiedBefore(graceCutoff);
        Set<String> referenced = queries.existingStorageKeys(oldObjects);

        Set<String> keysToDelete = new LinkedHashSet<>(deletedMediaKeys);
        int deletedOrphanObjects = 0;

        for (String key : oldObjects) {
            if (!referenced.contains(key) && keysToDelete.add(key)) {
                deletedOrphanObjects++;
            }
        }

        deleteObjectsAfterCommit(keysToDelete);

        return new MediaCleanupReport(
                false,
                detachedAttachments,
                detachedAvatars,
                deletedMediaKeys.size(),
                deletedOrphanObjects
        );
    }

    private void deleteObjectsAfterCommit(Set<String> keys) {
        if (keys.isEmpty()) {
            return;
        }

        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                for (String key : keys) {
                    try {
                        objectStorage.delete(key);
                    } catch (RuntimeException exception) {
                        log.warn("Could not delete object {} during media cleanup", key, exception);
                    }
                }
            }
        });
    }
}
