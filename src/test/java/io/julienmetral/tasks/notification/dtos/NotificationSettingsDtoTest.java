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
        settings.setTaskCommented(true);
        settings.setTaskMentioned(false);
        settings.setTaskDueSoon(true);
        settings.setTaskOverdue(false);

        NotificationSettingsDto dto = new NotificationSettingsDto(settings);

        assertThat(dto).isEqualTo(new NotificationSettingsDto(true, false, true, false, true, false, true, false));
    }

    @Test
    void mapsTheOppositeCombinationToCatchSwappedFields() {
        NotificationSettings settings = new NotificationSettings();
        settings.setTaskAssigned(false);
        settings.setTaskUnassigned(true);
        settings.setTaskCancelled(false);
        settings.setTaskDeleted(true);
        settings.setTaskCommented(false);
        settings.setTaskMentioned(true);
        settings.setTaskDueSoon(false);
        settings.setTaskOverdue(true);

        NotificationSettingsDto dto = new NotificationSettingsDto(settings);

        assertThat(dto.taskAssigned()).isFalse();
        assertThat(dto.taskUnassigned()).isTrue();
        assertThat(dto.taskCancelled()).isFalse();
        assertThat(dto.taskDeleted()).isTrue();
        assertThat(dto.taskCommented()).isFalse();
        assertThat(dto.taskMentioned()).isTrue();
        assertThat(dto.taskDueSoon()).isFalse();
        assertThat(dto.taskOverdue()).isTrue();
    }

    @Test
    void mapsCommentedAndMentionedIndependently() {
        NotificationSettings settings = new NotificationSettings();
        settings.setTaskCommented(false);

        NotificationSettingsDto dto = new NotificationSettingsDto(settings);

        assertThat(dto.taskCommented()).isFalse();
        assertThat(dto.taskMentioned()).isTrue();
    }

    @Test
    void mapsDueSoonAndOverdueIndependently() {
        NotificationSettings dueSoonOff = new NotificationSettings();
        dueSoonOff.setTaskDueSoon(false);
        NotificationSettings overdueOff = new NotificationSettings();
        overdueOff.setTaskOverdue(false);

        assertThat(new NotificationSettingsDto(dueSoonOff))
                .isEqualTo(new NotificationSettingsDto(true, true, true, true, true, true, false, true));
        assertThat(new NotificationSettingsDto(overdueOff))
                .isEqualTo(new NotificationSettingsDto(true, true, true, true, true, true, true, false));
    }

    @Test
    void mapsDefaultsAsEverythingEnabled() {
        NotificationSettingsDto dto = new NotificationSettingsDto(new NotificationSettings());

        assertThat(dto).isEqualTo(new NotificationSettingsDto(true, true, true, true, true, true, true, true));
    }
}
