package io.julienmetral.tasks.notification.mail;

import io.julienmetral.tasks.identity.entities.User;
import io.julienmetral.tasks.identity.repositories.UserRepository;
import io.julienmetral.tasks.identity.repositories.UserSummaryRepository;
import io.julienmetral.tasks.mail.MailMessage;
import io.julienmetral.tasks.mail.MailService;
import io.julienmetral.tasks.notification.entities.TaskNotificationType;
import io.julienmetral.tasks.notification.services.NotificationSettingsService;
import io.julienmetral.tasks.task.events.TaskAssigned;
import io.julienmetral.tasks.task.events.TaskCancelled;
import io.julienmetral.tasks.task.events.TaskCommentAdded;
import io.julienmetral.tasks.task.events.TaskDeleted;
import io.julienmetral.tasks.task.events.TaskUnassigned;
import io.julienmetral.tasks.task.events.UsersMentionedInComment;
import io.julienmetral.tasks.task.services.CommentMentions;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import static io.julienmetral.tasks.support.UserSummaries.active;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class TaskNotificationSenderTest {

    private static final UUID TASK_ID = UUID.fromString("00000000-0000-0000-0000-00000000000a");
    private static final UUID RECIPIENT_ID = UUID.fromString("00000000-0000-0000-0000-000000000001");
    private static final UUID ACTOR_ID = UUID.fromString("00000000-0000-0000-0000-000000000002");
    private static final UUID OTHER_ID = UUID.fromString("00000000-0000-0000-0000-000000000003");
    private static final UUID COMMENT_ID = UUID.fromString("00000000-0000-0000-0000-0000000000c1");
    private static final String FOOTER = "\n\nYou can turn these emails off in your notification settings.\n";

    @Mock
    private UserRepository userRepository;

    @Mock
    private UserSummaryRepository userSummaryRepository;

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
            case COMMENTED -> sender.onCommentAdded(commentAdded("Body", recipientId, actorId));
            case MENTIONED -> sender.onMentioned(new UsersMentionedInComment(TASK_ID, "TASK-1", "Title", COMMENT_ID,
                    "Body", actorId, recipientId == null ? Set.of() : Set.of(recipientId)));
        }
    }

    // Mirrors TaskCommentService: the mentioned ids come from CommentMentions.parse of the body
    private static TaskCommentAdded commentAdded(String body, UUID assigneeId, UUID authorId) {
        return new TaskCommentAdded(TASK_ID, "TASK-1", "Write tests", COMMENT_ID, body, assigneeId, authorId,
                CommentMentions.parse(body));
    }

    private static UsersMentionedInComment mentioned(String body, UUID authorId, UUID... userIds) {
        return new UsersMentionedInComment(TASK_ID, "TASK-1", "Write tests", COMMENT_ID, body, authorId,
                Set.of(userIds));
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

    private void stubActiveUser(UUID id, String displayName, String email, TaskNotificationType type) {
        when(userRepository.findById(id)).thenReturn(Optional.of(activeUser(id, displayName, email)));
        when(settingsService.isEnabled(id, type)).thenReturn(true);
    }

    private List<MailMessage> sentMessages(int expected) {
        ArgumentCaptor<MailMessage> captor = ArgumentCaptor.forClass(MailMessage.class);
        verify(mailService, times(expected)).send(captor.capture());
        return captor.getAllValues();
    }

    @Nested
    class CommentAdded {

        @Test
        void emailsTheAssigneeWithTheCommentExcerpt() {
            stubActiveRecipient(TaskNotificationType.COMMENTED);
            stubActor();

            sender.onCommentAdded(commentAdded("Looks good to me", RECIPIENT_ID, ACTOR_ID));

            assertThat(sentMessage()).isEqualTo(new MailMessage(
                    "alice@example.com",
                    "New comment on task TASK-1",
                    "Hello Alice,\n\nBob commented on the task TASK-1: \"Write tests\".\n\nLooks good to me" + FOOTER
            ));
        }

        @Test
        void isNotEmailedToAnAssigneeWhoIsMentioned() {
            when(settingsService.isEnabled(RECIPIENT_ID, TaskNotificationType.MENTIONED)).thenReturn(true);

            sender.onCommentAdded(commentAdded("Over to you <@" + RECIPIENT_ID + ">", RECIPIENT_ID, ACTOR_ID));

            verifyNoInteractions(userRepository, userSummaryRepository, mailService);
        }

        @Test
        void isEmailedToAMentionedAssigneeWhoTurnedMentionsOff() {
            when(settingsService.isEnabled(RECIPIENT_ID, TaskNotificationType.MENTIONED)).thenReturn(false);
            stubActiveRecipient(TaskNotificationType.COMMENTED);
            stubActor();

            sender.onCommentAdded(commentAdded("Over to you <@" + RECIPIENT_ID + ">", RECIPIENT_ID, ACTOR_ID));

            assertThat(sentMessage().subject()).isEqualTo("New comment on task TASK-1");
        }

        @Test
        void isStillEmailedToTheAssigneeWhenOnlyOthersAreMentioned() {
            stubActiveRecipient(TaskNotificationType.COMMENTED);
            stubActor();

            sender.onCommentAdded(commentAdded("cc <@" + OTHER_ID + ">", RECIPIENT_ID, ACTOR_ID));

            assertThat(sentMessage().to()).isEqualTo("alice@example.com");
        }

        @Test
        void isNotEmailedToAnAssigneeWhoWroteIt() {
            stubActor();

            sender.onCommentAdded(commentAdded("Note to self", ACTOR_ID, ACTOR_ID));

            verifyNoInteractions(settingsService, mailService);
        }

        @Test
        void isNotEmailedWhenTheTaskHasNoAssignee() {
            sender.onCommentAdded(commentAdded("Anyone?", null, ACTOR_ID));

            verifyNoInteractions(settingsService, mailService);
        }

        @Test
        void unassignedTaskWithAnImmutableMentionSetDoesNotThrow() {
            TaskCommentAdded event = new TaskCommentAdded(TASK_ID, "TASK-1", "Write tests", COMMENT_ID, "Anyone?",
                    null, ACTOR_ID, Set.of());

            sender.onCommentAdded(event);

            verifyNoInteractions(settingsService, mailService);
        }

        @Test
        void isNotEmailedToAnInactiveAssignee() {
            User disabled = activeUser(RECIPIENT_ID, "Alice", "alice@example.com");
            disabled.setEnabled(false);
            when(userRepository.findById(RECIPIENT_ID)).thenReturn(Optional.of(disabled));
            stubActor();

            sender.onCommentAdded(commentAdded("Hello", RECIPIENT_ID, ACTOR_ID));

            verifyNoInteractions(settingsService, mailService);
        }

        @Test
        void isNotEmailedWhenTheAssigneeTurnedCommentsOff() {
            when(userRepository.findById(RECIPIENT_ID))
                    .thenReturn(Optional.of(activeUser(RECIPIENT_ID, "Alice", "alice@example.com")));
            when(settingsService.isEnabled(RECIPIENT_ID, TaskNotificationType.COMMENTED)).thenReturn(false);
            stubActor();

            sender.onCommentAdded(commentAdded("Hello", RECIPIENT_ID, ACTOR_ID));

            verify(settingsService, never()).isEnabled(RECIPIENT_ID, TaskNotificationType.MENTIONED);
            verifyNoInteractions(mailService);
        }
    }

    @Nested
    class Mentioned {

        @Test
        void emailsTheMentionedUserNamingTheAuthorInTheSubject() {
            stubActiveRecipient(TaskNotificationType.MENTIONED);
            stubActor();
            when(userSummaryRepository.findAllById(Set.of(RECIPIENT_ID)))
                    .thenReturn(List.of(active(RECIPIENT_ID, "Alice")));

            sender.onMentioned(mentioned("Can you check, <@" + RECIPIENT_ID + ">?", ACTOR_ID, RECIPIENT_ID));

            assertThat(sentMessage()).isEqualTo(new MailMessage(
                    "alice@example.com",
                    "Bob mentioned you on task TASK-1",
                    "Hello Alice,\n\nBob mentioned you in a comment on the task TASK-1: \"Write tests\".\n\n"
                            + "Can you check, @Alice?" + FOOTER
            ));
        }

        @Test
        void emailsEachMentionedUserExceptTheAuthor() {
            stubActiveRecipient(TaskNotificationType.MENTIONED);
            stubActiveUser(OTHER_ID, "Carol", "carol@example.com", TaskNotificationType.MENTIONED);
            stubActor();

            sender.onMentioned(mentioned("Team", ACTOR_ID, RECIPIENT_ID, OTHER_ID, ACTOR_ID));

            assertThat(sentMessages(2))
                    .extracting(MailMessage::to)
                    .containsExactlyInAnyOrder("alice@example.com", "carol@example.com");
            verify(settingsService, never()).isEnabled(ACTOR_ID, TaskNotificationType.MENTIONED);
        }

        @Test
        void skipsUsersWhoTurnedMentionsOff() {
            when(userRepository.findById(RECIPIENT_ID))
                    .thenReturn(Optional.of(activeUser(RECIPIENT_ID, "Alice", "alice@example.com")));
            when(settingsService.isEnabled(RECIPIENT_ID, TaskNotificationType.MENTIONED)).thenReturn(false);
            stubActiveUser(OTHER_ID, "Carol", "carol@example.com", TaskNotificationType.MENTIONED);
            stubActor();

            sender.onMentioned(mentioned("Team", ACTOR_ID, RECIPIENT_ID, OTHER_ID));

            assertThat(sentMessage().to()).isEqualTo("carol@example.com");
        }

        @Test
        void skipsUsersWhoAreNoLongerActive() {
            stubActiveRecipient(TaskNotificationType.MENTIONED);
            User unverified = activeUser(OTHER_ID, "Carol", "carol@example.com");
            unverified.setEmailVerifiedAt(null);
            when(userRepository.findById(OTHER_ID)).thenReturn(Optional.of(unverified));
            stubActor();

            sender.onMentioned(mentioned("Team", ACTOR_ID, RECIPIENT_ID, OTHER_ID));

            assertThat(sentMessage().to()).isEqualTo("alice@example.com");
            verify(settingsService, never()).isEnabled(OTHER_ID, TaskNotificationType.MENTIONED);
        }

        @Test
        void checksTheMentionSwitchNotTheCommentSwitch() {
            stubActiveRecipient(TaskNotificationType.MENTIONED);
            stubActor();

            sender.onMentioned(mentioned("Hi", ACTOR_ID, RECIPIENT_ID));

            verify(settingsService, never()).isEnabled(RECIPIENT_ID, TaskNotificationType.COMMENTED);
            assertThat(sentMessage().to()).isEqualTo("alice@example.com");
        }

        @Test
        void mentioningOnlyYourselfSendsNothing() {
            stubActor();

            sender.onMentioned(mentioned("Note to self", ACTOR_ID, ACTOR_ID));

            verifyNoInteractions(settingsService, mailService);
        }
    }

    @Nested
    class Excerpt {

        private static final String PREFIX = "Hello Alice,\n\nSomeone commented on the task TASK-1: \"Write tests\".\n\n";

        private String commentExcerpt(String body) {
            stubActiveRecipient(TaskNotificationType.COMMENTED);
            when(userRepository.findById(ACTOR_ID)).thenReturn(Optional.empty());

            sender.onCommentAdded(commentAdded(body, RECIPIENT_ID, ACTOR_ID));

            String text = sentMessage().text();
            assertThat(text).startsWith(PREFIX).endsWith(FOOTER);
            return text.substring(PREFIX.length(), text.length() - FOOTER.length());
        }

        @Test
        void rendersMentionTokensAsNames() {
            when(userSummaryRepository.findAllById(Set.of(OTHER_ID, ACTOR_ID)))
                    .thenReturn(List.of(active(OTHER_ID, "Carol"), active(ACTOR_ID, "Bob")));

            String excerpt = commentExcerpt("<@" + OTHER_ID + "> and <@" + ACTOR_ID + ">, see <@" + OTHER_ID + ">");

            assertThat(excerpt).isEqualTo("@Carol and @Bob, see @Carol");
        }

        @Test
        void keepsTheTokenOfAUserWhoCannotBeFound() {
            when(userSummaryRepository.findAllById(Set.of(OTHER_ID))).thenReturn(List.of());

            String excerpt = commentExcerpt("Ask <@" + OTHER_ID + ">");

            assertThat(excerpt).isEqualTo("Ask <@" + OTHER_ID + ">");
        }

        @Test
        void aBodyOfExactlyTheLimitIsNotTruncated() {
            String body = "a".repeat(1_000);

            assertThat(commentExcerpt(body)).isEqualTo(body);
        }

        @Test
        void aBodyAboveTheLimitIsTruncatedWithAnEllipsis() {
            String excerpt = commentExcerpt("a".repeat(999) + "bc");

            assertThat(excerpt).isEqualTo("a".repeat(999) + "b...");
        }

        @Test
        void theLimitAppliesToTheRenderedText() {
            when(userSummaryRepository.findAllById(Set.of(OTHER_ID))).thenReturn(List.of(active(OTHER_ID, "Carol")));
            // 1029 characters as written, 996 once the token becomes @Carol
            String body = "x".repeat(990) + "<@" + OTHER_ID + ">";

            assertThat(commentExcerpt(body)).isEqualTo("x".repeat(990) + "@Carol");
        }

        @Test
        void aNameThatPushesTheTextAboveTheLimitIsTruncated() {
            when(userSummaryRepository.findAllById(Set.of(OTHER_ID)))
                    .thenReturn(List.of(active(OTHER_ID, "C".repeat(100))));
            String body = "x".repeat(950) + "<@" + OTHER_ID + ">";

            assertThat(commentExcerpt(body)).isEqualTo("x".repeat(950) + "@" + "C".repeat(49) + "...");
        }

        @Test
        void mentionEmailsUseTheSameTruncatedExcerpt() {
            stubActiveRecipient(TaskNotificationType.MENTIONED);
            stubActor();
            when(userSummaryRepository.findAllById(Set.of(RECIPIENT_ID)))
                    .thenReturn(List.of(active(RECIPIENT_ID, "Alice")));
            String body = "y".repeat(1_200) + "<@" + RECIPIENT_ID + ">";

            sender.onMentioned(mentioned(body, ACTOR_ID, RECIPIENT_ID));

            assertThat(sentMessage().text()).endsWith("\n\n" + "y".repeat(1_000) + "..." + FOOTER);
        }
    }
}
