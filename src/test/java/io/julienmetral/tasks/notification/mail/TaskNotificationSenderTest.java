package io.julienmetral.tasks.notification.mail;

import io.julienmetral.tasks.identity.entities.User;
import io.julienmetral.tasks.identity.repositories.UserRepository;
import io.julienmetral.tasks.mail.MailMessage;
import io.julienmetral.tasks.mail.MailService;
import io.julienmetral.tasks.notification.entities.TaskNotificationType;
import io.julienmetral.tasks.notification.services.NotificationSettingsService;
import io.julienmetral.tasks.task.events.TaskAssigned;
import io.julienmetral.tasks.task.events.TaskCancelled;
import io.julienmetral.tasks.task.events.TaskDeleted;
import io.julienmetral.tasks.task.events.TaskUnassigned;
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
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class TaskNotificationSenderTest {

    private static final UUID TASK_ID = UUID.fromString("00000000-0000-0000-0000-00000000000a");
    private static final UUID RECIPIENT_ID = UUID.fromString("00000000-0000-0000-0000-000000000001");
    private static final UUID ACTOR_ID = UUID.fromString("00000000-0000-0000-0000-000000000002");
    private static final String FOOTER = "\n\nYou can turn these emails off in your notification settings.\n";

    @Mock
    private UserRepository userRepository;

    @Mock
    private NotificationSettingsService settingsService;

    @Mock
    private MailService mailService;

    @InjectMocks
    private TaskNotificationSender sender;

    private static User activeUser(UUID id, String displayName, String email) {
        User user = new User();
        user.setId(id);
        user.setDisplayName(displayName);
        user.setEmail(email);
        user.setEmailVerifiedAt(Instant.parse("2026-01-01T00:00:00Z"));
        return user;
    }

    private void stubActiveRecipient(TaskNotificationType type) {
        when(userRepository.findById(RECIPIENT_ID))
                .thenReturn(Optional.of(activeUser(RECIPIENT_ID, "Alice", "alice@example.com")));
        when(settingsService.isEnabled(RECIPIENT_ID, type)).thenReturn(true);
    }

    private void stubActor() {
        when(userRepository.findById(ACTOR_ID))
                .thenReturn(Optional.of(activeUser(ACTOR_ID, "Bob", "bob@example.com")));
    }

    private MailMessage sentMessage() {
        ArgumentCaptor<MailMessage> captor = ArgumentCaptor.forClass(MailMessage.class);
        verify(mailService).send(captor.capture());
        return captor.getValue();
    }

    private void dispatch(TaskNotificationType type, UUID recipientId, UUID actorId) {
        switch (type) {
            case ASSIGNED -> sender.onAssigned(new TaskAssigned(TASK_ID, "TASK-1", "Title", recipientId, actorId));
            case UNASSIGNED -> sender.onUnassigned(new TaskUnassigned(TASK_ID, "TASK-1", "Title", recipientId, actorId));
            case CANCELLED -> sender.onCancelled(new TaskCancelled(TASK_ID, "TASK-1", "Title", recipientId, "r", actorId));
            case DELETED -> sender.onDeleted(new TaskDeleted(TASK_ID, "TASK-1", "Title", recipientId, actorId));
        }
    }

    @Test
    void assignedEmailsTheAssigneeNamingTheActor() {
        stubActiveRecipient(TaskNotificationType.ASSIGNED);
        stubActor();

        sender.onAssigned(new TaskAssigned(TASK_ID, "TASK-1", "Write tests", RECIPIENT_ID, ACTOR_ID));

        assertThat(sentMessage()).isEqualTo(new MailMessage(
                "alice@example.com",
                "Task TASK-1 was assigned to you",
                "Hello Alice,\n\nBob assigned you the task TASK-1: \"Write tests\"." + FOOTER
        ));
    }

    @Test
    void unassignedEmailsThePreviousAssigneeNamingTheActor() {
        stubActiveRecipient(TaskNotificationType.UNASSIGNED);
        stubActor();

        sender.onUnassigned(new TaskUnassigned(TASK_ID, "TASK-1", "Write tests", RECIPIENT_ID, ACTOR_ID));

        assertThat(sentMessage()).isEqualTo(new MailMessage(
                "alice@example.com",
                "Task TASK-1 is no longer assigned to you",
                "Hello Alice,\n\nBob assigned the task TASK-1: \"Write tests\" to someone else." + FOOTER
        ));
    }

    @Test
    void cancelledWithReasonEmailsTheAssigneeWithTheReason() {
        stubActiveRecipient(TaskNotificationType.CANCELLED);
        stubActor();

        sender.onCancelled(new TaskCancelled(TASK_ID, "TASK-1", "Write tests", RECIPIENT_ID, "Out of scope", ACTOR_ID));

        assertThat(sentMessage()).isEqualTo(new MailMessage(
                "alice@example.com",
                "Task TASK-1 was cancelled",
                "Hello Alice,\n\nBob cancelled the task TASK-1: \"Write tests\".\n\nReason: Out of scope" + FOOTER
        ));
    }

    @Test
    void cancelledWithoutReasonOmitsTheReasonLine() {
        stubActiveRecipient(TaskNotificationType.CANCELLED);
        stubActor();

        sender.onCancelled(new TaskCancelled(TASK_ID, "TASK-1", "Write tests", RECIPIENT_ID, null, ACTOR_ID));

        MailMessage message = sentMessage();
        assertThat(message.subject()).isEqualTo("Task TASK-1 was cancelled");
        assertThat(message.text())
                .isEqualTo("Hello Alice,\n\nBob cancelled the task TASK-1: \"Write tests\"." + FOOTER)
                .doesNotContain("Reason");
    }

    @Test
    void deletedEmailsTheAssigneeNamingTheActor() {
        stubActiveRecipient(TaskNotificationType.DELETED);
        stubActor();

        sender.onDeleted(new TaskDeleted(TASK_ID, "TASK-1", "Write tests", RECIPIENT_ID, ACTOR_ID));

        assertThat(sentMessage()).isEqualTo(new MailMessage(
                "alice@example.com",
                "Task TASK-1 was deleted",
                "Hello Alice,\n\nBob deleted the task TASK-1: \"Write tests\"." + FOOTER
        ));
    }

    @ParameterizedTest
    @EnumSource(TaskNotificationType.class)
    void unknownActorIsNamedSomeone(TaskNotificationType type) {
        stubActiveRecipient(type);

        dispatch(type, RECIPIENT_ID, null);

        assertThat(sentMessage().text()).startsWith("Hello Alice,\n\nSomeone ");
        verify(userRepository, never()).findById(ACTOR_ID);
    }

    @ParameterizedTest
    @EnumSource(TaskNotificationType.class)
    void actorWhoCannotBeLoadedIsNamedSomeone(TaskNotificationType type) {
        stubActiveRecipient(type);
        when(userRepository.findById(ACTOR_ID)).thenReturn(Optional.empty());

        dispatch(type, RECIPIENT_ID, ACTOR_ID);

        assertThat(sentMessage().text()).startsWith("Hello Alice,\n\nSomeone ");
    }

    @ParameterizedTest
    @EnumSource(TaskNotificationType.class)
    void nothingIsSentWithoutRecipient(TaskNotificationType type) {
        dispatch(type, null, null);

        verifyNoInteractions(settingsService, mailService);
    }

    @ParameterizedTest
    @EnumSource(TaskNotificationType.class)
    void nobodyIsEmailedAboutTheirOwnAction(TaskNotificationType type) {
        stubActor();

        dispatch(type, ACTOR_ID, ACTOR_ID);

        verifyNoInteractions(settingsService, mailService);
    }

    @ParameterizedTest
    @EnumSource(TaskNotificationType.class)
    void nothingIsSentWhenRecipientCannotBeLoaded(TaskNotificationType type) {
        when(userRepository.findById(RECIPIENT_ID)).thenReturn(Optional.empty());

        dispatch(type, RECIPIENT_ID, null);

        verifyNoInteractions(settingsService, mailService);
    }

    @ParameterizedTest
    @EnumSource(TaskNotificationType.class)
    void nothingIsSentToADisabledRecipient(TaskNotificationType type) {
        User disabled = activeUser(RECIPIENT_ID, "Alice", "alice@example.com");
        disabled.setEnabled(false);
        when(userRepository.findById(RECIPIENT_ID)).thenReturn(Optional.of(disabled));

        dispatch(type, RECIPIENT_ID, null);

        verifyNoInteractions(settingsService, mailService);
    }

    @ParameterizedTest
    @EnumSource(TaskNotificationType.class)
    void nothingIsSentToAnUnverifiedRecipient(TaskNotificationType type) {
        User unverified = activeUser(RECIPIENT_ID, "Alice", "alice@example.com");
        unverified.setEmailVerifiedAt(null);
        when(userRepository.findById(RECIPIENT_ID)).thenReturn(Optional.of(unverified));

        dispatch(type, RECIPIENT_ID, null);

        verifyNoInteractions(settingsService, mailService);
    }

    @ParameterizedTest
    @EnumSource(TaskNotificationType.class)
    void nothingIsSentWhenRecipientTurnedThatNotificationOff(TaskNotificationType type) {
        when(userRepository.findById(RECIPIENT_ID))
                .thenReturn(Optional.of(activeUser(RECIPIENT_ID, "Alice", "alice@example.com")));
        when(settingsService.isEnabled(RECIPIENT_ID, type)).thenReturn(false);

        dispatch(type, RECIPIENT_ID, null);

        verify(settingsService).isEnabled(RECIPIENT_ID, type);
        verify(mailService, never()).send(any());
    }

    @ParameterizedTest
    @EnumSource(TaskNotificationType.class)
    void eachEventChecksOnlyItsOwnSetting(TaskNotificationType type) {
        stubActiveRecipient(type);

        dispatch(type, RECIPIENT_ID, null);

        verify(settingsService).isEnabled(RECIPIENT_ID, type);
        for (TaskNotificationType other : TaskNotificationType.values()) {
            if (other != type) {
                verify(settingsService, never()).isEnabled(RECIPIENT_ID, other);
            }
        }
        assertThat(sentMessage().to()).isEqualTo("alice@example.com");
    }
}
