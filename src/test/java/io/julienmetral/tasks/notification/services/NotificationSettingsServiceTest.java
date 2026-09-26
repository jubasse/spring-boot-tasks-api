package io.julienmetral.tasks.notification.services;

import io.julienmetral.tasks.identity.entities.User;
import io.julienmetral.tasks.identity.exceptions.UserNotFoundException;
import io.julienmetral.tasks.identity.repositories.UserRepository;
import io.julienmetral.tasks.notification.dtos.NotificationSettingsDto;
import io.julienmetral.tasks.notification.entities.NotificationSettings;
import io.julienmetral.tasks.notification.entities.TaskNotificationType;
import io.julienmetral.tasks.notification.repositories.NotificationSettingsRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class NotificationSettingsServiceTest {

    private static final UUID USER_ID = UUID.fromString("00000000-0000-0000-0000-000000000001");

    @Mock
    private NotificationSettingsRepository settingsRepository;

    @Mock
    private UserRepository userRepository;

    @InjectMocks
    private NotificationSettingsService service;

    private static User user() {
        User user = new User();
        user.setId(USER_ID);
        return user;
    }

    private static NotificationSettings stored(User user) {
        NotificationSettings settings = NotificationSettings.defaults(user);
        settings.setTaskAssigned(false);
        settings.setTaskUnassigned(true);
        settings.setTaskCancelled(false);
        settings.setTaskDeleted(true);
        settings.setTaskCommented(false);
        settings.setTaskMentioned(true);
        settings.setUpdatedAt(Instant.parse("2026-01-01T00:00:00Z"));
        return settings;
    }

    @Test
    void getReturnsStoredSettings() {
        User user = user();
        NotificationSettings settings = stored(user);
        when(userRepository.findById(USER_ID)).thenReturn(Optional.of(user));
        when(settingsRepository.findById(USER_ID)).thenReturn(Optional.of(settings));

        assertThat(service.get(USER_ID)).isSameAs(settings);
        verify(settingsRepository, never()).save(any());
    }

    @Test
    void getReturnsUnsavedDefaultsWhenNoRowExists() {
        User user = user();
        when(userRepository.findById(USER_ID)).thenReturn(Optional.of(user));
        when(settingsRepository.findById(USER_ID)).thenReturn(Optional.empty());

        NotificationSettings result = service.get(USER_ID);

        assertThat(result.getUser()).isSameAs(user);
        assertThat(result.isTaskAssigned()).isTrue();
        assertThat(result.isTaskUnassigned()).isTrue();
        assertThat(result.isTaskCancelled()).isTrue();
        assertThat(result.isTaskDeleted()).isTrue();
        assertThat(result.isTaskCommented()).isTrue();
        assertThat(result.isTaskMentioned()).isTrue();
        assertThat(result.getUpdatedAt()).isNull();
        verify(settingsRepository, never()).save(any());
    }

    @Test
    void getThrowsWhenUserDoesNotExist() {
        when(userRepository.findById(USER_ID)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.get(USER_ID))
                .isInstanceOf(UserNotFoundException.class)
                .hasMessageContaining(USER_ID.toString());
        verifyNoInteractions(settingsRepository);
    }

    @Test
    void updateCreatesSettingsWhenNoRowExists() {
        User user = user();
        when(userRepository.findById(USER_ID)).thenReturn(Optional.of(user));
        when(settingsRepository.findById(USER_ID)).thenReturn(Optional.empty());
        when(settingsRepository.save(any(NotificationSettings.class))).thenAnswer(inv -> inv.getArgument(0));
        Instant before = Instant.now();

        NotificationSettings result = service.update(
                USER_ID, new NotificationSettingsDto(false, true, false, true, false, true)
        );

        ArgumentCaptor<NotificationSettings> captor = ArgumentCaptor.forClass(NotificationSettings.class);
        verify(settingsRepository).save(captor.capture());
        NotificationSettings saved = captor.getValue();
        assertThat(result).isSameAs(saved);
        assertThat(saved.getUser()).isSameAs(user);
        // New row: the id stays null so Spring Data persists it; @MapsId derives it from the user
        assertThat(saved.getUserId()).isNull();
        assertThat(saved.isTaskAssigned()).isFalse();
        assertThat(saved.isTaskUnassigned()).isTrue();
        assertThat(saved.isTaskCancelled()).isFalse();
        assertThat(saved.isTaskDeleted()).isTrue();
        assertThat(saved.isTaskCommented()).isFalse();
        assertThat(saved.isTaskMentioned()).isTrue();
        assertThat(saved.getUpdatedAt()).isBetween(before, Instant.now());
    }

    @Test
    void updateOverwritesExistingSettings() {
        User user = user();
        NotificationSettings existing = stored(user);
        Instant previousUpdate = existing.getUpdatedAt();
        when(userRepository.findById(USER_ID)).thenReturn(Optional.of(user));
        when(settingsRepository.findById(USER_ID)).thenReturn(Optional.of(existing));
        when(settingsRepository.save(existing)).thenReturn(existing);

        NotificationSettings result = service.update(
                USER_ID, new NotificationSettingsDto(true, false, true, false, true, false)
        );

        assertThat(result).isSameAs(existing);
        assertThat(existing.isTaskAssigned()).isTrue();
        assertThat(existing.isTaskUnassigned()).isFalse();
        assertThat(existing.isTaskCancelled()).isTrue();
        assertThat(existing.isTaskDeleted()).isFalse();
        assertThat(existing.isTaskCommented()).isTrue();
        assertThat(existing.isTaskMentioned()).isFalse();
        assertThat(existing.getUpdatedAt()).isAfter(previousUpdate);
    }

    @Test
    void updateReturnsWhatTheRepositorySaved() {
        User user = user();
        NotificationSettings persisted = stored(user);
        when(userRepository.findById(USER_ID)).thenReturn(Optional.of(user));
        when(settingsRepository.findById(USER_ID)).thenReturn(Optional.empty());
        when(settingsRepository.save(any(NotificationSettings.class))).thenReturn(persisted);

        assertThat(service.update(USER_ID, new NotificationSettingsDto(true, true, true, true, true, true)))
                .isSameAs(persisted);
    }

    @Test
    void updateThrowsWhenUserDoesNotExist() {
        when(userRepository.findById(USER_ID)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.update(USER_ID, new NotificationSettingsDto(true, true, true, true, true, true)))
                .isInstanceOf(UserNotFoundException.class)
                .hasMessageContaining(USER_ID.toString());
        verifyNoInteractions(settingsRepository);
    }

    @ParameterizedTest
    @EnumSource(TaskNotificationType.class)
    void isEnabledIsTrueWhenNoRowExists(TaskNotificationType type) {
        when(settingsRepository.findById(USER_ID)).thenReturn(Optional.empty());

        assertThat(service.isEnabled(USER_ID, type)).isTrue();
        verifyNoInteractions(userRepository);
    }

    @Test
    void isEnabledReturnsTheStoredSwitchOfEachType() {
        // stored(): assigned off, unassigned on, cancelled off, deleted on, commented off, mentioned on
        when(settingsRepository.findById(USER_ID)).thenReturn(Optional.of(stored(user())));

        assertThat(service.isEnabled(USER_ID, TaskNotificationType.ASSIGNED)).isFalse();
        assertThat(service.isEnabled(USER_ID, TaskNotificationType.UNASSIGNED)).isTrue();
        assertThat(service.isEnabled(USER_ID, TaskNotificationType.CANCELLED)).isFalse();
        assertThat(service.isEnabled(USER_ID, TaskNotificationType.DELETED)).isTrue();
        assertThat(service.isEnabled(USER_ID, TaskNotificationType.COMMENTED)).isFalse();
        assertThat(service.isEnabled(USER_ID, TaskNotificationType.MENTIONED)).isTrue();
        verifyNoInteractions(userRepository);
    }
}
