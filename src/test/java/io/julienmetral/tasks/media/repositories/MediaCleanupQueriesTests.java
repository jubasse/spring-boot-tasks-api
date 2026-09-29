package io.julienmetral.tasks.media.repositories;

import io.julienmetral.tasks.support.JdbcSliceTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@JdbcSliceTest
class MediaCleanupQueriesTests {

    // Every date sits in 2000, before anything the other test contexts write to the shared database: the cutoffs
    // below reach only this class's rows
    private static final Instant NOW = Instant.parse("2000-06-01T00:00:00Z");

    private static final Instant RETENTION_CUTOFF = NOW.minus(Duration.ofDays(30));

    private static final Instant GRACE_CUTOFF = NOW.minus(Duration.ofDays(1));

    private static final Instant LONG_AGO = NOW.minus(Duration.ofDays(365));

    @Autowired
    private MediaCleanupQueries queries;

    @Autowired
    private JdbcTemplate jdbc;

    // Attachments of deleted tasks

    @Test
    void detachingAttachmentsOfTasksDeletedBeforeTheCutoffReturnsTheirMediaAndKeepsTheRows() {
        UUID task = insertTask(RETENTION_CUTOFF.minusSeconds(1));
        UUID first = insertMedia(LONG_AGO);
        UUID second = insertMedia(LONG_AGO);
        attach(task, first);
        attach(task, second);

        List<UUID> detached = queries.detachAttachmentsOfTasksDeletedBefore(RETENTION_CUTOFF);

        assertThat(detached).contains(first, second);
        assertThat(count("SELECT count(*) FROM task_attachments WHERE task_id = ?", task)).isZero();
        assertThat(count("SELECT count(*) FROM media WHERE id IN (?, ?)", first, second)).isEqualTo(2);
    }

    @Test
    void detachingAttachmentsLeavesTasksDeletedSinceTheCutoffAndTasksNotDeleted() {
        UUID recentlyDeleted = insertTask(RETENTION_CUTOFF);
        UUID notDeleted = insertTask(null);
        UUID ofRecentlyDeleted = insertMedia(LONG_AGO);
        UUID ofNotDeleted = insertMedia(LONG_AGO);
        attach(recentlyDeleted, ofRecentlyDeleted);
        attach(notDeleted, ofNotDeleted);

        List<UUID> detached = queries.detachAttachmentsOfTasksDeletedBefore(RETENTION_CUTOFF);

        assertThat(detached).doesNotContain(ofRecentlyDeleted, ofNotDeleted);
        assertThat(count("SELECT count(*) FROM task_attachments WHERE media_id IN (?, ?)", ofRecentlyDeleted,
                ofNotDeleted)).isEqualTo(2);
    }

    // Photos of deleted users

    @Test
    void detachingPhotosOfUsersDeletedBeforeTheCutoffReturnsTheProcessedAndThePendingPhoto() {
        UUID user = insertUser(RETENTION_CUTOFF.minusSeconds(1));
        UUID avatar = insertMedia(LONG_AGO);
        UUID pendingAvatar = insertMedia(LONG_AGO);
        setPhotos(user, avatar, pendingAvatar);

        List<UUID> detached = queries.detachAvatarsOfUsersDeletedBefore(RETENTION_CUTOFF);

        assertThat(detached).contains(avatar, pendingAvatar);
        assertThat(jdbc.queryForMap("SELECT avatar_media_id, pending_avatar_media_id FROM user_profiles WHERE id = ?",
                user))
                .containsEntry("avatar_media_id", null)
                .containsEntry("pending_avatar_media_id", null);
    }

    @Test
    void detachingOnlyAPendingPhotoReturnsNoNull() {
        UUID user = insertUser(RETENTION_CUTOFF.minusSeconds(1));
        UUID pendingAvatar = insertMedia(LONG_AGO);
        setPhotos(user, null, pendingAvatar);

        List<UUID> detached = queries.detachAvatarsOfUsersDeletedBefore(RETENTION_CUTOFF);

        assertThat(detached).contains(pendingAvatar).doesNotContainNull();
    }

    @Test
    void detachingPhotosLeavesUsersDeletedSinceTheCutoffAndUsersNotDeleted() {
        UUID recentlyDeleted = insertUser(RETENTION_CUTOFF);
        UUID notDeleted = insertUser(null);
        UUID photoOfRecentlyDeleted = insertMedia(LONG_AGO);
        UUID photoOfNotDeleted = insertMedia(LONG_AGO);
        setPhotos(recentlyDeleted, photoOfRecentlyDeleted, null);
        setPhotos(notDeleted, photoOfNotDeleted, null);

        List<UUID> detached = queries.detachAvatarsOfUsersDeletedBefore(RETENTION_CUTOFF);

        assertThat(detached).doesNotContain(photoOfRecentlyDeleted, photoOfNotDeleted);
        assertThat(avatarOf(recentlyDeleted)).isEqualTo(photoOfRecentlyDeleted);
        assertThat(avatarOf(notDeleted)).isEqualTo(photoOfNotDeleted);
    }

    // Unreferenced media

    @Test
    void deletingUnreferencedMediaOlderThanTheCutoffReturnsTheirStorageKeys() {
        UUID orphan = insertMedia(GRACE_CUTOFF.minusSeconds(1));
        String storageKey = storageKeyOf(orphan);

        List<String> deletedKeys = queries.deleteUnreferencedMediaCreatedBefore(GRACE_CUTOFF);

        assertThat(deletedKeys).contains(storageKey);
        assertThat(count("SELECT count(*) FROM media WHERE id = ?", orphan)).isZero();
    }

    @Test
    void deletingUnreferencedMediaSparesUploadsStillWithinTheGracePeriod() {
        UUID uploadInProgress = insertMedia(GRACE_CUTOFF);
        String storageKey = storageKeyOf(uploadInProgress);

        assertThat(queries.deleteUnreferencedMediaCreatedBefore(GRACE_CUTOFF)).doesNotContain(storageKey);
        assertThat(count("SELECT count(*) FROM media WHERE id = ?", uploadInProgress)).isOne();
    }

    @Test
    void deletingUnreferencedMediaSparesPhotosPendingPhotosAndAttachmentsOfDeletedTasks() {
        UUID user = insertUser(null);
        UUID avatar = insertMedia(LONG_AGO);
        UUID pendingAvatar = insertMedia(LONG_AGO);
        setPhotos(user, avatar, pendingAvatar);
        UUID attachment = insertMedia(LONG_AGO);
        attach(insertTask(RETENTION_CUTOFF), attachment);

        List<String> deletedKeys = queries.deleteUnreferencedMediaCreatedBefore(GRACE_CUTOFF);

        assertThat(deletedKeys).doesNotContain(
                storageKeyOf(avatar),
                storageKeyOf(pendingAvatar),
                storageKeyOf(attachment)
        );
        assertThat(count("SELECT count(*) FROM media WHERE id IN (?, ?, ?)", avatar, pendingAvatar, attachment))
                .isEqualTo(3);
    }

    @Test
    void mediaDetachedFromADeletedTaskIsDeletedWithTheUnreferencedMedia() {
        UUID attachment = insertMedia(LONG_AGO);
        attach(insertTask(RETENTION_CUTOFF.minusSeconds(1)), attachment);
        String storageKey = storageKeyOf(attachment);

        queries.detachAttachmentsOfTasksDeletedBefore(RETENTION_CUTOFF);

        assertThat(queries.deleteUnreferencedMediaCreatedBefore(GRACE_CUTOFF)).contains(storageKey);
    }

    // Stored objects

    @Test
    void existingStorageKeysKeepsOnlyTheKeysOfAMediaRow() {
        String stored = storageKeyOf(insertMedia(LONG_AGO));
        String orphanObject = "task-attachment/" + UUID.randomUUID();

        assertThat(queries.existingStorageKeys(List.of(stored, orphanObject))).containsExactly(stored);
    }

    @Test
    void existingStorageKeysOfNoKeyIsEmpty() {
        assertThat(queries.existingStorageKeys(List.of())).isEmpty();
    }

    private UUID insertUser(Instant deletedAt) {
        UUID id = jdbc.queryForObject(
                """
                        INSERT INTO user_profiles (display_name, status, created_at, updated_at)
                        VALUES ('Media cleanup', ?, ?, ?)
                        RETURNING id
                        """,
                UUID.class,
                deletedAt == null ? "ACTIVE" : "DELETED", timestamp(LONG_AGO), timestamp(LONG_AGO)
        );
        jdbc.update(
                """
                        INSERT INTO users (id, email, password_hash, enabled, created_at, updated_at, deleted_at)
                        VALUES (?, ?, '!', true, ?, ?, ?)
                        """,
                id, "cleanup-" + UUID.randomUUID() + "@example.com", timestamp(LONG_AGO), timestamp(LONG_AGO),
                timestamp(deletedAt)
        );
        return id;
    }

    private UUID insertTask(Instant deletedAt) {
        return jdbc.queryForObject(
                """
                        INSERT INTO tasks (created_at, updated_at, priority, reference, status, title, version,
                                           deleted_at)
                        VALUES (?, ?, 'LOW', ?, 'TO_DO', 'Task with files', 0, ?)
                        RETURNING id
                        """,
                UUID.class,
                timestamp(LONG_AGO), timestamp(LONG_AGO), "MED-" + UUID.randomUUID().toString().substring(0, 18),
                timestamp(deletedAt)
        );
    }

    private UUID insertMedia(Instant createdAt) {
        return jdbc.queryForObject(
                """
                        INSERT INTO media (storage_key, usage, original_filename, content_type, size_bytes, sha256,
                                           created_at)
                        VALUES (?, 'TASK_ATTACHMENT', 'file.pdf', 'application/pdf', 1, repeat('0', 64), ?)
                        RETURNING id
                        """,
                UUID.class,
                "task-attachment/" + UUID.randomUUID(), timestamp(createdAt)
        );
    }

    private void attach(UUID task, UUID media) {
        jdbc.update(
                "INSERT INTO task_attachments (task_id, media_id, created_at) VALUES (?, ?, ?)",
                task, media, timestamp(LONG_AGO)
        );
    }

    private void setPhotos(UUID user, UUID avatar, UUID pendingAvatar) {
        jdbc.update(
                "UPDATE user_profiles SET avatar_media_id = ?, pending_avatar_media_id = ? WHERE id = ?",
                avatar, pendingAvatar, user
        );
    }

    private UUID avatarOf(UUID user) {
        return jdbc.queryForObject("SELECT avatar_media_id FROM user_profiles WHERE id = ?", UUID.class, user);
    }

    private String storageKeyOf(UUID media) {
        return jdbc.queryForObject("SELECT storage_key FROM media WHERE id = ?", String.class, media);
    }

    private int count(String sql, Object... arguments) {
        return jdbc.queryForObject(sql, Integer.class, arguments);
    }

    private static Timestamp timestamp(Instant instant) {
        return instant == null ? null : Timestamp.from(instant);
    }
}
