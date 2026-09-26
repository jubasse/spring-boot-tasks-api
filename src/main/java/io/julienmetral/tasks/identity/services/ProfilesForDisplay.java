package io.julienmetral.tasks.identity.services;

import io.julienmetral.tasks.identity.entities.UserProfile;
import org.hibernate.Hibernate;

/**
 * Loads what {@code UserProfileResponseDto} reads. With open-in-view off, a response is written after the service's
 * transaction has closed, so a profile it shows must be loaded before: a proxy set with {@code getReferenceById} or
 * a lazy association would otherwise fail with {@code LazyInitializationException}.
 */
public final class ProfilesForDisplay {

    private ProfilesForDisplay() {
    }

    public static void load(UserProfile profile) {
        if (profile != null) {
            Hibernate.initialize(profile);
            Hibernate.initialize(profile.getAvatar());
        }
    }
}
