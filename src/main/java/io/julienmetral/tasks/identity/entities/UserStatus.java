package io.julienmetral.tasks.identity.entities;

/** Account state shown next to a user wherever tasks reference them. */
public enum UserStatus {
    ACTIVE,
    UNVERIFIED,
    DISABLED,
    DELETED;

    /** A {@code null} user is one that was soft-deleted, so its row can no longer be loaded. */
    public static UserStatus of(User user) {
        if (user == null) {
            return DELETED;
        }

        if (!user.isEnabled()) {
            return DISABLED;
        }

        if (user.getEmailVerifiedAt() == null) {
            return UNVERIFIED;
        }

        return ACTIVE;
    }

    public static UserStatus of(UserSummary user) {
        if (user.getDeletedAt() != null) {
            return DELETED;
        }

        if (!user.isEnabled()) {
            return DISABLED;
        }

        if (user.getEmailVerifiedAt() == null) {
            return UNVERIFIED;
        }

        return ACTIVE;
    }
}
