package io.julienmetral.tasks.identity.entities;

import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

class UserStatusTest {

    private static final Instant VERIFIED_AT = Instant.parse("2026-01-01T00:00:00Z");

    private static User user(boolean enabled, Instant emailVerifiedAt) {
        User user = new User();
        user.setEnabled(enabled);
        user.setEmailVerifiedAt(emailVerifiedAt);
        return user;
    }

    @Test
    void nullUserIsDeleted() {
        assertThat(UserStatus.of(null)).isEqualTo(UserStatus.DELETED);
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
