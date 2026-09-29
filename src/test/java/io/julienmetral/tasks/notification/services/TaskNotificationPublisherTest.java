package io.julienmetral.tasks.notification.services;

import io.julienmetral.tasks.identity.entities.User;
import io.julienmetral.tasks.identity.repositories.UserProfileRepository;
import io.julienmetral.tasks.identity.repositories.UserRepository;
import io.julienmetral.tasks.notification.entities.TaskNotificationType;
import io.julienmetral.tasks.notification.entities.WebhookEvent;
import io.julienmetral.tasks.notification.events.TaskNotificationCreated;
import io.julienmetral.tasks.task.events.TaskAssigned;
import io.julienmetral.tasks.task.events.TaskCancelled;
import io.julienmetral.tasks.task.events.TaskCommentAdded;
import io.julienmetral.tasks.task.events.TaskDeleted;
import io.julienmetral.tasks.task.events.TaskDueSoon;
import io.julienmetral.tasks.task.events.TaskOverdue;
import io.julienmetral.tasks.task.events.TaskUnassigned;
import io.julienmetral.tasks.task.events.UsersMentionedInComment;
import io.julienmetral.tasks.task.services.CommentMentions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.Consumer;

import static io.julienmetral.tasks.support.UserProfiles.active;
import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.params.provider.EnumSource.Mode.EXCLUDE;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class TaskNotificationPublisherTest {

    private static final UUID TASK_ID = UUID.fromString("00000000-0000-0000-0000-00000000000a");
    private static final UUID RECIPIENT_ID = UUID.fromString("00000000-0000-0000-0000-000000000001");
    private static final UUID ACTOR_ID = UUID.fromString("00000000-0000-0000-0000-000000000002");
    private static final UUID OTHER_ID = UUID.fromString("00000000-0000-0000-0000-000000000003");
    private static final UUID COMMENT_ID = UUID.fromString("00000000-0000-0000-0000-0000000000c1");
    private static final Instant DUE_AT = Instant.parse("2026-10-01T09:05:00Z");

    @Mock
    private UserRepository userRepository;

    @Mock
    private UserProfileRepository userProfileRepository;

    @Mock
    private ApplicationEventPublisher eventPublisher;

    private TaskNotificationPublisher publisher;

    @BeforeEach
    void createPublisher() {
        publisher = new TaskNotificationPublisher(
                userRepository, userProfileRepository, new CommentExcerpts(userProfileRepository), eventPublisher);
    }

    // Data of each event

    @Test
    void assignedGoesToTheAssigneeWithTheTaskAndTheActor() {
        stubActive(RECIPIENT_ID);
        stubActorNamed("Bob");

        publisher.onAssigned(new TaskAssigned(TASK_ID, "TASK-1", "Write tests", RECIPIENT_ID, ACTOR_ID));

        TaskNotificationCreated notification = published();
        assertThat(notification.event()).isEqualTo(WebhookEvent.TASK_ASSIGNED);
        assertThat(notification.recipientId()).isEqualTo(RECIPIENT_ID);
        assertThat(notification.data()).isEqualTo(Map.of("task", task(), "actor", actor("Bob")));
    }

    @Test
    void dataListsTheTaskThenTheActorThenTheDetails() {
        stubActive(RECIPIENT_ID);
        stubActorNamed("Bob");

        publisher.onCancelled(new TaskCancelled(TASK_ID, "TASK-1", "Write tests", RECIPIENT_ID, "Out of scope", ACTOR_ID));

        Map<String, Object> data = published().data();
        assertThat(data.keySet()).containsExactly("task", "actor", "reason");
        assertThat(keys(data.get("task"))).containsExactly("id", "reference", "title");
        assertThat(keys(data.get("actor"))).containsExactly("id", "displayName");
    }

    @Test
    void unassignedGoesToThePreviousAssignee() {
        stubActive(RECIPIENT_ID);
        stubActorNamed("Bob");

        publisher.onUnassigned(new TaskUnassigned(TASK_ID, "TASK-1", "Write tests", RECIPIENT_ID, ACTOR_ID));

        TaskNotificationCreated notification = published();
        assertThat(notification.event()).isEqualTo(WebhookEvent.TASK_UNASSIGNED);
        assertThat(notification.recipientId()).isEqualTo(RECIPIENT_ID);
        assertThat(notification.data()).isEqualTo(Map.of("task", task(), "actor", actor("Bob")));
    }

    @Test
    void cancelledCarriesTheReason() {
        stubActive(RECIPIENT_ID);
        stubActorNamed("Bob");

        publisher.onCancelled(new TaskCancelled(TASK_ID, "TASK-1", "Write tests", RECIPIENT_ID, "Out of scope", ACTOR_ID));

        TaskNotificationCreated notification = published();
        assertThat(notification.event()).isEqualTo(WebhookEvent.TASK_CANCELLED);
        assertThat(notification.data())
                .isEqualTo(Map.of("task", task(), "actor", actor("Bob"), "reason", "Out of scope"));
    }

    @Test
    void cancelledWithoutAReasonKeepsTheReasonAsNull() {
        stubActive(RECIPIENT_ID);
        stubActorNamed("Bob");

        publisher.onCancelled(new TaskCancelled(TASK_ID, "TASK-1", "Write tests", RECIPIENT_ID, null, ACTOR_ID));

        Map<String, Object> data = published().data();
        assertThat(data).containsKey("reason");
        assertThat(data.get("reason")).isNull();
    }

    @Test
    void deletedNamesTheTaskAndTheActor() {
        stubActive(RECIPIENT_ID);
        stubActorNamed("Bob");

        publisher.onDeleted(new TaskDeleted(TASK_ID, "TASK-1", "Write tests", RECIPIENT_ID, ACTOR_ID));

        TaskNotificationCreated notification = published();
        assertThat(notification.event()).isEqualTo(WebhookEvent.TASK_DELETED);
        assertThat(notification.data()).isEqualTo(Map.of("task", task(), "actor", actor("Bob")));
    }

    @Test
    void commentedGoesToTheAssigneeWithTheCommentIdAndItsExcerpt() {
        stubActive(RECIPIENT_ID);
        stubActorNamed("Bob");

        publisher.onCommentAdded(commentAdded("Looks good to me", RECIPIENT_ID));

        TaskNotificationCreated notification = published();
        assertThat(notification.event()).isEqualTo(WebhookEvent.TASK_COMMENTED);
        assertThat(notification.recipientId()).isEqualTo(RECIPIENT_ID);
        assertThat(notification.data()).isEqualTo(Map.of(
                "task", task(),
                "actor", actor("Bob"),
                "comment", Map.of("id", COMMENT_ID, "excerpt", "Looks good to me")
        ));
    }

    @Test
    void commentExcerptRendersTheMentionsWithDisplayNames() {
        stubActive(RECIPIENT_ID);
        stubActorNamed("Bob");
        when(userProfileRepository.findAllById(Set.of(OTHER_ID))).thenReturn(List.of(active(OTHER_ID, "Carol")));

        publisher.onCommentAdded(commentAdded("Ask <@" + OTHER_ID + "> first", RECIPIENT_ID));

        assertThat(published().data().get("comment"))
                .isEqualTo(Map.of("id", COMMENT_ID, "excerpt", "Ask @Carol first"));
    }

    @Test
    void commentExcerptIsCutAfterAThousandCharacters() {
        stubActive(RECIPIENT_ID);
        stubActorNamed("Bob");

        publisher.onCommentAdded(commentAdded("a".repeat(1_200), RECIPIENT_ID));

        assertThat(((Map<?, ?>) published().data().get("comment")).get("excerpt"))
                .isEqualTo("a".repeat(1_000) + "...");
    }

    @Test
    void mentionedGoesToEveryActiveMentionedUserWithTheComment() {
        stubActive(RECIPIENT_ID);
        stubActive(OTHER_ID);
        stubActorNamed("Bob");

        publisher.onMentioned(mentioned("Hello", RECIPIENT_ID, OTHER_ID));

        List<TaskNotificationCreated> notifications = published(2);
        assertThat(notifications).extracting(TaskNotificationCreated::recipientId)
                .containsExactlyInAnyOrder(RECIPIENT_ID, OTHER_ID);
        assertThat(notifications).allSatisfy(notification -> {
            assertThat(notification.event()).isEqualTo(WebhookEvent.TASK_MENTIONED);
            assertThat(notification.data()).isEqualTo(Map.of(
                    "task", task(),
                    "actor", actor("Bob"),
                    "comment", Map.of("id", COMMENT_ID, "excerpt", "Hello")
            ));
        });
    }

    @Test
    void mentionSkipsTheAuthorAndTheInactiveMentionedUsers() {
        stubActive(RECIPIENT_ID);
        stubAccount(OTHER_ID, user -> user.setEnabled(false));
        stubActorNamed("Bob");

        publisher.onMentioned(mentioned("Hello", RECIPIENT_ID, OTHER_ID, ACTOR_ID));

        assertThat(published().recipientId()).isEqualTo(RECIPIENT_ID);
    }

    @Test
    void assigneeWhoIsAlsoMentionedGetsBothTheCommentAndTheMention() {
        stubActive(RECIPIENT_ID);
        stubActorNamed("Bob");
        String body = "Over to you <@" + RECIPIENT_ID + ">";

        publisher.onCommentAdded(commentAdded(body, RECIPIENT_ID));
        publisher.onMentioned(mentioned(body, RECIPIENT_ID));

        assertThat(published(2)).extracting(TaskNotificationCreated::event)
                .containsExactly(WebhookEvent.TASK_COMMENTED, WebhookEvent.TASK_MENTIONED);
    }

    @Test
    void dueSoonHasANullActorAndCarriesTheDueDate() {
        stubActive(RECIPIENT_ID);

        publisher.onDueSoon(new TaskDueSoon(TASK_ID, "TASK-1", "Write tests", DUE_AT, RECIPIENT_ID));

        TaskNotificationCreated notification = published();
        assertThat(notification.event()).isEqualTo(WebhookEvent.TASK_DUE_SOON);
        assertThat(notification.data().keySet()).containsExactly("task", "actor", "dueAt");
        assertThat(notification.data().get("actor")).isNull();
        assertThat(notification.data().get("dueAt")).isEqualTo("2026-10-01T09:05:00Z");
        verifyNoInteractions(userProfileRepository);
    }

    @Test
    void overdueHasANullActorAndCarriesTheDueDate() {
        stubActive(RECIPIENT_ID);

        publisher.onOverdue(new TaskOverdue(TASK_ID, "TASK-1", "Write tests", DUE_AT, RECIPIENT_ID));

        TaskNotificationCreated notification = published();
        assertThat(notification.event()).isEqualTo(WebhookEvent.TASK_OVERDUE);
        assertThat(notification.data().keySet()).containsExactly("task", "actor", "dueAt");
        assertThat(notification.data().get("actor")).isNull();
        assertThat(notification.data().get("dueAt")).isEqualTo("2026-10-01T09:05:00Z");
    }

    @ParameterizedTest
    @EnumSource(value = TaskNotificationType.class, mode = EXCLUDE, names = {"DUE_SOON", "OVERDUE"})
    void actorWhoseProfileCannotBeLoadedHasANullDisplayName(TaskNotificationType type) {
        stubActive(RECIPIENT_ID);
        when(userProfileRepository.findById(ACTOR_ID)).thenReturn(Optional.empty());

        dispatch(type, RECIPIENT_ID, ACTOR_ID);

        Map<?, ?> actor = (Map<?, ?>) published().data().get("actor");
        assertThat(keys(actor)).containsExactly("id", "displayName");
        assertThat(actor.get("id")).isEqualTo(ACTOR_ID);
        assertThat(actor.get("displayName")).isNull();
    }

    @ParameterizedTest
    @EnumSource(TaskNotificationType.class)
    void eachTaskEventBecomesTheMatchingWebhookEvent(TaskNotificationType type) {
        stubActive(RECIPIENT_ID);
        lenient().when(userProfileRepository.findById(ACTOR_ID)).thenReturn(Optional.of(active(ACTOR_ID, "Bob")));

        dispatch(type, RECIPIENT_ID, ACTOR_ID);

        assertThat(published().event()).isEqualTo(WebhookEvent.of(type));
    }

    // Who receives nothing

    @ParameterizedTest
    @EnumSource(value = TaskNotificationType.class, mode = EXCLUDE, names = {"DUE_SOON", "OVERDUE"})
    void nobodyHearsAboutTheirOwnAction(TaskNotificationType type) {
        dispatch(type, ACTOR_ID, ACTOR_ID);

        verifyNoInteractions(userRepository, eventPublisher);
    }

    @ParameterizedTest
    @EnumSource(TaskNotificationType.class)
    void nothingIsPublishedWithoutARecipient(TaskNotificationType type) {
        dispatch(type, null, ACTOR_ID);

        verifyNoInteractions(userRepository, eventPublisher);
    }

    @ParameterizedTest
    @EnumSource(TaskNotificationType.class)
    void nothingIsPublishedToADisabledRecipient(TaskNotificationType type) {
        stubAccount(RECIPIENT_ID, user -> user.setEnabled(false));

        dispatch(type, RECIPIENT_ID, ACTOR_ID);

        verifyNoInteractions(eventPublisher);
    }

    @ParameterizedTest
    @EnumSource(TaskNotificationType.class)
    void nothingIsPublishedToAnUnverifiedRecipient(TaskNotificationType type) {
        stubAccount(RECIPIENT_ID, user -> user.setEmailVerifiedAt(null));

        dispatch(type, RECIPIENT_ID, ACTOR_ID);

        verifyNoInteractions(eventPublisher);
    }

    // findById skips soft-deleted accounts, and an erased one has no row left
    @ParameterizedTest
    @EnumSource(TaskNotificationType.class)
    void nothingIsPublishedToARecipientWhoseAccountCannotBeLoaded(TaskNotificationType type) {
        when(userRepository.findById(RECIPIENT_ID)).thenReturn(Optional.empty());

        dispatch(type, RECIPIENT_ID, ACTOR_ID);

        verifyNoInteractions(eventPublisher);
    }

    private void dispatch(TaskNotificationType type, UUID recipientId, UUID actorId) {
        switch (type) {
            case ASSIGNED -> publisher.onAssigned(new TaskAssigned(TASK_ID, "TASK-1", "Write tests", recipientId, actorId));
            case UNASSIGNED -> publisher.onUnassigned(
                    new TaskUnassigned(TASK_ID, "TASK-1", "Write tests", recipientId, actorId));
            case CANCELLED -> publisher.onCancelled(
                    new TaskCancelled(TASK_ID, "TASK-1", "Write tests", recipientId, "Out of scope", actorId));
            case DELETED -> publisher.onDeleted(new TaskDeleted(TASK_ID, "TASK-1", "Write tests", recipientId, actorId));
            case COMMENTED -> publisher.onCommentAdded(new TaskCommentAdded(TASK_ID, "TASK-1", "Write tests",
                    COMMENT_ID, "Body", recipientId, actorId, Set.of()));
            case MENTIONED -> publisher.onMentioned(new UsersMentionedInComment(TASK_ID, "TASK-1", "Write tests",
                    COMMENT_ID, "Body", actorId, recipientId == null ? Set.of() : Set.of(recipientId)));
            // Reminders come from the job and have no actor
            case DUE_SOON -> publisher.onDueSoon(new TaskDueSoon(TASK_ID, "TASK-1", "Write tests", DUE_AT, recipientId));
            case OVERDUE -> publisher.onOverdue(new TaskOverdue(TASK_ID, "TASK-1", "Write tests", DUE_AT, recipientId));
        }
    }

    // Mirrors TaskCommentService: the mentioned ids come from CommentMentions.parse of the body
    private static TaskCommentAdded commentAdded(String body, UUID assigneeId) {
        return new TaskCommentAdded(TASK_ID, "TASK-1", "Write tests", COMMENT_ID, body, assigneeId, ACTOR_ID,
                CommentMentions.parse(body));
    }

    private static UsersMentionedInComment mentioned(String body, UUID... userIds) {
        return new UsersMentionedInComment(TASK_ID, "TASK-1", "Write tests", COMMENT_ID, body, ACTOR_ID,
                Set.of(userIds));
    }

    private static Map<String, Object> task() {
        return Map.of("id", TASK_ID, "reference", "TASK-1", "title", "Write tests");
    }

    private static Map<String, Object> actor(String displayName) {
        return Map.of("id", ACTOR_ID, "displayName", displayName);
    }

    private void stubActive(UUID id) {
        stubAccount(id, user -> {
        });
    }

    private void stubAccount(UUID id, Consumer<User> change) {
        User user = new User();
        user.setId(id);
        user.setDisplayName("User " + id);
        user.setEmail(id + "@example.com");
        user.setEmailVerifiedAt(Instant.parse("2026-01-01T00:00:00Z"));
        change.accept(user);

        when(userRepository.findById(id)).thenReturn(Optional.of(user));
    }

    private void stubActorNamed(String displayName) {
        when(userProfileRepository.findById(ACTOR_ID)).thenReturn(Optional.of(active(ACTOR_ID, displayName)));
    }

    private static List<Object> keys(Object map) {
        return new ArrayList<>(((Map<?, ?>) map).keySet());
    }

    private TaskNotificationCreated published() {
        return published(1).getFirst();
    }

    private List<TaskNotificationCreated> published(int expected) {
        ArgumentCaptor<TaskNotificationCreated> captor = ArgumentCaptor.forClass(TaskNotificationCreated.class);
        verify(eventPublisher, times(expected)).publishEvent(captor.capture());
        return new ArrayList<>(captor.getAllValues());
    }
}
