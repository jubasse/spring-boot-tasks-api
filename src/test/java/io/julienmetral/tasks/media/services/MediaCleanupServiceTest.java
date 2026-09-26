package io.julienmetral.tasks.media.services;

import io.julienmetral.tasks.config.MediaCleanupProperties;
import io.julienmetral.tasks.media.exceptions.StorageUnavailableException;
import io.julienmetral.tasks.media.model.MediaCleanupReport;
import io.julienmetral.tasks.media.repositories.MediaCleanupQueries;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

@ExtendWith({MockitoExtension.class, OutputCaptureExtension.class})
class MediaCleanupServiceTest {

    private static final Instant NOW = Instant.parse("2026-09-26T03:30:00Z");

    private static final Duration RETENTION = Duration.ofDays(30);

    private static final Duration GRACE_PERIOD = Duration.ofDays(1);

    private static final Instant RETENTION_CUTOFF = Instant.parse("2026-08-27T03:30:00Z");

    private static final Instant GRACE_CUTOFF = Instant.parse("2026-09-25T03:30:00Z");

    @Mock
    private MediaCleanupQueries queries;

    @Mock
    private ObjectStorage objectStorage;

    private MediaCleanupService service;

    @BeforeEach
    void setUp() {
        MediaCleanupProperties properties = new MediaCleanupProperties(true, "0 30 3 * * *", RETENTION, GRACE_PERIOD);
        service = new MediaCleanupService(queries, objectStorage, properties, Clock.fixed(NOW, ZoneOffset.UTC));
        TransactionSynchronizationManager.initSynchronization();
    }

    @AfterEach
    void tearDown() {
        TransactionSynchronizationManager.clearSynchronization();
    }

    @Test
    void skipsTheRunWhenAnotherInstanceHoldsTheLock() {
        when(queries.tryLock()).thenReturn(false);

        MediaCleanupReport report = service.cleanUp();

        assertThat(report).isEqualTo(MediaCleanupReport.skippedRun());
        assertThat(report.skipped()).isTrue();
        verify(queries).tryLock();
        verifyNoMoreInteractions(queries);
        verifyNoInteractions(objectStorage);
        assertThat(TransactionSynchronizationManager.getSynchronizations()).isEmpty();
    }

    @Test
    void purgesWithCutoffsComputedFromTheClockAndTheProperties() {
        lockAcquired();
        when(queries.detachAttachmentsOfTasksDeletedBefore(RETENTION_CUTOFF))
                .thenReturn(List.of(UUID.randomUUID(), UUID.randomUUID()));
        when(queries.detachAvatarsOfUsersDeletedBefore(RETENTION_CUTOFF)).thenReturn(List.of(UUID.randomUUID()));
        when(queries.deleteUnreferencedMediaCreatedBefore(GRACE_CUTOFF))
                .thenReturn(List.of("avatar/a", "task-attachment/b", "task-attachment/c"));
        List<String> oldObjects = List.of("task-attachment/kept", "avatar/orphan");
        when(objectStorage.listKeysModifiedBefore(GRACE_CUTOFF)).thenReturn(oldObjects);
        when(queries.existingStorageKeys(oldObjects)).thenReturn(Set.of("task-attachment/kept"));

        MediaCleanupReport report = service.cleanUp();

        assertThat(report).isEqualTo(new MediaCleanupReport(false, 2, 1, 3, 1));
        InOrder order = inOrder(queries, objectStorage);
        order.verify(queries).tryLock();
        order.verify(queries).detachAttachmentsOfTasksDeletedBefore(RETENTION_CUTOFF);
        order.verify(queries).detachAvatarsOfUsersDeletedBefore(RETENTION_CUTOFF);
        order.verify(queries).deleteUnreferencedMediaCreatedBefore(GRACE_CUTOFF);
        order.verify(objectStorage).listKeysModifiedBefore(GRACE_CUTOFF);
        order.verify(queries).existingStorageKeys(oldObjects);
    }

    @Test
    void deletesObjectsOnlyAfterCommit() {
        lockAcquired();
        when(queries.deleteUnreferencedMediaCreatedBefore(GRACE_CUTOFF)).thenReturn(List.of("avatar/unreferenced"));
        when(objectStorage.listKeysModifiedBefore(GRACE_CUTOFF)).thenReturn(List.of("avatar/orphan"));
        when(queries.existingStorageKeys(List.of("avatar/orphan"))).thenReturn(Set.of());

        service.cleanUp();

        verify(objectStorage, never()).delete(any());
        commit();
        InOrder order = inOrder(objectStorage);
        order.verify(objectStorage).delete("avatar/unreferenced");
        order.verify(objectStorage).delete("avatar/orphan");
    }

    @Test
    void neverDeletesObjectsStillReferencedByAMediaRow() {
        lockAcquired();
        List<String> oldObjects = List.of("avatar/referenced", "task-attachment/referenced");
        when(objectStorage.listKeysModifiedBefore(GRACE_CUTOFF)).thenReturn(oldObjects);
        when(queries.existingStorageKeys(oldObjects)).thenReturn(Set.copyOf(oldObjects));

        MediaCleanupReport report = service.cleanUp();

        assertThat(report).isEqualTo(new MediaCleanupReport(false, 0, 0, 0, 0));
        assertThat(TransactionSynchronizationManager.getSynchronizations()).isEmpty();
        verify(objectStorage, never()).delete(any());
    }

    @Test
    void anOrphanObjectOfADeletedMediaRowIsDeletedAndCountedOnce() {
        lockAcquired();
        when(queries.deleteUnreferencedMediaCreatedBefore(GRACE_CUTOFF)).thenReturn(List.of("avatar/shared"));
        List<String> oldObjects = List.of("avatar/shared", "avatar/orphan");
        when(objectStorage.listKeysModifiedBefore(GRACE_CUTOFF)).thenReturn(oldObjects);
        when(queries.existingStorageKeys(oldObjects)).thenReturn(Set.of());

        MediaCleanupReport report = service.cleanUp();

        assertThat(report.deletedMedia()).isEqualTo(1);
        assertThat(report.deletedOrphanObjects()).isEqualTo(1);
        commit();
        verify(objectStorage).delete("avatar/shared");
        verify(objectStorage).delete("avatar/orphan");
        verify(objectStorage).listKeysModifiedBefore(GRACE_CUTOFF);
        verifyNoMoreInteractions(objectStorage);
    }

    @Test
    void registersNoSynchronizationWhenThereIsNothingToDelete() {
        lockAcquired();
        when(objectStorage.listKeysModifiedBefore(GRACE_CUTOFF)).thenReturn(List.of());
        when(queries.existingStorageKeys(List.of())).thenReturn(Set.of());

        MediaCleanupReport report = service.cleanUp();

        assertThat(report).isEqualTo(new MediaCleanupReport(false, 0, 0, 0, 0));
        assertThat(TransactionSynchronizationManager.getSynchronizations()).isEmpty();
    }

    @Test
    void aFailingObjectDeleteIsLoggedAndDoesNotStopTheOthers(CapturedOutput output) {
        lockAcquired();
        when(queries.deleteUnreferencedMediaCreatedBefore(GRACE_CUTOFF))
                .thenReturn(List.of("avatar/first", "avatar/failing", "avatar/last"));
        when(objectStorage.listKeysModifiedBefore(GRACE_CUTOFF)).thenReturn(List.of());
        when(queries.existingStorageKeys(List.of())).thenReturn(Set.of());
        lenient().doThrow(new StorageUnavailableException(new RuntimeException("unreachable")))
                .when(objectStorage).delete("avatar/failing");

        service.cleanUp();
        commit();

        verify(objectStorage).delete("avatar/first");
        verify(objectStorage).delete("avatar/failing");
        verify(objectStorage).delete("avatar/last");
        assertThat(output).contains("Could not delete object avatar/failing during media cleanup");
        assertThat(output).doesNotContain("Could not delete object avatar/first");
    }

    private void lockAcquired() {
        when(queries.tryLock()).thenReturn(true);
    }

    private static void commit() {
        List<TransactionSynchronization> synchronizations = TransactionSynchronizationManager.getSynchronizations();
        assertThat(synchronizations).hasSize(1);
        synchronizations.forEach(TransactionSynchronization::afterCommit);
    }
}
