package io.julienmetral.tasks.identity.dtos;

import io.julienmetral.tasks.identity.entities.UserStatus;
import io.julienmetral.tasks.identity.entities.UserSummary;

import java.util.UUID;

/** A user as shown inside other resources (tasks, task history), deleted users included. */
public record UserPreviewResponseDto(
        UUID id,
        String displayName,
        UserStatus status
) {

    /** Null when there is no user at all, for example an unassigned task. */
    public static UserPreviewResponseDto of(UserSummary user) {
        if (user == null) {
            return null;
        }

        return new UserPreviewResponseDto(user.getId(), user.getDisplayName(), UserStatus.of(user));
    }
}
