package io.julienmetral.tasks.media.repositories;

import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * Native SQL on purpose: soft-deleted tasks and users are invisible to JPA (@SoftDelete), and the cleanup is about
 * exactly those rows.
 */
@Repository
@RequiredArgsConstructor
public class MediaCleanupQueries {

    // Any stable number: every instance must use the same one
    private static final long LOCK_KEY = 7_411_203_588_104_229L;

    private final NamedParameterJdbcTemplate jdbc;

    /** Transaction-scoped lock, released on commit or rollback; false when another instance holds it. */
    public boolean tryLock() {
        return Boolean.TRUE.equals(jdbc.queryForObject(
                "SELECT pg_try_advisory_xact_lock(:key)",
                new MapSqlParameterSource("key", LOCK_KEY),
                Boolean.class
        ));
    }

    /** @return the media ids that were attached */
    public List<UUID> detachAttachmentsOfTasksDeletedBefore(Instant cutoff) {
        return jdbc.queryForList(
                """
                        DELETE FROM task_attachments ta
                        USING tasks t
                        WHERE ta.task_id = t.id AND t.deleted_at < :cutoff
                        RETURNING ta.media_id
                        """,
                cutoff(cutoff),
                UUID.class
        );
    }

    /** @return the media ids that were profile photos, processed or still waiting for the worker */
    public List<UUID> detachAvatarsOfUsersDeletedBefore(Instant cutoff) {
        return jdbc.queryForList(
                """
                        WITH detached AS (
                            SELECT p.id, p.avatar_media_id, p.pending_avatar_media_id
                            FROM user_profiles p
                            JOIN users u ON u.id = p.id
                            WHERE u.deleted_at < :cutoff
                              AND (p.avatar_media_id IS NOT NULL OR p.pending_avatar_media_id IS NOT NULL)
                        ), updated AS (
                            UPDATE user_profiles p
                            SET avatar_media_id = NULL, pending_avatar_media_id = NULL
                            FROM detached d
                            WHERE p.id = d.id
                            RETURNING d.avatar_media_id, d.pending_avatar_media_id
                        )
                        SELECT avatar_media_id FROM updated WHERE avatar_media_id IS NOT NULL
                        UNION ALL
                        SELECT pending_avatar_media_id FROM updated WHERE pending_avatar_media_id IS NOT NULL
                        """,
                cutoff(cutoff),
                UUID.class
        );
    }

    /** @return the storage keys of the deleted rows */
    public List<String> deleteUnreferencedMediaCreatedBefore(Instant cutoff) {
        return jdbc.queryForList(
                """
                        DELETE FROM media m
                        WHERE m.created_at < :cutoff
                          AND NOT EXISTS (SELECT 1 FROM user_profiles p WHERE p.avatar_media_id = m.id)
                          AND NOT EXISTS (SELECT 1 FROM user_profiles p WHERE p.pending_avatar_media_id = m.id)
                          AND NOT EXISTS (SELECT 1 FROM task_attachments ta WHERE ta.media_id = m.id)
                        RETURNING m.storage_key
                        """,
                cutoff(cutoff),
                String.class
        );
    }

    public Set<String> existingStorageKeys(Collection<String> keys) {
        if (keys.isEmpty()) {
            return Set.of();
        }

        return jdbc.queryForList(
                "SELECT storage_key FROM media WHERE storage_key IN (:keys)",
                new MapSqlParameterSource("keys", keys),
                String.class
        ).stream().collect(Collectors.toSet());
    }

    private static MapSqlParameterSource cutoff(Instant cutoff) {
        return new MapSqlParameterSource("cutoff", Timestamp.from(cutoff));
    }
}
