package io.julienmetral.tasks.export.services;

import io.julienmetral.tasks.export.ExportProperties;
import io.julienmetral.tasks.export.entities.DataExport;
import io.julienmetral.tasks.export.entities.DataExportStatus;
import io.julienmetral.tasks.export.entities.DataExportType;
import io.julienmetral.tasks.export.entities.TaskExportFilters;
import io.julienmetral.tasks.export.events.DataExportCompleted;
import io.julienmetral.tasks.export.events.DataExportFailed;
import io.julienmetral.tasks.export.exceptions.DataExportInProgressException;
import io.julienmetral.tasks.export.exceptions.DataExportNotFoundException;
import io.julienmetral.tasks.export.messaging.DataExportRequested;
import io.julienmetral.tasks.export.messaging.ExportQueues;
import io.julienmetral.tasks.export.repositories.DataExportRepository;
import io.julienmetral.tasks.identity.repositories.UserProfileRepository;
import io.julienmetral.tasks.identity.repositories.UserRepository;
import io.julienmetral.tasks.media.model.Media;
import io.julienmetral.tasks.media.model.MediaUsage;
import io.julienmetral.tasks.media.services.MediaService;
import io.julienmetral.tasks.messaging.services.Outbox;
import lombok.RequiredArgsConstructor;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class DataExportService {

    private final DataExportRepository repository;
    private final UserRepository userRepository;
    private final UserProfileRepository userProfileRepository;
    private final MediaService mediaService;
    private final Outbox outbox;
    private final ExportProperties properties;
    private final ApplicationEventPublisher eventPublisher;
    private final Clock clock;

    /**
     * Queues an export for its owner, whose account row is locked meanwhile, so that two requests at once count each
     * other: one export of each type may be queued or running per owner.
     *
     * @throws DataExportInProgressException when one already is
     */
    @Transactional
    public DataExport request(UUID ownerId, DataExportType type, TaskExportFilters taskFilters) {
        userRepository.findByIdForUpdate(ownerId);

        if (repository.existsByOwnerIdAndTypeAndStatusIn(ownerId, type, DataExportStatus.ACTIVE)) {
            throw new DataExportInProgressException();
        }

        DataExport export = new DataExport();
        export.setOwner(userProfileRepository.getReferenceById(ownerId));
        export.setType(type);
        export.setStatus(DataExportStatus.QUEUED);
        export.setTaskFilters(taskFilters);
        export.setCreatedAt(clock.instant());
        export.setLeaseUntil(clock.instant().plus(properties.lease()));

        DataExport saved = repository.save(export);
        outbox.enqueue(ExportQueues.RUN, new DataExportRequested(saved.getId()));

        return saved;
    }

    @Transactional(readOnly = true)
    public Page<DataExport> findOwn(UUID ownerId, Pageable pageable) {
        return repository.findByOwnerId(ownerId, pageable);
    }

    /** @throws DataExportNotFoundException also when the export belongs to someone else */
    @Transactional(readOnly = true)
    public DataExport getOwn(UUID ownerId, UUID id) {
        return repository.findByIdAndOwnerId(id, ownerId).orElseThrow(() -> new DataExportNotFoundException(id));
    }

    /**
     * Deletes the export and its file.
     *
     * @throws DataExportInProgressException while it runs
     */
    @Transactional
    public void deleteOwn(UUID ownerId, UUID id) {
        DataExport export = getOwn(ownerId, id);

        if (DataExportStatus.ACTIVE.contains(export.getStatus())) {
            throw new DataExportInProgressException();
        }

        deleteFile(export);
        repository.delete(export);
    }

    @Transactional(readOnly = true)
    public Optional<DataExport> find(UUID id) {
        return repository.findById(id);
    }

    /** @return the export when this instance now runs it; empty when it is not queued, or another instance runs it */
    @Transactional
    public Optional<DataExport> claim(UUID id) {
        Instant now = clock.instant();
        int claimed = repository.claim(
                id, now, now.plus(properties.lease()), DataExportStatus.QUEUED, DataExportStatus.RUNNING);

        return claimed == 1 ? repository.findById(id) : Optional.empty();
    }

    /** Pushes a running export's lease forward, so that a long run is not taken for an interrupted one. */
    @Transactional
    public void renewLease(UUID id) {
        repository.renewLease(id, clock.instant().plus(properties.lease()), DataExportStatus.RUNNING);
    }

    /**
     * The last step of a successful run: stores the file and makes it downloadable until the retention ends. The
     * owner is emailed after the commit.
     */
    @Transactional
    public void complete(UUID id, Path file, String filename, String contentType, long rowCount) {
        DataExport export = repository.findById(id).orElseThrow(() -> new DataExportNotFoundException(id));
        Instant now = clock.instant();

        Media media = mediaService.storeGenerated(
                file, filename, contentType, MediaUsage.EXPORT, export.getOwner().getId());

        export.setMedia(media);
        export.setStatus(DataExportStatus.COMPLETED);
        export.setRowCount(rowCount);
        export.setCompletedAt(now);
        export.setExpiresAt(now.plus(properties.retention()));
        export.setLeaseUntil(null);

        eventPublisher.publishEvent(new DataExportCompleted(
                export.getId(), export.getOwner().getId(), export.getType(), export.getExpiresAt()));
    }

    /** Records a run that failed; the owner is emailed once no attempt is left, after the commit. */
    @Transactional
    public void fail(UUID id, String failure) {
        repository.findById(id).ifPresent(export -> {
            if (export.getStatus() != DataExportStatus.RUNNING) {
                return;
            }

            export.setStatus(DataExportStatus.FAILED);
            export.setFailure(failure.length() > 100 ? failure.substring(0, 100) : failure);
            export.setLeaseUntil(null);

            eventPublisher.publishEvent(new DataExportFailed(
                    export.getId(), export.getOwner().getId(), export.getType()));
        });
    }

    /**
     * Queues again the exports whose lease ran out: a running one whose instance stopped (or fails it once every
     * attempt is used), and a queued one whose message was lost. A lost message used to leave its export queued for
     * good, blocking its owner's next export of that type. A duplicate message does nothing: the run claims first.
     *
     * @return how many were queued again
     */
    @Transactional
    public int requeueInterrupted() {
        Instant now = clock.instant();
        int requeued = 0;

        for (DataExport export : repository.findByStatusInAndLeaseUntilBefore(DataExportStatus.ACTIVE, now)) {
            if (export.getStatus() == DataExportStatus.RUNNING && export.getAttempts() >= properties.maxAttempts()) {
                fail(export.getId(), "Interrupted");
                continue;
            }

            export.setStatus(DataExportStatus.QUEUED);
            export.setLeaseUntil(now.plus(properties.lease()));
            outbox.enqueue(ExportQueues.RUN, new DataExportRequested(export.getId()));
            requeued++;
        }

        return requeued;
    }

    /**
     * Deletes the files of exports past their retention, which stay listed as expired, then the expired and failed
     * exports older than the history retention.
     *
     * @return how many files were deleted
     */
    @Transactional
    public int purge() {
        Instant now = clock.instant();
        int deleted = 0;

        for (DataExport export : repository.findByStatusAndExpiresAtBefore(DataExportStatus.COMPLETED, now)) {
            deleteFile(export);
            export.setStatus(DataExportStatus.EXPIRED);
            deleted++;
        }

        repository.deleteByStatusInAndCreatedAtBefore(
                Set.of(DataExportStatus.EXPIRED, DataExportStatus.FAILED),
                now.minus(properties.historyRetention()));

        return deleted;
    }

    private void deleteFile(DataExport export) {
        Media media = export.getMedia();

        // Hibernate writes the export's update before the media's deletion, so the foreign key holds
        if (media != null) {
            export.setMedia(null);
            mediaService.delete(media);
        }
    }
}
