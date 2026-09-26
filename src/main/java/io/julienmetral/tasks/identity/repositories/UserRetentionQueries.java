package io.julienmetral.tasks.identity.repositories;

import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/** Native SQL: anonymization is about soft-deleted users, which JPA never loads (@SoftDelete). */
@Repository
@RequiredArgsConstructor
public class UserRetentionQueries {

    // Any stable number, distinct from the other jobs' locks: every instance must use the same one
    private static final long LOCK_KEY = 5_118_640_279_033_417L;

    private static final String ACTIVITY = "COALESCE(u.last_active_at, u.last_login_at, u.created_at)";

    // Admins are never warned nor deleted for inactivity: deleting the last one would lock everybody out of
    // administration. Checked again at deletion, for a user promoted after the warning.
    private static final String NOT_ADMIN =
            "NOT EXISTS (SELECT 1 FROM user_roles r WHERE r.user_id = u.id AND r.role = 'ADMIN')";

    private final NamedParameterJdbcTemplate jdbc;

    public record InactiveUser(
            UUID id,
            String email,
            String displayName
    ) {
    }

    /** Transaction-scoped lock, released on commit or rollback; false when another instance holds it. */
    public boolean tryLock() {
        return Boolean.TRUE.equals(jdbc.queryForObject(
                "SELECT pg_try_advisory_xact_lock(:key)",
                new MapSqlParameterSource("key", LOCK_KEY),
                Boolean.class
        ));
    }

    /**
     * Erases the personal data of users deleted before {@code cutoff}, and the rows that only held it (settings,
     * tokens). The row itself stays, so tasks, comments and history keep pointing to a "Deleted user". The email
     * becomes free for a new sign-up.
     *
     * @return the anonymized user ids
     */
    public List<UUID> anonymizeUsersDeletedBefore(Instant cutoff, Instant now) {
        List<UUID> ids = jdbc.queryForList(
                """
                        UPDATE users u
                        SET email = 'deleted-' || u.id || '@anonymized.invalid',
                            display_name = 'Deleted user',
                            password_hash = '!',
                            enabled = false,
                            email_verified_at = NULL,
                            last_login_at = NULL,
                            last_active_at = NULL,
                            inactivity_warned_at = NULL,
                            anonymized_at = :now
                        WHERE u.deleted_at < :cutoff AND u.anonymized_at IS NULL
                        RETURNING u.id
                        """,
                new MapSqlParameterSource()
                        .addValue("cutoff", Timestamp.from(cutoff))
                        .addValue("now", Timestamp.from(now)),
                UUID.class
        );

        if (!ids.isEmpty()) {
            MapSqlParameterSource users = new MapSqlParameterSource("ids", ids);

            for (String table : List.of(
                    "notification_settings",
                    "refresh_tokens",
                    "email_verification_tokens",
                    "password_reset_tokens"
            )) {
                jdbc.update("DELETE FROM " + table + " WHERE user_id IN (:ids)", users);
            }
        }

        return ids;
    }

    /**
     * Marks as warned the accounts, admins excepted, without activity since {@code cutoff}.
     *
     * @return the accounts warned by this call
     */
    public List<InactiveUser> warnUsersInactiveSince(Instant cutoff, Instant now) {
        return jdbc.query(
                """
                        UPDATE users u
                        SET inactivity_warned_at = :now
                        WHERE u.deleted_at IS NULL
                          AND u.inactivity_warned_at IS NULL
                          AND %s < :cutoff
                          AND %s
                        RETURNING u.id, u.email, u.display_name
                        """.formatted(ACTIVITY, NOT_ADMIN),
                new MapSqlParameterSource()
                        .addValue("cutoff", Timestamp.from(cutoff))
                        .addValue("now", Timestamp.from(now)),
                (row, index) -> new InactiveUser(
                        row.getObject("id", UUID.class),
                        row.getString("email"),
                        row.getString("display_name")
                )
        );
    }

    /** Accounts warned before {@code cutoff} that are still inactive: any activity clears the warning. */
    public List<UUID> usersWarnedBefore(Instant cutoff) {
        return jdbc.queryForList(
                """
                        SELECT u.id FROM users u
                        WHERE u.deleted_at IS NULL AND u.inactivity_warned_at < :cutoff AND %s
                        """.formatted(NOT_ADMIN),
                new MapSqlParameterSource("cutoff", Timestamp.from(cutoff)),
                UUID.class
        );
    }
}
