package io.julienmetral.tasks.notification.repositories;

import io.julienmetral.tasks.notification.entities.NotificationSettings;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.UUID;

public interface NotificationSettingsRepository extends JpaRepository<NotificationSettings, UUID> {
}
