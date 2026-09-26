package io.julienmetral.tasks.support;

import io.julienmetral.tasks.identity.entities.UserSummary;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.Instant;
import java.util.UUID;

/** {@link UserSummary} is read-only and has no setters, so unit tests build it through its fields. */
public final class UserSummaries {

    private static final Instant VERIFIED_AT = Instant.parse("2026-01-01T00:00:00Z");

    private UserSummaries() {
    }

    public static UserSummary reference(UUID id) {
        return summary(id, null, true, null, null);
    }

    public static UserSummary active(UUID id, String displayName) {
        return summary(id, displayName, true, VERIFIED_AT, null);
    }

    public static UserSummary summary(
            UUID id,
            String displayName,
            boolean enabled,
            Instant emailVerifiedAt,
            Instant deletedAt
    ) {
        UserSummary user = new UserSummary();
        ReflectionTestUtils.setField(user, "id", id);
        ReflectionTestUtils.setField(user, "displayName", displayName);
        ReflectionTestUtils.setField(user, "enabled", enabled);
        ReflectionTestUtils.setField(user, "emailVerifiedAt", emailVerifiedAt);
        ReflectionTestUtils.setField(user, "deletedAt", deletedAt);
        return user;
    }
}
