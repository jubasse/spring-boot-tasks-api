package io.julienmetral.tasks.identity.entities;

import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.UUID;

import static io.julienmetral.tasks.support.UserSummaries.summary;
import static org.assertj.core.api.Assertions.assertThat;

class UserStatusTest {

    private static final Instant VERIFIED_AT = Instant.parse("2026-01-01T00:00:00Z");
    private static final Instant DELETED_AT = Instant.parse("2026-02-01T00:00:00Z");

    @Nested
    class OfUser {

        private static User user(boolean enabled, Instant emailVerifiedAt) {
            User user = new User();
            user.setEnabled(enabled);
            user.setEmailVerifiedAt(emailVerifiedAt);
            return user;
        }

        @Test
        void nullUserIsDeleted() {
            assertThat(UserStatus.of((User) null)).isEqualTo(UserStatus.DELETED);
        }

        @Test
        void enabledAndVerifiedUserIsActive() {
            assertThat(UserStatus.of(user(true, VERIFIED_AT))).isEqualTo(UserStatus.ACTIVE);
        }

        @Test
        void enabledButUnverifiedUserIsUnverified() {
            assertThat(UserStatus.of(user(true, null))).isEqualTo(UserStatus.UNVERIFIED);
        }

        @Test
        void disabledVerifiedUserIsDisabled() {
            assertThat(UserStatus.of(user(false, VERIFIED_AT))).isEqualTo(UserStatus.DISABLED);
        }

        @Test
        void disabledTakesPrecedenceOverUnverified() {
            assertThat(UserStatus.of(user(false, null))).isEqualTo(UserStatus.DISABLED);
        }
    }

    @Nested
    class OfUserSummary {

        private static UserSummary user(boolean enabled, Instant emailVerifiedAt, Instant deletedAt) {
            return summary(UUID.randomUUID(), "Alice", enabled, emailVerifiedAt, deletedAt);
        }

        @Test
        void enabledAndVerifiedUserIsActive() {
            assertThat(UserStatus.of(user(true, VERIFIED_AT, null))).isEqualTo(UserStatus.ACTIVE);
        }

        @Test
        void enabledButUnverifiedUserIsUnverified() {
            assertThat(UserStatus.of(user(true, null, null))).isEqualTo(UserStatus.UNVERIFIED);
        }

        @Test
        void disabledVerifiedUserIsDisabled() {
            assertThat(UserStatus.of(user(false, VERIFIED_AT, null))).isEqualTo(UserStatus.DISABLED);
        }

        @Test
        void disabledTakesPrecedenceOverUnverified() {
            assertThat(UserStatus.of(user(false, null, null))).isEqualTo(UserStatus.DISABLED);
        }

        @Test
        void deletedActiveUserIsDeleted() {
            assertThat(UserStatus.of(user(true, VERIFIED_AT, DELETED_AT))).isEqualTo(UserStatus.DELETED);
        }

        @Test
        void deletedTakesPrecedenceOverDisabledAndUnverified() {
            assertThat(UserStatus.of(user(false, null, DELETED_AT))).isEqualTo(UserStatus.DELETED);
        }

        @Test
        void deletedTakesPrecedenceOverUnverified() {
            assertThat(UserStatus.of(user(true, null, DELETED_AT))).isEqualTo(UserStatus.DELETED);
        }
    }
}
