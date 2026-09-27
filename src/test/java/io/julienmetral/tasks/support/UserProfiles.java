package io.julienmetral.tasks.support;

import io.julienmetral.tasks.identity.entities.UserProfile;
import io.julienmetral.tasks.identity.entities.UserStatus;

import java.util.UUID;

public final class UserProfiles {

    private UserProfiles() {
    }

    public static UserProfile reference(UUID id) {
        return profile(id, null, UserStatus.ACTIVE);
    }

    public static UserProfile active(UUID id, String displayName) {
        return profile(id, displayName, UserStatus.ACTIVE);
    }

    public static UserProfile profile(UUID id, String displayName, UserStatus status) {
        UserProfile profile = new UserProfile();
        profile.setId(id);
        profile.setDisplayName(displayName);
        profile.setStatus(status);
        return profile;
    }
}
