package io.julienmetral.tasks.identity.entities;

import java.time.Instant;
import org.jspecify.annotations.Nullable;

/** Account state shown next to a user wherever tasks reference them. */
public enum UserStatus {
    ACTIVE,
    UNVERIFIED,
    DISABLED,
    DELETED;

    /** A {@code null} user is one that was soft-deleted, so its row can no longer be loaded. */
    public static UserStatus of(@Nullable User user) {
        if (user == null) {
            return DELETED;
        }

        return of(user.isEnabled(), user.getEmailVerifiedAt());
    }

    /** The status of an account that is not deleted. */
    public static UserStatus of(boolean enabled, @Nullable Instant emailVerifiedAt) {
        if (!enabled) {
            return DISABLED;
        }

        if (emailVerifiedAt == null) {
            return UNVERIFIED;
        }

        return ACTIVE;
    }
}
