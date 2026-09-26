package io.julienmetral.tasks.identity.repositories;

import io.julienmetral.tasks.identity.repositories.UserRetentionQueries.InactiveUser;
import io.julienmetral.tasks.support.JdbcSliceTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;

import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static io.julienmetral.tasks.support.Transactions.inNewTransaction;
import static org.assertj.core.api.Assertions.assertThat;

@JdbcSliceTest
class UserRetentionQueriesTests {

    // Every date sits in 2000, before anything the other test contexts write to the shared database: the cutoffs
    // below reach only this class's rows
    private static final Instant NOW = Instant.parse("2000-06-01T00:00:00Z");

    private static final Instant ANONYMIZATION_CUTOFF = NOW.minus(Duration.ofDays(30));

    private static final Instant INACTIVITY_CUTOFF = NOW.minus(Duration.ofDays(730));

    private static final Instant LONG_AGO = NOW.minus(Duration.ofDays(1100));

    private static final Instant RECENTLY = NOW.minus(Duration.ofDays(100));

    @Autowired
    private UserRetentionQueries queries;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private PlatformTransactionManager transactionManager;

    // Erasure of deleted users

    @Test
    void anonymizingTurnsTheProfileIntoAnAnonymousDeletedUser() {
        UUID user = insertUser(LONG_AGO);
        UUID avatar = insertMedia(user);
        UUID pendingAvatar = insertMedia(user);
        jdbc.update(
                "UPDATE user_profiles SET avatar_media_id = ?, pending_avatar_media_id = ? WHERE id = ?",
                avatar, pendingAvatar, user
        );
        setDate(user, "deleted_at", ANONYMIZATION_CUTOFF.minusSeconds(1));

        List<UUID> erased = queries.anonymizeUsersDeletedBefore(ANONYMIZATION_CUTOFF, NOW);

        assertThat(erased).contains(user);
        Map<String, Object> profile = profile(user);
        assertThat(profile)
                .containsEntry("display_name", "Deleted user")
                .containsEntry("status", "DELETED")
                .containsEntry("avatar_media_id", null)
                .containsEntry("pending_avatar_media_id", null);
        assertThat(instant(profile.get("anonymized_at"))).isEqualTo(NOW);
        assertThat(instant(profile.get("updated_at"))).isEqualTo(NOW);
    }

    @Test
    void anonymizingDeletesTheAccountWithItsRolesSettingsAndTokens() {
        UUID user = insertUser(LONG_AGO);
        jdbc.update("INSERT INTO user_roles (user_id, role) VALUES (?, 'USER'), (?, 'ADMIN')", user, user);
        jdbc.update("INSERT INTO notification_settings (user_id, updated_at) VALUES (?, ?)", user, timestamp(LONG_AGO));
        jdbc.update(
                """
                        INSERT INTO refresh_tokens (user_id, token_hash, family_id, created_at, expires_at)
                        VALUES (?, ?, ?, ?, ?)
                        """,
                user, uniqueHash(), UUID.randomUUID(), timestamp(LONG_AGO), timestamp(NOW)
        );
        for (String table : List.of("email_verification_tokens", "password_reset_tokens")) {
            jdbc.update(
                    "INSERT INTO " + table + " (user_id, token_hash, created_at, expires_at) VALUES (?, ?, ?, ?)",
                    user, uniqueHash(), timestamp(LONG_AGO), timestamp(NOW)
            );
        }
        setDate(user, "deleted_at", ANONYMIZATION_CUTOFF.minusSeconds(1));

        queries.anonymizeUsersDeletedBefore(ANONYMIZATION_CUTOFF, NOW);

        assertThat(count("SELECT count(*) FROM users WHERE id = ?", user)).isZero();
        for (String table : List.of(
                "user_roles",
                "notification_settings",
                "refresh_tokens",
                "email_verification_tokens",
                "password_reset_tokens"
        )) {
            assertThat(count("SELECT count(*) FROM " + table + " WHERE user_id = ?", user)).as(table).isZero();
        }
    }

    @Test
    void anonymizingKeepsTasksAndMediaPointingToTheProfile() {
        UUID user = insertUser(LONG_AGO);
        UUID task = jdbc.queryForObject(
                """
                        INSERT INTO tasks (created_at, updated_at, priority, reference, status, title, version,
                                           created_by_id, assigned_to_id)
                        VALUES (?, ?, 'LOW', ?, 'TO_DO', 'Task of an erased user', 0, ?, ?)
                        RETURNING id
                        """,
                UUID.class,
                timestamp(LONG_AGO), timestamp(LONG_AGO), uniqueReference(), user, user
        );
        UUID media = insertMedia(user);
        setDate(user, "deleted_at", ANONYMIZATION_CUTOFF.minusSeconds(1));

        queries.anonymizeUsersDeletedBefore(ANONYMIZATION_CUTOFF, NOW);

        assertThat(jdbc.queryForMap("SELECT created_by_id, assigned_to_id FROM tasks WHERE id = ?", task))
                .containsEntry("created_by_id", user)
                .containsEntry("assigned_to_id", user);
        assertThat(jdbc.queryForObject("SELECT uploaded_by_id FROM media WHERE id = ?", UUID.class, media))
                .isEqualTo(user);
    }

    @Test
    void anonymizingFreesTheEmailForANewAccount() {
        UUID user = insertUser(LONG_AGO);
        String email = jdbc.queryForObject("SELECT email FROM users WHERE id = ?", String.class, user);
        setDate(user, "deleted_at", ANONYMIZATION_CUTOFF.minusSeconds(1));

        queries.anonymizeUsersDeletedBefore(ANONYMIZATION_CUTOFF, NOW);

        UUID newUser = insertUser(NOW, email);
        assertThat(jdbc.queryForObject("SELECT id FROM users WHERE email = ?", UUID.class, email)).isEqualTo(newUser);
    }

    @Test
    void anonymizingLeavesUsersDeletedSinceTheCutoffAndUsersNotDeleted() {
        UUID recentlyDeleted = insertUser(LONG_AGO);
        setDate(recentlyDeleted, "deleted_at", ANONYMIZATION_CUTOFF);
        UUID notDeleted = insertUser(LONG_AGO);

        List<UUID> erased = queries.anonymizeUsersDeletedBefore(ANONYMIZATION_CUTOFF, NOW);

        assertThat(erased).doesNotContain(recentlyDeleted, notDeleted);
        for (UUID user : List.of(recentlyDeleted, notDeleted)) {
            assertThat(count("SELECT count(*) FROM users WHERE id = ?", user)).isOne();
            assertThat(profile(user).get("display_name")).isNotEqualTo("Deleted user");
            assertThat(profile(user).get("anonymized_at")).isNull();
        }
    }

    @Test
    void anonymizingWithNoUserToEraseReturnsNothing() {
        // No user was deleted before 1970: the deletions must not run with an empty id list
        assertThat(queries.anonymizeUsersDeletedBefore(Instant.EPOCH, NOW)).isEmpty();
    }

    // Warning of inactive users

    @Test
    void warningMarksUsersInactiveSinceTheCutoffAndReturnsTheirContactDetails() {
        UUID enabledUser = insertUser(LONG_AGO);
        UUID disabledUser = insertUser(LONG_AGO);
        jdbc.update("UPDATE users SET enabled = false WHERE id = ?", disabledUser);

        List<InactiveUser> warned = queries.warnUsersInactiveSince(INACTIVITY_CUTOFF, NOW);

        assertThat(warned).contains(
                new InactiveUser(enabledUser, emailOf(enabledUser), displayNameOf(enabledUser), true),
                new InactiveUser(disabledUser, emailOf(disabledUser), displayNameOf(disabledUser), false)
        );
        assertThat(warnedAt(enabledUser)).isEqualTo(NOW);
        assertThat(warnedAt(disabledUser)).isEqualTo(NOW);
    }

    @ParameterizedTest(name = "last active {0}, last login {1}: warned {2}")
    @CsvSource(nullValues = "never", value = {
            "recently, long ago, false",
            "never, long ago, true",
            "never, recently, false",
            "never, never, true"
    })
    void activityIsTheLastActivityElseTheLastLoginElseTheCreation(
            String lastActive,
            String lastLogin,
            boolean warned
    ) {
        UUID user = insertUser(LONG_AGO);
        setDate(user, "last_active_at", at(lastActive));
        setDate(user, "last_login_at", at(lastLogin));

        List<UUID> warnedUsers = ids(queries.warnUsersInactiveSince(INACTIVITY_CUTOFF, NOW));

        assertThat(warnedUsers.contains(user)).isEqualTo(warned);
    }

    @Test
    void warningSkipsAdmins() {
        UUID admin = insertUser(LONG_AGO);
        jdbc.update("INSERT INTO user_roles (user_id, role) VALUES (?, 'USER'), (?, 'ADMIN')", admin, admin);

        assertThat(ids(queries.warnUsersInactiveSince(INACTIVITY_CUTOFF, NOW))).doesNotContain(admin);
        assertThat(warnedAt(admin)).isNull();
    }

    @Test
    void warningSkipsDeletedUsersAndUsersAlreadyWarned() {
        UUID deleted = insertUser(LONG_AGO);
        setDate(deleted, "deleted_at", RECENTLY);
        UUID alreadyWarned = insertUser(LONG_AGO);
        Instant firstWarning = NOW.minus(Duration.ofDays(10));
        setDate(alreadyWarned, "inactivity_warned_at", firstWarning);

        assertThat(ids(queries.warnUsersInactiveSince(INACTIVITY_CUTOFF, NOW))).doesNotContain(deleted, alreadyWarned);
        assertThat(warnedAt(deleted)).isNull();
        assertThat(warnedAt(alreadyWarned)).isEqualTo(firstWarning);
    }

    // Deletion of warned users

    @Test
    void usersWarnedBeforeTheCutoffAreDueForDeletion() {
        Instant cutoff = NOW.minus(Duration.ofDays(30));
        UUID warnedLongAgo = insertUser(LONG_AGO);
        setDate(warnedLongAgo, "inactivity_warned_at", cutoff.minusSeconds(1));
        UUID warnedRecently = insertUser(LONG_AGO);
        setDate(warnedRecently, "inactivity_warned_at", cutoff);

        List<UUID> due = queries.usersWarnedBefore(cutoff);

        assertThat(due).contains(warnedLongAgo).doesNotContain(warnedRecently);
    }

    @Test
    void userPromotedToAdminAfterTheWarningIsNotDueForDeletion() {
        UUID admin = insertUser(LONG_AGO);
        setDate(admin, "inactivity_warned_at", LONG_AGO);
        jdbc.update("INSERT INTO user_roles (user_id, role) VALUES (?, 'ADMIN')", admin);

        assertThat(queries.usersWarnedBefore(NOW)).doesNotContain(admin);
    }

    @Test
    void deletedUserIsNotDueForDeletionAgain() {
        UUID deleted = insertUser(LONG_AGO);
        setDate(deleted, "inactivity_warned_at", LONG_AGO);
        setDate(deleted, "deleted_at", RECENTLY);

        assertThat(queries.usersWarnedBefore(NOW)).doesNotContain(deleted);
    }

    @Test
    void lockIsHeldByOneTransactionAtATimeAndReleasedWhenItEnds() {
        assertThat(inNewTransaction(transactionManager, queries::tryLock)).isTrue();
        assertThat(queries.tryLock()).isTrue();
        assertThat(inNewTransaction(transactionManager, queries::tryLock)).isFalse();
    }

    private UUID insertUser(Instant createdAt) {
        return insertUser(createdAt, "retention-" + UUID.randomUUID() + "@example.com");
    }

    private UUID insertUser(Instant createdAt, String email) {
        UUID id = jdbc.queryForObject(
                """
                        INSERT INTO user_profiles (display_name, status, created_at, updated_at)
                        VALUES (?, 'ACTIVE', ?, ?)
                        RETURNING id
                        """,
                UUID.class,
                "Retention " + UUID.randomUUID(), timestamp(createdAt), timestamp(createdAt)
        );
        jdbc.update(
                """
                        INSERT INTO users (id, email, password_hash, enabled, email_verified_at, created_at, updated_at)
                        VALUES (?, ?, '!', true, ?, ?, ?)
                        """,
                id, email, timestamp(createdAt), timestamp(createdAt), timestamp(createdAt)
        );
        return id;
    }

    private UUID insertMedia(UUID uploadedBy) {
        return jdbc.queryForObject(
                """
                        INSERT INTO media (storage_key, usage, original_filename, content_type, size_bytes, sha256,
                                           uploaded_by_id, created_at)
                        VALUES (?, 'AVATAR', 'photo.png', 'image/png', 1, repeat('0', 64), ?, ?)
                        RETURNING id
                        """,
                UUID.class,
                "avatar/" + UUID.randomUUID(), uploadedBy, timestamp(LONG_AGO)
        );
    }

    private void setDate(UUID user, String column, Instant value) {
        jdbc.update("UPDATE users SET " + column + " = ? WHERE id = ?", timestamp(value), user);
    }

    private Map<String, Object> profile(UUID user) {
        return jdbc.queryForMap("SELECT * FROM user_profiles WHERE id = ?", user);
    }

    private String emailOf(UUID user) {
        return jdbc.queryForObject("SELECT email FROM users WHERE id = ?", String.class, user);
    }

    private String displayNameOf(UUID user) {
        return jdbc.queryForObject("SELECT display_name FROM user_profiles WHERE id = ?", String.class, user);
    }

    private Instant warnedAt(UUID user) {
        return instant(jdbc.queryForObject("SELECT inactivity_warned_at FROM users WHERE id = ?", Object.class, user));
    }

    private int count(String sql, Object... arguments) {
        return jdbc.queryForObject(sql, Integer.class, arguments);
    }

    private static List<UUID> ids(List<InactiveUser> users) {
        return users.stream().map(InactiveUser::id).toList();
    }

    private static Instant at(String label) {
        if (label == null) {
            return null;
        }
        return switch (label) {
            case "recently" -> RECENTLY;
            case "long ago" -> LONG_AGO;
            default -> throw new IllegalArgumentException(label);
        };
    }

    private static Timestamp timestamp(Instant instant) {
        return instant == null ? null : Timestamp.from(instant);
    }

    private static Instant instant(Object value) {
        return switch (value) {
            case null -> null;
            case Timestamp timestamp -> timestamp.toInstant();
            case OffsetDateTime dateTime -> dateTime.toInstant();
            default -> throw new IllegalArgumentException(value.getClass().getName());
        };
    }

    private static String uniqueHash() {
        return UUID.randomUUID().toString().replace("-", "");
    }

    private static String uniqueReference() {
        return "RET-" + UUID.randomUUID().toString().substring(0, 18);
    }
}
