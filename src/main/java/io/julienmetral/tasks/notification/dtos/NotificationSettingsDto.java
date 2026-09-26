package io.julienmetral.tasks.notification.dtos;

import io.julienmetral.tasks.notification.entities.NotificationSettings;
import jakarta.validation.constraints.NotNull;

/** Used both as the PUT body (every field required) and as the response. */
public record NotificationSettingsDto(
        @NotNull Boolean taskAssigned,
        @NotNull Boolean taskUnassigned,
        @NotNull Boolean taskCancelled,
        @NotNull Boolean taskDeleted,
        @NotNull Boolean taskCommented,
        @NotNull Boolean taskMentioned
) {

    public NotificationSettingsDto(NotificationSettings settings) {
        this(
                settings.isTaskAssigned(),
                settings.isTaskUnassigned(),
                settings.isTaskCancelled(),
                settings.isTaskDeleted(),
                settings.isTaskCommented(),
                settings.isTaskMentioned()
        );
    }
}
