package io.julienmetral.tasks.identity.repositories;

import static org.assertj.core.api.Assertions.assertThat;

import io.julienmetral.tasks.support.JdbcSliceTest;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.stream.Stream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

@JdbcSliceTest
class ExpiredTokenQueriesTests {

    // Every date sits in 2000, before anything the other test contexts write to the shared
    // database: the cutoff reaches only this class's rows
    private static final Instant CUTOFF = Instant.parse("2000-06-01T00:00:00Z");

    private static final Instant BEFORE_CUTOFF = CUTOFF.minus(Duration.ofDays(1));

    private static final Instant AFTER_CUTOFF = CUTOFF.plus(Duration.ofDays(1));

    private static final Instant LONG_AGO = CUTOFF.minus(Duration.ofDays(100));

    @Autowired private ExpiredTokenQueries queries;

    @Autowired private JdbcTemplate jdbc;

    private UUID user;

    @BeforeEach
    void insertUser() {
        user =
                jdbc.queryForObject(
                        """
                        INSERT INTO user_profiles (display_name, status, created_at, updated_at)
                        VALUES (?, 'ACTIVE', ?, ?)
                        RETURNING id
                        """,
                        UUID.class,
                        "Tokens " + UUID.randomUUID(),
                        timestamp(LONG_AGO),
                        timestamp(LONG_AGO));
        jdbc.update(
                """
                        INSERT INTO users (id, email, password_hash, enabled, email_verified_at, created_at, updated_at)
                        VALUES (?, ?, '!', true, ?, ?, ?)
                        """,
                user,
                "tokens-" + UUID.randomUUID() + "@example.com",
                timestamp(LONG_AGO),
                timestamp(LONG_AGO),
                timestamp(LONG_AGO));
    }

    // Refresh token families

    @Test
    void familyWhoseTokensAllExpiredBeforeTheCutoffIsDeleted() {
        UUID family = UUID.randomUUID();
        UUID revoked = insertRefreshToken(family, LONG_AGO, LONG_AGO);
        UUID last = insertRefreshToken(family, BEFORE_CUTOFF, null);

        int deleted = queries.deleteRefreshFamiliesExpiredBefore(CUTOFF);

        assertThat(deleted).isGreaterThanOrEqualTo(2);
        assertThat(refreshTokensAmong(revoked, last)).isEmpty();
    }

    @Test
    void familyWithALiveTokenKeepsItsRevokedExpiredTokens() {
        UUID family = UUID.randomUUID();
        UUID revoked = insertRefreshToken(family, LONG_AGO, LONG_AGO);
        UUID live = insertRefreshToken(family, AFTER_CUTOFF, null);

        queries.deleteRefreshFamiliesExpiredBefore(CUTOFF);

        assertThat(refreshTokensAmong(revoked, live)).containsExactlyInAnyOrder(revoked, live);
    }

    @Test
    void tokenExpiringAtTheCutoffKeepsItsFamily() {
        UUID family = UUID.randomUUID();
        UUID revoked = insertRefreshToken(family, LONG_AGO, LONG_AGO);
        UUID last = insertRefreshToken(family, CUTOFF, null);

        queries.deleteRefreshFamiliesExpiredBefore(CUTOFF);

        assertThat(refreshTokensAmong(revoked, last)).containsExactlyInAnyOrder(revoked, last);
    }

    @Test
    void expiredFamilyIsDeletedWithoutTouchingAnotherFamilyOfTheSameUser() {
        UUID expired = insertRefreshToken(UUID.randomUUID(), BEFORE_CUTOFF, null);
        UUID live = insertRefreshToken(UUID.randomUUID(), AFTER_CUTOFF, null);

        queries.deleteRefreshFamiliesExpiredBefore(CUTOFF);

        assertThat(refreshTokensAmong(expired, live)).containsExactly(live);
    }

    // Email verification and password reset tokens

    @ParameterizedTest
    @ValueSource(strings = {"email_verification_tokens", "password_reset_tokens"})
    void singleUseTokensExpiredBeforeTheCutoffAreDeletedUsedOrNot(String table) {
        UUID unused = insertSingleUseToken(table, BEFORE_CUTOFF, null);
        UUID used = insertSingleUseToken(table, BEFORE_CUTOFF, LONG_AGO);

        int deleted = deleteSingleUseTokens(table);

        assertThat(deleted).isGreaterThanOrEqualTo(2);
        assertThat(remainingAmong(table, unused, used)).isEmpty();
    }

    @ParameterizedTest
    @ValueSource(strings = {"email_verification_tokens", "password_reset_tokens"})
    void singleUseTokensExpiringAtOrAfterTheCutoffStay(String table) {
        UUID atCutoff = insertSingleUseToken(table, CUTOFF, null);
        UUID afterCutoff = insertSingleUseToken(table, AFTER_CUTOFF, LONG_AGO);

        deleteSingleUseTokens(table);

        assertThat(remainingAmong(table, atCutoff, afterCutoff))
                .containsExactlyInAnyOrder(atCutoff, afterCutoff);
    }

    private int deleteSingleUseTokens(String table) {
        return switch (table) {
            case "email_verification_tokens" ->
                    queries.deleteVerificationTokensExpiredBefore(CUTOFF);
            case "password_reset_tokens" -> queries.deleteResetTokensExpiredBefore(CUTOFF);
            default -> throw new IllegalArgumentException(table);
        };
    }

    private UUID insertRefreshToken(UUID family, Instant expiresAt, Instant revokedAt) {
        return jdbc.queryForObject(
                """
                        INSERT INTO refresh_tokens (user_id, token_hash, family_id, created_at, expires_at, revoked_at)
                        VALUES (?, ?, ?, ?, ?, ?)
                        RETURNING id
                        """,
                UUID.class,
                user,
                uniqueHash(),
                family,
                timestamp(LONG_AGO),
                timestamp(expiresAt),
                timestamp(revokedAt));
    }

    private UUID insertSingleUseToken(String table, Instant expiresAt, Instant usedAt) {
        return jdbc.queryForObject(
                "INSERT INTO "
                        + table
                        + " (user_id, token_hash, created_at, expires_at, used_at)"
                        + " VALUES (?, ?, ?, ?, ?) RETURNING id",
                UUID.class,
                user,
                uniqueHash(),
                timestamp(LONG_AGO),
                timestamp(expiresAt),
                timestamp(usedAt));
    }

    private List<UUID> refreshTokensAmong(UUID... ids) {
        return remainingAmong("refresh_tokens", ids);
    }

    private List<UUID> remainingAmong(String table, UUID... ids) {
        return Stream.of(ids)
                .filter(
                        id ->
                                jdbc.queryForObject(
                                                "SELECT count(*) FROM " + table + " WHERE id = ?",
                                                Integer.class,
                                                id)
                                        == 1)
                .toList();
    }

    private static Timestamp timestamp(Instant instant) {
        return instant == null ? null : Timestamp.from(instant);
    }

    private static String uniqueHash() {
        return UUID.randomUUID().toString().replace("-", "");
    }
}
