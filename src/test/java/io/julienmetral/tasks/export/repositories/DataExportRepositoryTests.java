package io.julienmetral.tasks.export.repositories;

import io.julienmetral.tasks.export.entities.DataExport;
import io.julienmetral.tasks.export.entities.DataExportStatus;
import io.julienmetral.tasks.export.entities.DataExportType;
import io.julienmetral.tasks.export.entities.TaskExportFilters;
import io.julienmetral.tasks.identity.entities.User;
import io.julienmetral.tasks.identity.entities.UserProfile;
import io.julienmetral.tasks.identity.entities.UserRole;
import io.julienmetral.tasks.media.model.Media;
import io.julienmetral.tasks.media.model.MediaUsage;
import io.julienmetral.tasks.support.RepositoryTest;
import io.julienmetral.tasks.task.entities.TaskStatus;
import org.hibernate.Hibernate;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.jpa.test.autoconfigure.TestEntityManager;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;

import java.time.Duration;
import java.time.Instant;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.function.Consumer;

import static org.assertj.core.api.Assertions.assertThat;

@RepositoryTest
class DataExportRepositoryTests {

    // Every date sits in 2000, before anything the other test contexts write to the shared database: the cutoffs
    // below reach only this class's exports
    private static final Instant NOW = Instant.parse("2000-06-01T12:00:00Z");

    private static final Instant LEASE_UNTIL = NOW.plus(Duration.ofMinutes(15));

    @Autowired
    private DataExportRepository repository;

    @Autowired
    private TestEntityManager entityManager;

    @Test
    void claimTakesAQueuedExportForTheLease() {
        DataExport queued = persistExport(persistOwner(), DataExportStatus.QUEUED, export -> { });

        int claimed = claim(queued);

        DataExport running = reload(queued);
        assertThat(claimed).isOne();
        assertThat(running.getStatus()).isEqualTo(DataExportStatus.RUNNING);
        assertThat(running.getLeaseUntil()).isEqualTo(LEASE_UNTIL);
        assertThat(running.getStartedAt()).isEqualTo(NOW);
        assertThat(running.getAttempts()).isOne();
    }

    @Test
    void claimLeavesAnExportRunningUnderALiveLease() {
        DataExport running = persistExport(persistOwner(), DataExportStatus.RUNNING, export -> {
            export.setAttempts(1);
            export.setLeaseUntil(NOW.plusSeconds(1));
        });

        assertThat(claim(running)).isZero();

        DataExport unchanged = reload(running);
        assertThat(unchanged.getAttempts()).isOne();
        assertThat(unchanged.getLeaseUntil()).isEqualTo(NOW.plusSeconds(1));
    }

    @Test
    void claimTakesARunningExportOnceItsLeaseRanOutAndCountsTheAttempt() {
        DataExport interrupted = persistExport(persistOwner(), DataExportStatus.RUNNING, export -> {
            export.setAttempts(1);
            export.setLeaseUntil(NOW.minusSeconds(1));
            export.setFailure("Interrupted");
        });

        assertThat(claim(interrupted)).isOne();

        DataExport running = reload(interrupted);
        assertThat(running.getStatus()).isEqualTo(DataExportStatus.RUNNING);
        assertThat(running.getAttempts()).isEqualTo(2);
        assertThat(running.getLeaseUntil()).isEqualTo(LEASE_UNTIL);
        assertThat(running.getFailure()).isNull();
    }

    @ParameterizedTest
    @EnumSource(value = DataExportStatus.class, names = {"COMPLETED", "FAILED", "EXPIRED"})
    void claimLeavesAnExportThatEnded(DataExportStatus status) {
        DataExport ended = persistExport(persistOwner(), status, export -> export.setAttempts(1));

        assertThat(claim(ended)).isZero();
        assertThat(reload(ended).getStatus()).isEqualTo(status);
    }

    @Test
    void secondClaimOfTheSameExportFindsItRunning() {
        DataExport queued = persistExport(persistOwner(), DataExportStatus.QUEUED, export -> { });

        assertThat(claim(queued)).isOne();
        assertThat(claim(queued)).isZero();
        assertThat(reload(queued).getAttempts()).isOne();
    }

    @Test
    void exportsToQueueAgainAreTheQueuedAndRunningOnesWhoseLeaseRanOut() {
        UserProfile owner = persistOwner();
        DataExport interrupted = persistExport(owner, DataExportStatus.RUNNING,
                export -> export.setLeaseUntil(NOW.minusSeconds(1)));
        DataExport lostMessage = persistExport(owner, DataExportStatus.QUEUED,
                export -> export.setLeaseUntil(NOW.minusSeconds(1)));
        DataExport stillRunning = persistExport(owner, DataExportStatus.RUNNING,
                export -> export.setLeaseUntil(NOW.plusSeconds(1)));
        DataExport stillQueued = persistExport(owner, DataExportStatus.QUEUED,
                export -> export.setLeaseUntil(NOW.plusSeconds(1)));
        DataExport failed = persistExport(owner, DataExportStatus.FAILED,
                export -> export.setLeaseUntil(NOW.minusSeconds(1)));

        List<UUID> found = ids(repository.findByStatusInAndLeaseUntilBefore(DataExportStatus.ACTIVE, NOW));

        assertThat(found)
                .contains(interrupted.getId(), lostMessage.getId())
                .doesNotContain(stillRunning.getId(), stillQueued.getId(), failed.getId());
    }

    @Test
    void renewingTheLeaseOfARunningExportPushesItForward() {
        DataExport running = persistExport(persistOwner(), DataExportStatus.RUNNING, export -> {
            export.setAttempts(1);
            export.setLeaseUntil(NOW.minusSeconds(1));
        });

        assertThat(repository.renewLease(running.getId(), LEASE_UNTIL, DataExportStatus.RUNNING)).isOne();

        DataExport renewed = reload(running);
        assertThat(renewed.getLeaseUntil()).isEqualTo(LEASE_UNTIL);
        assertThat(renewed.getStatus()).isEqualTo(DataExportStatus.RUNNING);
        assertThat(renewed.getAttempts()).isOne();
    }

    // A run that ended, or was queued again by the recovery, must not take its lease back
    @ParameterizedTest
    @EnumSource(value = DataExportStatus.class, names = "RUNNING", mode = EnumSource.Mode.EXCLUDE)
    void renewingTheLeaseOfAnExportThatIsNotRunningChangesNothing(DataExportStatus status) {
        DataExport notRunning = persistExport(persistOwner(), status,
                export -> export.setLeaseUntil(NOW.minusSeconds(1)));

        assertThat(repository.renewLease(notRunning.getId(), LEASE_UNTIL, DataExportStatus.RUNNING)).isZero();
        assertThat(reload(notRunning).getLeaseUntil()).isEqualTo(NOW.minusSeconds(1));
    }

    @Test
    void expiredFilesAreThoseOfCompletedExportsPastTheirExpiryLoadedWithTheirMedia() {
        UserProfile owner = persistOwner();
        DataExport expired = persistExport(owner, DataExportStatus.COMPLETED, export -> {
            export.setMedia(persistMedia());
            export.setExpiresAt(NOW.minusSeconds(1));
        });
        DataExport stillAvailable = persistExport(owner, DataExportStatus.COMPLETED, export -> {
            export.setMedia(persistMedia());
            export.setExpiresAt(NOW.plusSeconds(1));
        });
        entityManager.clear();

        List<DataExport> found = repository.findByStatusAndExpiresAtBefore(DataExportStatus.COMPLETED, NOW);

        assertThat(ids(found)).contains(expired.getId()).doesNotContain(stillAvailable.getId());
        assertThat(found).allSatisfy(export -> assertThat(Hibernate.isInitialized(export.getMedia())).isTrue());
    }

    @Test
    void oldHistoryDeletionTakesOnlyTheGivenStatusesCreatedBeforeTheCutoff() {
        UserProfile owner = persistOwner();
        DataExport oldExpired = persistExport(owner, DataExportStatus.EXPIRED, NOW.minusSeconds(1));
        DataExport oldFailed = persistExport(owner, DataExportStatus.FAILED, NOW.minusSeconds(1));
        DataExport oldCompleted = persistExport(owner, DataExportStatus.COMPLETED, NOW.minusSeconds(1));
        DataExport recentExpired = persistExport(owner, DataExportStatus.EXPIRED, NOW);

        int deleted = repository.deleteByStatusInAndCreatedAtBefore(
                Set.of(DataExportStatus.EXPIRED, DataExportStatus.FAILED), NOW);
        entityManager.clear();

        assertThat(deleted).isEqualTo(2);
        assertThat(repository.findById(oldExpired.getId())).isEmpty();
        assertThat(repository.findById(oldFailed.getId())).isEmpty();
        assertThat(repository.findById(oldCompleted.getId())).isPresent();
        assertThat(repository.findById(recentExpired.getId())).isPresent();
    }

    @Test
    void exportInProgressIsFoundForItsOwnerAndTypeOnly() {
        UserProfile owner = persistOwner();
        persistExport(owner, DataExportStatus.RUNNING, export -> { });

        assertThat(repository.existsByOwnerIdAndTypeAndStatusIn(
                owner.getId(), DataExportType.TASKS_CSV, DataExportStatus.ACTIVE)).isTrue();
        assertThat(repository.existsByOwnerIdAndTypeAndStatusIn(
                owner.getId(), DataExportType.USERS_CSV, DataExportStatus.ACTIVE)).isFalse();
        assertThat(repository.existsByOwnerIdAndTypeAndStatusIn(
                persistOwner().getId(), DataExportType.TASKS_CSV, DataExportStatus.ACTIVE)).isFalse();
    }

    @Test
    void endedExportIsNotInProgress() {
        UserProfile owner = persistOwner();
        persistExport(owner, DataExportStatus.COMPLETED, export -> { });
        persistExport(owner, DataExportStatus.FAILED, export -> { });

        assertThat(repository.existsByOwnerIdAndTypeAndStatusIn(
                owner.getId(), DataExportType.TASKS_CSV, DataExportStatus.ACTIVE)).isFalse();
    }

    @Test
    void ownerSeesOnlyTheirExports() {
        UserProfile owner = persistOwner();
        UserProfile someoneElse = persistOwner();
        DataExport older = persistExport(owner, DataExportStatus.EXPIRED, NOW.minusSeconds(60));
        DataExport newer = persistExport(owner, DataExportStatus.COMPLETED, NOW);
        DataExport ofSomeoneElse = persistExport(someoneElse, DataExportStatus.COMPLETED, NOW);

        List<DataExport> page = repository.findByOwnerId(
                owner.getId(), PageRequest.of(0, 10, Sort.by(Sort.Direction.DESC, "createdAt"))).getContent();

        assertThat(ids(page)).containsExactly(newer.getId(), older.getId());
        assertThat(repository.findByIdAndOwnerId(newer.getId(), owner.getId())).isPresent();
        assertThat(repository.findByIdAndOwnerId(ofSomeoneElse.getId(), owner.getId())).isEmpty();
    }

    @Test
    void filtersOfATasksExportAreKept() {
        UUID assignee = UUID.randomUUID();
        DataExport export = persistExport(persistOwner(), DataExportStatus.QUEUED,
                queued -> queued.setTaskFilters(new TaskExportFilters(TaskStatus.BLOCKED, assignee, true)));

        assertThat(reload(export).getTaskFilters())
                .isEqualTo(new TaskExportFilters(TaskStatus.BLOCKED, assignee, true));
    }

    private int claim(DataExport export) {
        return repository.claim(
                export.getId(), NOW, LEASE_UNTIL, DataExportStatus.QUEUED, DataExportStatus.RUNNING);
    }

    // The bulk updates bypass the persistence context: read the row again
    private DataExport reload(DataExport export) {
        entityManager.clear();
        return entityManager.find(DataExport.class, export.getId());
    }

    private UserProfile persistOwner() {
        User user = new User();
        user.setEmail("exports-" + UUID.randomUUID() + "@example.com");
        user.setPasswordHash("!");
        user.setDisplayName("Export owner");
        user.setRoles(new HashSet<>(Set.of(UserRole.USER)));
        return entityManager.persistAndFlush(user).getProfile();
    }

    private Media persistMedia() {
        Media media = new Media();
        media.setStorageKey(MediaUsage.EXPORT.storagePrefix() + "/" + UUID.randomUUID());
        media.setUsage(MediaUsage.EXPORT);
        media.setOriginalFilename("tasks-2000-06-01.csv");
        media.setContentType("text/csv");
        media.setSizeBytes(10);
        media.setSha256("0".repeat(64));
        media.setCreatedAt(NOW);
        return entityManager.persistAndFlush(media);
    }

    private DataExport persistExport(UserProfile owner, DataExportStatus status, Instant createdAt) {
        return persistExport(owner, status, export -> export.setCreatedAt(createdAt));
    }

    private DataExport persistExport(UserProfile owner, DataExportStatus status,
                                     Consumer<DataExport> details) {
        DataExport export = new DataExport();
        export.setOwner(owner);
        export.setType(DataExportType.TASKS_CSV);
        export.setStatus(status);
        export.setCreatedAt(NOW.minus(Duration.ofMinutes(5)));
        details.accept(export);
        return entityManager.persistAndFlush(export);
    }

    private static List<UUID> ids(List<DataExport> exports) {
        return exports.stream().map(DataExport::getId).toList();
    }
}
