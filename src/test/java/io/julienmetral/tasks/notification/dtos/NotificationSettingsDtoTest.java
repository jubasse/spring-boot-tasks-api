package io.julienmetral.tasks.notification.dtos;

import io.julienmetral.tasks.notification.entities.NotificationSettings;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class NotificationSettingsDtoTest {

    @Test
    void mapsEveryFlagFromTheEntity() {
        NotificationSettings settings = new NotificationSettings();
        settings.setTaskAssigned(true);
        settings.setTaskUnassigned(false);
        settings.setTaskCancelled(true);
        settings.setTaskDeleted(false);

        NotificationSettingsDto dto = new NotificationSettingsDto(settings);

        assertThat(dto).isEqualTo(new NotificationSettingsDto(true, false, true, false));
    }

    @Test
    void mapsTheOppositeCombinationToCatchSwappedFields() {
        NotificationSettings settings = new NotificationSettings();
        settings.setTaskAssigned(false);
        settings.setTaskUnassigned(true);
        settings.setTaskCancelled(false);
        settings.setTaskDeleted(true);

        NotificationSettingsDto dto = new NotificationSettingsDto(settings);

        assertThat(dto.taskAssigned()).isFalse();
        assertThat(dto.taskUnassigned()).isTrue();
        assertThat(dto.taskCancelled()).isFalse();
        assertThat(dto.taskDeleted()).isTrue();
    }

    @Test
    void mapsDefaultsAsEverythingEnabled() {
        NotificationSettingsDto dto = new NotificationSettingsDto(new NotificationSettings());

        assertThat(dto).isEqualTo(new NotificationSettingsDto(true, true, true, true));
    }
}
