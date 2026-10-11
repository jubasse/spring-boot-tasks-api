package io.julienmetral.tasks.identity.dtos;

import io.julienmetral.tasks.identity.entities.UserProfile;
import io.julienmetral.tasks.identity.entities.UserStatus;
import io.julienmetral.tasks.media.services.MediaUrls;
import java.util.UUID;
import org.jspecify.annotations.Nullable;

/**
 * A user as shown inside other resources (tasks, task history), deleted users included. {@code
 * avatarUrl} is never null: without a visible photo, it points to the generated identicon.
 */
public record UserProfileResponseDto(
        UUID id, String displayName, UserStatus status, String avatarUrl) {

    public static UserProfileResponseDto of(UserProfile user, MediaUrls mediaUrls) {
        return new UserProfileResponseDto(
                user.getId(), user.getDisplayName(), user.getStatus(), mediaUrls.avatarOf(user));
    }

    /** Null when there is no user at all, for example an unassigned task. */
    public static @Nullable UserProfileResponseDto ofNullable(
            @Nullable UserProfile user, MediaUrls mediaUrls) {
        return user == null ? null : of(user, mediaUrls);
    }
}
