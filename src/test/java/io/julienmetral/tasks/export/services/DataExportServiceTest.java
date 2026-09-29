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
import io.julienmetral.tasks.identity.entities.UserProfile;
import io.julienmetral.tasks.identity.repositories.UserProfileRepository;
import io.julienmetral.tasks.identity.repositories.UserRepository;
import io.julienmetral.tasks.media.exceptions.StorageUnavailableException;
import io.julienmetral.tasks.media.model.Media;
import io.julienmetral.tasks.media.model.MediaUsage;
import io.julienmetral.tasks.media.services.MediaService;
import io.julienmetral.tasks.messaging.services.Outbox;
import io.julienmetral.tasks.task.entities.TaskStatus;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;

import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import static io.julienmetral.tasks.support.UserProfiles.reference;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.AdditionalAnswers.returnsFirstArg;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class DataExportServiceTest {

    private static final Instant NOW = Instant.parse("2030-01-01T10:00:00Z");

    private static final Duration RETENTION = Duration.ofDays(7);

    private static final Duration HISTORY_RETENTION = Duration.ofDays(30);

    private static final Duration LEASE = Duration.ofMinutes(15);

    private static final int MAX_ATTEMPTS = 3;

    private static final UUID OWNER_ID = UUID.fromString("00000000-0000-0000-0000-000000000001");

    private static final UUID EXPORT_ID = UUID.fromString("00000000-0000-0000-0000-0000000000e1");

    @Mock
    private DataExportRepository repository;

    @Mock
    private UserRepository userRepository;

    @Mock
    private UserProfileRepository userProfileRepository;

    @Mock
    private MediaService mediaService;

    @Mock
    private Outbox outbox;

    @Mock
    private ApplicationEventPublisher eventPublisher;

    private DataExportService service;

    @BeforeEach
    void setUp() {
        ExportProperties properties = new ExportProperties(
                "https://app.example/exports",
                RETENTION,
                HISTORY_RETENTION,
                500,
                LEASE,
                MAX_ATTEMPTS,
                false,
                "0 30 4 * * *",
                false,
                Duration.ofMinutes(5)
        );
        service = new DataExportService(
                repository,
                userRepository,
                userProfileRepository,
                mediaService,
                outbox,
                properties,
                eventPublisher,
                Clock.fixed(NOW, ZoneOffset.UTC)
        );
    }

    private static DataExport export(DataExportStatus status) {
        DataExport export = new DataExport();
        export.setId(EXPORT_ID);
        export.setOwner(reference(OWNER_ID));
        export.setType(DataExportType.TASKS_CSV);
        export.setStatus(status);
        export.setCreatedAt(NOW.minus(Duration.ofMinutes(5)));
        return export;
    }

    private static Media media() {
        Media media = new Media();
        media.setStorageKey("export/" + UUID.randomUUID());
        return media;
    }

    @Nested
    class Request {

        private final TaskExportFilters filters = new TaskExportFilters(TaskStatus.DONE, UUID.randomUUID(), false);

        @Test
        void locksTheOwnerThenSavesAQueuedExportWithALeaseAndQueuesItsRun() {
            UserProfile owner = reference(OWNER_ID);
            when(userProfileRepository.getReferenceById(OWNER_ID)).thenReturn(owner);
            when(repository.save(any(DataExport.class))).then(invocation -> {
                DataExport saved = invocation.getArgument(0);
                saved.setId(EXPORT_ID);
                return saved;
            });

            DataExport export = service.request(OWNER_ID, DataExportType.TASKS_CSV, filters);

            InOrder order = inOrder(userRepository, repository, outbox);
            order.verify(userRepository).findByIdForUpdate(OWNER_ID);
            order.verify(repository).existsByOwnerIdAndTypeAndStatusIn(
                    OWNER_ID, DataExportType.TASKS_CSV, Set.of(DataExportStatus.QUEUED, DataExportStatus.RUNNING));
            order.verify(repository).save(export);
            order.verify(outbox).enqueue(ExportQueues.RUN, new DataExportRequested(EXPORT_ID));

            assertThat(export.getOwner()).isSameAs(owner);
            assertThat(export.getType()).isEqualTo(DataExportType.TASKS_CSV);
            assertThat(export.getStatus()).isEqualTo(DataExportStatus.QUEUED);
            assertThat(export.getTaskFilters()).isEqualTo(filters);
            assertThat(export.getCreatedAt()).isEqualTo(NOW);
            assertThat(export.getLeaseUntil()).isEqualTo(NOW.plus(LEASE));
            assertThat(export.getAttempts()).isZero();
            assertThat(export.getMedia()).isNull();
        }

        @Test
        void exportOfTheSameTypeQueuedOrRunningIsAConflictAndNothingIsQueued() {
            when(repository.existsByOwnerIdAndTypeAndStatusIn(any(), any(), any())).thenReturn(true);

            assertThatThrownBy(() -> service.request(OWNER_ID, DataExportType.USERS_CSV, null))
                    .isInstanceOf(DataExportInProgressException.class)
                    .hasMessage("An export of the same kind is already queued or running: wait for it to finish");

            verify(userRepository).findByIdForUpdate(OWNER_ID);
            verify(repository, never()).save(any());
            verifyNoInteractions(outbox);
        }

        @Test
        void usersExportLooksForAUsersExportInProgress() {
            when(repository.save(any(DataExport.class))).then(returnsFirstArg());

            service.request(OWNER_ID, DataExportType.USERS_CSV, null);

            verify(repository).existsByOwnerIdAndTypeAndStatusIn(
                    OWNER_ID, DataExportType.USERS_CSV, DataExportStatus.ACTIVE);
        }
    }

    @Nested
    class OwnExports {

        @Test
        void getOwnReturnsTheExportOfItsOwner() {
            DataExport export = export(DataExportStatus.QUEUED);
            when(repository.findByIdAndOwnerId(EXPORT_ID, OWNER_ID)).thenReturn(Optional.of(export));

            assertThat(service.getOwn(OWNER_ID, EXPORT_ID)).isSameAs(export);
        }

        @Test
        void exportOfSomeoneElseIsNotFound() {
            UUID stranger = UUID.randomUUID();

            assertThatThrownBy(() -> service.getOwn(stranger, EXPORT_ID))
                    .isInstanceOf(DataExportNotFoundException.class)
                    .hasMessage("Export not found: " + EXPORT_ID);

            verify(repository).findByIdAndOwnerId(EXPORT_ID, stranger);
        }

        @Test
        void findOwnReadsThePageOfTheOwner() {
            Pageable pageable = PageRequest.of(1, 5);
            Page<DataExport> page = new PageImpl<>(List.of(export(DataExportStatus.COMPLETED)));
            when(repository.findByOwnerId(OWNER_ID, pageable)).thenReturn(page);

            assertThat(service.findOwn(OWNER_ID, pageable)).isSameAs(page);
        }

        @Test
        void deletingACompletedExportDeletesItsFileAndTheExport() {
            DataExport export = export(DataExportStatus.COMPLETED);
            Media media = media();
            export.setMedia(media);
            when(repository.findByIdAndOwnerId(EXPORT_ID, OWNER_ID)).thenReturn(Optional.of(export));

            service.deleteOwn(OWNER_ID, EXPORT_ID);

            verify(mediaService).delete(media);
            verify(repository).delete(export);
            assertThat(export.getMedia()).isNull();
        }

        @ParameterizedTest
        @EnumSource(value = DataExportStatus.class, names = {"FAILED", "EXPIRED"})
        void deletingAnExportWithoutFileDeletesOnlyTheExport(DataExportStatus status) {
            DataExport export = export(status);
            when(repository.findByIdAndOwnerId(EXPORT_ID, OWNER_ID)).thenReturn(Optional.of(export));

            service.deleteOwn(OWNER_ID, EXPORT_ID);

            verify(repository).delete(export);
            verifyNoInteractions(mediaService);
        }

        @ParameterizedTest
        @EnumSource(value = DataExportStatus.class, names = {"QUEUED", "RUNNING"})
        void exportQueuedOrRunningCannotBeDeleted(DataExportStatus status) {
            when(repository.findByIdAndOwnerId(EXPORT_ID, OWNER_ID)).thenReturn(Optional.of(export(status)));

            assertThatThrownBy(() -> service.deleteOwn(OWNER_ID, EXPORT_ID))
                    .isInstanceOf(DataExportInProgressException.class);

            verify(repository, never()).delete(any());
            verifyNoInteractions(mediaService);
        }

        @Test
        void deletingTheExportOfSomeoneElseIsNotFoundAndDeletesNothing() {
            assertThatThrownBy(() -> service.deleteOwn(UUID.randomUUID(), EXPORT_ID))
                    .isInstanceOf(DataExportNotFoundException.class);

            verify(repository, never()).delete(any());
            verifyNoInteractions(mediaService);
        }
    }

    @Nested
    class Claim {

        @Test
        void claimedExportIsReadAgainAndReturned() {
            DataExport running = export(DataExportStatus.RUNNING);
            when(repository.claim(EXPORT_ID, NOW, NOW.plus(LEASE), DataExportStatus.QUEUED, DataExportStatus.RUNNING))
                    .thenReturn(1);
            when(repository.findById(EXPORT_ID)).thenReturn(Optional.of(running));

            assertThat(service.claim(EXPORT_ID)).containsSame(running);
        }

        @Test
        void exportNotWaitingToRunIsNotClaimed() {
            when(repository.claim(EXPORT_ID, NOW, NOW.plus(LEASE), DataExportStatus.QUEUED, DataExportStatus.RUNNING))
                    .thenReturn(0);

            assertThat(service.claim(EXPORT_ID)).isEmpty();
            verify(repository, never()).findById(any());
        }
    }

    @Nested
    class Complete {

        private final Path file = Path.of("/tmp/exports/" + EXPORT_ID + ".csv");

        @Test
        void storesTheFileAndKeepsItDownloadableUntilTheRetentionEnds() {
            DataExport export = export(DataExportStatus.RUNNING);
            export.setLeaseUntil(NOW.plus(LEASE));
            Media media = media();
            when(repository.findById(EXPORT_ID)).thenReturn(Optional.of(export));
            when(mediaService.storeGenerated(file, "tasks-2030-01-01.csv", "text/csv", MediaUsage.EXPORT, OWNER_ID))
                    .thenReturn(media);

            service.complete(EXPORT_ID, file, "tasks-2030-01-01.csv", "text/csv", 42);

            assertThat(export.getMedia()).isSameAs(media);
            assertThat(export.getStatus()).isEqualTo(DataExportStatus.COMPLETED);
            assertThat(export.getRowCount()).isEqualTo(42);
            assertThat(export.getCompletedAt()).isEqualTo(NOW);
            assertThat(export.getExpiresAt()).isEqualTo(NOW.plus(RETENTION));
            assertThat(export.getLeaseUntil()).isNull();
            verify(eventPublisher).publishEvent(
                    new DataExportCompleted(EXPORT_ID, OWNER_ID, DataExportType.TASKS_CSV, NOW.plus(RETENTION)));
        }

        @Test
        void unknownExportIsNotFoundAndNothingIsStored() {
            assertThatThrownBy(() -> service.complete(EXPORT_ID, file, "tasks.csv", "text/csv", 1))
                    .isInstanceOf(DataExportNotFoundException.class);

            verifyNoInteractions(mediaService, eventPublisher);
        }

        @Test
        void fileThatCannotBeStoredLeavesTheExportRunningAndTellsNobody() {
            DataExport export = export(DataExportStatus.RUNNING);
            StorageUnavailableException failure = new StorageUnavailableException(new RuntimeException("down"));
            when(repository.findById(EXPORT_ID)).thenReturn(Optional.of(export));
            when(mediaService.storeGenerated(any(), anyString(), anyString(), any(), any())).thenThrow(failure);

            assertThatThrownBy(() -> service.complete(EXPORT_ID, file, "tasks.csv", "text/csv", 1)).isSameAs(failure);

            assertThat(export.getStatus()).isEqualTo(DataExportStatus.RUNNING);
            assertThat(export.getMedia()).isNull();
            verifyNoInteractions(eventPublisher);
        }
    }

    @Nested
    class Fail {

        @Test
        void runningExportFailsWithItsFailureKindAndItsOwnerIsTold() {
            DataExport export = export(DataExportStatus.RUNNING);
            export.setLeaseUntil(NOW.plus(LEASE));
            when(repository.findById(EXPORT_ID)).thenReturn(Optional.of(export));

            service.fail(EXPORT_ID, "StorageUnavailableException");

            assertThat(export.getStatus()).isEqualTo(DataExportStatus.FAILED);
            assertThat(export.getFailure()).isEqualTo("StorageUnavailableException");
            assertThat(export.getLeaseUntil()).isNull();
            verify(eventPublisher).publishEvent(new DataExportFailed(EXPORT_ID, OWNER_ID, DataExportType.TASKS_CSV));
        }

        @Test
        void failureKindIsCutToItsColumn() {
            DataExport export = export(DataExportStatus.RUNNING);
            when(repository.findById(EXPORT_ID)).thenReturn(Optional.of(export));

            service.fail(EXPORT_ID, "F".repeat(150));

            assertThat(export.getFailure()).isEqualTo("F".repeat(100));
        }

        @ParameterizedTest
        @EnumSource(value = DataExportStatus.class, names = "RUNNING", mode = EnumSource.Mode.EXCLUDE)
        void exportThatIsNotRunningIsLeftAsItIs(DataExportStatus status) {
            DataExport export = export(status);
            when(repository.findById(EXPORT_ID)).thenReturn(Optional.of(export));

            service.fail(EXPORT_ID, "IllegalStateException");

            assertThat(export.getStatus()).isEqualTo(status);
            assertThat(export.getFailure()).isNull();
            verifyNoInteractions(eventPublisher);
        }

        @Test
        void unknownExportIsIgnored() {
            service.fail(EXPORT_ID, "IllegalStateException");

            verifyNoInteractions(eventPublisher);
        }
    }

    @Nested
    class RequeueInterrupted {

        private DataExport leaseRanOut(DataExportStatus status, int attempts) {
            DataExport export = export(status);
            export.setId(UUID.randomUUID());
            export.setAttempts(attempts);
            export.setLeaseUntil(NOW.minusSeconds(1));
            return export;
        }

        private void leaseRanOutFor(DataExport... exports) {
            when(repository.findByStatusInAndLeaseUntilBefore(DataExportStatus.ACTIVE, NOW))
                    .thenReturn(List.of(exports));
        }

        @Test
        void runningExportsWithAttemptsLeftAreQueuedAgainWithANewLease() {
            DataExport first = leaseRanOut(DataExportStatus.RUNNING, 1);
            DataExport second = leaseRanOut(DataExportStatus.RUNNING, MAX_ATTEMPTS - 1);
            leaseRanOutFor(first, second);

            assertThat(service.requeueInterrupted()).isEqualTo(2);

            for (DataExport export : List.of(first, second)) {
                assertThat(export.getStatus()).isEqualTo(DataExportStatus.QUEUED);
                assertThat(export.getLeaseUntil()).isEqualTo(NOW.plus(LEASE));
                verify(outbox).enqueue(ExportQueues.RUN, new DataExportRequested(export.getId()));
            }
            verifyNoInteractions(eventPublisher);
        }

        @Test
        void queuedExportWhoseMessageWasLostIsSentAgainWithANewLease() {
            DataExport lost = leaseRanOut(DataExportStatus.QUEUED, 0);
            leaseRanOutFor(lost);

            assertThat(service.requeueInterrupted()).isOne();

            assertThat(lost.getStatus()).isEqualTo(DataExportStatus.QUEUED);
            assertThat(lost.getLeaseUntil()).isEqualTo(NOW.plus(LEASE));
            assertThat(lost.getAttempts()).isZero();
            verify(outbox).enqueue(ExportQueues.RUN, new DataExportRequested(lost.getId()));
        }

        // Only a run counts as an attempt: a queued export is sent again whatever its attempts
        @Test
        void queuedExportIsSentAgainEvenAfterItsLastAttempt() {
            DataExport lost = leaseRanOut(DataExportStatus.QUEUED, MAX_ATTEMPTS);
            leaseRanOutFor(lost);

            assertThat(service.requeueInterrupted()).isOne();

            assertThat(lost.getStatus()).isEqualTo(DataExportStatus.QUEUED);
            verify(outbox).enqueue(ExportQueues.RUN, new DataExportRequested(lost.getId()));
            verifyNoInteractions(eventPublisher);
        }

        @Test
        void runningExportThatUsedEveryAttemptFailsAndItsOwnerIsTold() {
            DataExport exhausted = leaseRanOut(DataExportStatus.RUNNING, MAX_ATTEMPTS);
            leaseRanOutFor(exhausted);
            when(repository.findById(exhausted.getId())).thenReturn(Optional.of(exhausted));

            assertThat(service.requeueInterrupted()).isZero();

            assertThat(exhausted.getStatus()).isEqualTo(DataExportStatus.FAILED);
            assertThat(exhausted.getFailure()).isEqualTo("Interrupted");
            assertThat(exhausted.getLeaseUntil()).isNull();
            verifyNoInteractions(outbox);
            verify(eventPublisher).publishEvent(
                    new DataExportFailed(exhausted.getId(), OWNER_ID, DataExportType.TASKS_CSV));
        }

        @Test
        void withoutLeaseThatRanOutNothingIsQueued() {
            assertThat(service.requeueInterrupted()).isZero();

            verifyNoInteractions(outbox, eventPublisher);
        }
    }

    @Nested
    class RenewLease {

        @Test
        void leaseOfARunningExportIsPushedToALeaseFromNow() {
            service.renewLease(EXPORT_ID);

            verify(repository).renewLease(EXPORT_ID, NOW.plus(LEASE), DataExportStatus.RUNNING);
        }
    }

    @Nested
    class Purge {

        @Test
        void filesPastTheirRetentionAreDeletedAndTheirExportsStayListedAsExpired() {
            DataExport expired = export(DataExportStatus.COMPLETED);
            Media media = media();
            expired.setMedia(media);
            when(repository.findByStatusAndExpiresAtBefore(DataExportStatus.COMPLETED, NOW))
                    .thenReturn(List.of(expired));

            assertThat(service.purge()).isOne();

            assertThat(expired.getStatus()).isEqualTo(DataExportStatus.EXPIRED);
            assertThat(expired.getMedia()).isNull();
            verify(mediaService).delete(media);
            verify(repository, never()).delete(any());
        }

        @Test
        void expiredAndFailedExportsOlderThanTheHistoryRetentionAreDeletedAfterTheFiles() {
            DataExport expired = export(DataExportStatus.COMPLETED);
            Media media = media();
            expired.setMedia(media);
            when(repository.findByStatusAndExpiresAtBefore(DataExportStatus.COMPLETED, NOW))
                    .thenReturn(List.of(expired));

            service.purge();

            InOrder order = inOrder(mediaService, repository);
            order.verify(mediaService).delete(media);
            order.verify(repository).deleteByStatusInAndCreatedAtBefore(
                    Set.of(DataExportStatus.EXPIRED, DataExportStatus.FAILED), NOW.minus(HISTORY_RETENTION));
        }

        @Test
        void withoutExpiredFileTheOldHistoryIsStillDeleted() {
            assertThat(service.purge()).isZero();

            verify(repository).deleteByStatusInAndCreatedAtBefore(
                    Set.of(DataExportStatus.EXPIRED, DataExportStatus.FAILED), NOW.minus(HISTORY_RETENTION));
            verifyNoInteractions(mediaService);
        }
    }
}
