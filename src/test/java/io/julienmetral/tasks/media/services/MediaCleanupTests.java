package io.julienmetral.tasks.media.services;

import io.julienmetral.tasks.TestcontainersConfiguration;
import io.julienmetral.tasks.identity.entities.User;
import io.julienmetral.tasks.media.model.Media;
import io.julienmetral.tasks.media.model.MediaCleanupReport;
import io.julienmetral.tasks.media.model.MediaUsage;
import io.julienmetral.tasks.media.repositories.MediaCleanupQueries;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.util.ReflectionTestUtils;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@Import(TestcontainersConfiguration.class)
@SpringBootTest
class MediaCleanupTests extends AbstractMediaCleanupTests {

    private static final long LOCK_KEY = (long) ReflectionTestUtils.getField(MediaCleanupQueries.class, "LOCK_KEY");

    @Autowired
    private DataSource dataSource;

    @Test
    void attachmentsOfTaskDeletedBeyondRetentionArePurgedWithTheirObjects() {
        User user = createUser();
        UUID taskId = createTask(user);
        Media first = attachedToTask(taskId, user);
        Media second = attachedToTask(taskId, user);
        backdateMedia(first, BEYOND_RETENTION);
        backdateMedia(second, BEYOND_RETENTION);
        softDeleteTask(taskId, BEYOND_RETENTION);

        MediaCleanupReport report = cleanupService.cleanUp();

        assertThat(report.skipped()).isFalse();
        assertThat(report.detachedAttachments()).isGreaterThanOrEqualTo(2);
        assertThat(report.deletedMedia()).isGreaterThanOrEqualTo(2);
        for (Media media : new Media[]{first, second}) {
            assertThat(isAttached(media)).isFalse();
            assertThat(mediaExists(media)).isFalse();
            assertThat(objectExists(media.getStorageKey())).isFalse();
        }
    }

    @Test
    void attachmentsOfTaskDeletedWithinRetentionAreKept() {
        User user = createUser();
        UUID taskId = createTask(user);
        Media media = attachedToTask(taskId, user);
        backdateMedia(media, BEYOND_RETENTION);
        softDeleteTask(taskId, WITHIN_RETENTION);

        cleanupService.cleanUp();

        assertKeptAsAttachment(media);
    }

    @Test
    void oldAttachmentsOfLiveTaskAreKept() {
        User user = createUser();
        UUID taskId = createTask(user);
        Media media = attachedToTask(taskId, user);
        backdateMedia(media, BEYOND_RETENTION.multipliedBy(12));

        cleanupService.cleanUp();

        assertKeptAsAttachment(media);
    }

    @Test
    void avatarOfUserDeletedBeyondRetentionIsPurgedWithItsObject() {
        User user = createUser();
        Media avatar = avatarOf(user);
        backdateMedia(avatar, BEYOND_RETENTION);
        softDeleteUser(user, BEYOND_RETENTION);

        MediaCleanupReport report = cleanupService.cleanUp();

        assertThat(report.detachedAvatars()).isGreaterThanOrEqualTo(1);
        assertThat(report.deletedMedia()).isGreaterThanOrEqualTo(1);
        assertThat(avatarMediaIdOf(user)).isNull();
        assertThat(mediaExists(avatar)).isFalse();
        assertThat(objectExists(avatar.getStorageKey())).isFalse();
    }

    @Test
    void avatarOfUserDeletedWithinRetentionIsKept() {
        User user = createUser();
        Media avatar = avatarOf(user);
        backdateMedia(avatar, BEYOND_RETENTION);
        softDeleteUser(user, WITHIN_RETENTION);

        cleanupService.cleanUp();

        assertKeptAsAvatar(user, avatar);
    }

    @Test
    void oldAvatarOfLiveUserIsKept() {
        User user = createUser();
        Media avatar = avatarOf(user);
        backdateMedia(avatar, BEYOND_RETENTION.multipliedBy(12));

        cleanupService.cleanUp();

        assertKeptAsAvatar(user, avatar);
    }

    @Test
    void unreferencedMediaOlderThanGracePeriodIsDeletedWithItsObject() {
        User user = createUser();
        Media attachment = storeAttachment(user);
        Media avatar = storeAvatar(user);
        backdateMedia(attachment, BEYOND_GRACE_PERIOD);
        backdateMedia(avatar, BEYOND_GRACE_PERIOD);

        MediaCleanupReport report = cleanupService.cleanUp();

        assertThat(report.deletedMedia()).isGreaterThanOrEqualTo(2);
        for (Media media : new Media[]{attachment, avatar}) {
            assertThat(mediaExists(media)).isFalse();
            assertThat(objectExists(media.getStorageKey())).isFalse();
        }
    }

    @Test
    void unreferencedMediaWithinGracePeriodIsKept() {
        User user = createUser();
        Media media = storeAttachment(user);

        cleanupService.cleanUp();

        assertThat(mediaExists(media)).isTrue();
        assertThat(objectExists(media.getStorageKey())).isTrue();
    }

    @Test
    void unreferencedMediaWhoseObjectIsAlreadyGoneIsDeleted() {
        UUID mediaId = jdbcTemplate.queryForObject(
                """
                        insert into media (storage_key, usage, original_filename, content_type, size_bytes, sha256, created_at)
                        values (?, 'TASK_ATTACHMENT', 'lost.pdf', 'application/pdf', 1, ?, ?)
                        returning id
                        """,
                UUID.class,
                MediaUsage.TASK_ATTACHMENT.storagePrefix() + "/" + UUID.randomUUID(),
                "0".repeat(64),
                Timestamp.from(clock.instant().minus(BEYOND_GRACE_PERIOD))
        );

        MediaCleanupReport report = cleanupService.cleanUp();

        assertThat(report.skipped()).isFalse();
        assertThat(jdbcTemplate.queryForObject(
                "select exists (select 1 from media where id = ?)",
                Boolean.class,
                mediaId
        )).isFalse();
    }

    @Test
    void recentObjectWithoutMediaRowIsKept() {
        String key = putObjectWithoutMediaRow();

        cleanupService.cleanUp();

        assertThat(objectExists(key)).isTrue();
    }

    @Test
    void secondRunRightAfterDeletesNothingMore() {
        User user = createUser();
        UUID deletedTaskId = createTask(user);
        Media expiredAttachment = attachedToTask(deletedTaskId, user);
        backdateMedia(expiredAttachment, BEYOND_RETENTION);
        softDeleteTask(deletedTaskId, BEYOND_RETENTION);
        Media keptAttachment = attachedToTask(createTask(user), user);
        backdateMedia(keptAttachment, BEYOND_RETENTION);
        Media keptAvatar = avatarOf(user);

        cleanupService.cleanUp();
        MediaCleanupReport second = cleanupService.cleanUp();

        assertThat(second).isEqualTo(new MediaCleanupReport(false, 0, 0, 0, 0));
        assertThat(mediaExists(expiredAttachment)).isFalse();
        assertKeptAsAttachment(keptAttachment);
        assertKeptAsAvatar(user, keptAvatar);
    }

    @Test
    void runIsSkippedWhileAnotherTransactionHoldsTheLock() throws SQLException {
        User user = createUser();
        UUID taskId = createTask(user);
        Media attachment = attachedToTask(taskId, user);
        backdateMedia(attachment, BEYOND_RETENTION);
        softDeleteTask(taskId, BEYOND_RETENTION);
        Media orphan = storeAttachment(user);
        backdateMedia(orphan, BEYOND_GRACE_PERIOD);

        try (Connection otherInstance = dataSource.getConnection()) {
            otherInstance.setAutoCommit(false);
            try {
                assertThat(tryLock(otherInstance)).isTrue();

                assertThat(cleanupService.cleanUp()).isEqualTo(MediaCleanupReport.skippedRun());
            } finally {
                otherInstance.rollback();
                otherInstance.setAutoCommit(true);
            }
        }

        assertKeptAsAttachment(attachment);
        assertThat(mediaExists(orphan)).isTrue();
        assertThat(objectExists(orphan.getStorageKey())).isTrue();

        MediaCleanupReport afterRelease = cleanupService.cleanUp();

        assertThat(afterRelease.skipped()).isFalse();
        assertThat(isAttached(attachment)).isFalse();
        assertThat(mediaExists(attachment)).isFalse();
        assertThat(mediaExists(orphan)).isFalse();
        assertThat(objectExists(orphan.getStorageKey())).isFalse();
    }

    @Test
    void lockIsReleasedWhenTheRunCommits() throws SQLException {
        cleanupService.cleanUp();

        assertThat(jdbcTemplate.queryForObject(
                """
                        select exists (
                            select 1 from pg_locks
                            where locktype = 'advisory' and ((classid::bigint << 32) | objid::bigint) = ?
                        )
                        """,
                Boolean.class,
                LOCK_KEY
        )).isFalse();

        try (Connection otherInstance = dataSource.getConnection()) {
            otherInstance.setAutoCommit(false);
            try {
                assertThat(tryLock(otherInstance)).isTrue();
            } finally {
                otherInstance.rollback();
                otherInstance.setAutoCommit(true);
            }
        }
    }

    @Test
    void callerRollbackKeepsRowsAndObjects() {
        User user = createUser();
        Media orphan = storeAttachment(user);
        backdateMedia(orphan, BEYOND_GRACE_PERIOD);
        Media avatar = avatarOf(user);
        backdateMedia(avatar, BEYOND_RETENTION);
        softDeleteUser(user, BEYOND_RETENTION);

        transactionTemplate.executeWithoutResult(status -> {
            assertThat(cleanupService.cleanUp().skipped()).isFalse();
            status.setRollbackOnly();
        });

        assertThat(mediaExists(orphan)).isTrue();
        assertThat(objectExists(orphan.getStorageKey())).isTrue();
        assertKeptAsAvatar(user, avatar);
    }

    private void assertKeptAsAttachment(Media media) {
        assertThat(isAttached(media)).isTrue();
        assertThat(mediaExists(media)).isTrue();
        assertThat(objectExists(media.getStorageKey())).isTrue();
    }

    private void assertKeptAsAvatar(User user, Media avatar) {
        assertThat(avatarMediaIdOf(user)).isEqualTo(avatar.getId());
        assertThat(mediaExists(avatar)).isTrue();
        assertThat(objectExists(avatar.getStorageKey())).isTrue();
    }

    private static boolean tryLock(Connection connection) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("select pg_try_advisory_xact_lock(?)")) {
            statement.setLong(1, LOCK_KEY);
            try (ResultSet result = statement.executeQuery()) {
                result.next();
                return result.getBoolean(1);
            }
        }
    }
}
