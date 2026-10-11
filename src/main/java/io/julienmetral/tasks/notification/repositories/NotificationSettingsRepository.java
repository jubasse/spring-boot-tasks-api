package io.julienmetral.tasks.notification.repositories;

import io.julienmetral.tasks.notification.entities.NotificationSettings;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface NotificationSettingsRepository extends JpaRepository<NotificationSettings, UUID> {}
