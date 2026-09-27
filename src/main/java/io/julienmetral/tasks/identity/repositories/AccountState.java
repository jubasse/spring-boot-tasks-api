package io.julienmetral.tasks.identity.repositories;

import io.julienmetral.tasks.identity.entities.UserStatus;

import java.time.Instant;

/** The two account columns a status depends on, read without loading the account. */
public record AccountState(boolean enabled, Instant emailVerifiedAt) {

    public UserStatus status() {
        return UserStatus.of(enabled, emailVerifiedAt);
    }
}
