package io.julienmetral.tasks.identity.dtos;

import io.julienmetral.tasks.identity.entities.UserStatus;
import io.julienmetral.tasks.identity.entities.UserSummary;
import io.julienmetral.tasks.media.services.MediaUrls;

import java.util.UUID;

/** A user as shown inside other resources (tasks, task history), deleted users included. */
public record UserPreviewResponseDto(
        UUID id,
        String displayName,
        UserStatus status,
        String avatarUrl
) {

    /** Null when there is no user at all, for example an unassigned task. */
    public static UserPreviewResponseDto of(UserSummary user, MediaUrls mediaUrls) {
        if (user == null) {
            return null;
        }

        return new UserPreviewResponseDto(
                user.getId(),
                user.getDisplayName(),
                UserStatus.of(user),
                mediaUrls.of(user.getAvatar())
        );
    }
}
