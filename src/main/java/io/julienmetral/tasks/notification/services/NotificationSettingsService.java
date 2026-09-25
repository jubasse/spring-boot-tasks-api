package io.julienmetral.tasks.notification.services;

import io.julienmetral.tasks.identity.entities.User;
import io.julienmetral.tasks.identity.exceptions.UserNotFoundException;
import io.julienmetral.tasks.identity.repositories.UserRepository;
import io.julienmetral.tasks.notification.dtos.NotificationSettingsDto;
import io.julienmetral.tasks.notification.entities.NotificationSettings;
import io.julienmetral.tasks.notification.entities.TaskNotificationType;
import io.julienmetral.tasks.notification.repositories.NotificationSettingsRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class NotificationSettingsService {

    private final NotificationSettingsRepository settingsRepository;
    private final UserRepository userRepository;

    /** The user's settings, or the defaults (everything enabled) when they never changed them. */
    @Transactional(readOnly = true)
    public NotificationSettings get(UUID userId) {
        User user = getUser(userId);

        return settingsRepository
                .findById(userId)
                .orElseGet(() -> NotificationSettings.defaults(user));
    }

    /** Whether the user wants this email; users who never changed their settings get everything. */
    @Transactional(readOnly = true)
    public boolean isEnabled(UUID userId, TaskNotificationType type) {
        return settingsRepository
                .findById(userId)
                .map(settings -> settings.isEnabled(type))
                .orElse(true);
    }

    @Transactional
    public NotificationSettings update(UUID userId, NotificationSettingsDto dto) {
        User user = getUser(userId);

        NotificationSettings settings = settingsRepository
                .findById(userId)
                .orElseGet(() -> NotificationSettings.defaults(user));

        settings.setTaskAssigned(dto.taskAssigned());
        settings.setTaskUnassigned(dto.taskUnassigned());
        settings.setTaskCancelled(dto.taskCancelled());
        settings.setTaskDeleted(dto.taskDeleted());
        settings.setUpdatedAt(Instant.now());

        return settingsRepository.save(settings);
    }

    private User getUser(UUID userId) {
        return userRepository
                .findById(userId)
                .orElseThrow(() -> new UserNotFoundException(userId));
    }
}
