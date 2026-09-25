package io.julienmetral.tasks.identity.dtos;

import io.julienmetral.tasks.identity.entities.User;
import io.julienmetral.tasks.identity.entities.UserStatus;

import java.util.UUID;

/** A user as shown inside other resources (tasks, task history). {@code displayName} is null once deleted. */
public record UserPreviewResponseDto(
        UUID id,
        String displayName,
        UserStatus status
) {

    /**
     * @param user the loaded association, null when the user is soft-deleted
     * @param id   the foreign key, still known when the user is soft-deleted
     * @return null when there is no user at all (for example an unassigned task)
     */
    public static UserPreviewResponseDto of(User user, UUID id) {
        if (user != null) {
            return new UserPreviewResponseDto(user.getId(), user.getDisplayName(), UserStatus.of(user));
        }

        if (id != null) {
            return new UserPreviewResponseDto(id, null, UserStatus.DELETED);
        }

        return null;
    }
}
