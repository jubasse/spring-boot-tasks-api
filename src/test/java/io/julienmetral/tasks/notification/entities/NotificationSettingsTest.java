package io.julienmetral.tasks.notification.entities;

import io.julienmetral.tasks.identity.entities.User;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class NotificationSettingsTest {

    private static final UUID USER_ID = UUID.fromString("00000000-0000-0000-0000-000000000001");

    @Test
    void newInstanceHasEveryNotificationEnabled() {
        NotificationSettings settings = new NotificationSettings();

        assertThat(settings.isTaskAssigned()).isTrue();
        assertThat(settings.isTaskUnassigned()).isTrue();
        assertThat(settings.isTaskCancelled()).isTrue();
        assertThat(settings.isTaskDeleted()).isTrue();
        assertThat(settings.isTaskCommented()).isTrue();
        assertThat(settings.isTaskMentioned()).isTrue();
        assertThat(settings.getUpdatedAt()).isNull();
    }

    @Test
    void defaultsLinksTheUserAndEnablesEverything() {
        User user = new User();
        user.setId(USER_ID);

        NotificationSettings settings = NotificationSettings.defaults(user);

        assertThat(settings.getUser()).isSameAs(user);
        // Left null so that Spring Data persists the entity; @MapsId derives it from the user on insert
        assertThat(settings.getUserId()).isNull();
        assertThat(settings.isTaskAssigned()).isTrue();
        assertThat(settings.isTaskUnassigned()).isTrue();
        assertThat(settings.isTaskCancelled()).isTrue();
        assertThat(settings.isTaskDeleted()).isTrue();
        assertThat(settings.isTaskCommented()).isTrue();
        assertThat(settings.isTaskMentioned()).isTrue();
        // Not persisted yet: no timestamp until the user changes something.
        assertThat(settings.getUpdatedAt()).isNull();
    }

    @Test
    void defaultsReturnsANewInstanceOnEveryCall() {
        User user = new User();
        user.setId(USER_ID);

        assertThat(NotificationSettings.defaults(user))
                .isNotSameAs(NotificationSettings.defaults(user));
    }

    @ParameterizedTest
    @EnumSource(TaskNotificationType.class)
    void isEnabledIsTrueForEveryTypeByDefault(TaskNotificationType type) {
        assertThat(new NotificationSettings().isEnabled(type)).isTrue();
    }

    @ParameterizedTest
    @EnumSource(TaskNotificationType.class)
    void isEnabledReadsOnlyTheSwitchOfItsType(TaskNotificationType type) {
        NotificationSettings onlyThisOneOff = new NotificationSettings();
        switch (type) {
            case ASSIGNED -> onlyThisOneOff.setTaskAssigned(false);
            case UNASSIGNED -> onlyThisOneOff.setTaskUnassigned(false);
            case CANCELLED -> onlyThisOneOff.setTaskCancelled(false);
            case DELETED -> onlyThisOneOff.setTaskDeleted(false);
            case COMMENTED -> onlyThisOneOff.setTaskCommented(false);
            case MENTIONED -> onlyThisOneOff.setTaskMentioned(false);
        }

        for (TaskNotificationType other : TaskNotificationType.values()) {
            assertThat(onlyThisOneOff.isEnabled(other)).as(other.name()).isEqualTo(other != type);
        }
    }
}
