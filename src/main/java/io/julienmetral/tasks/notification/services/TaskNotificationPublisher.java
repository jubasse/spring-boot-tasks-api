package io.julienmetral.tasks.notification.services;

import io.julienmetral.tasks.identity.entities.UserProfile;
import io.julienmetral.tasks.identity.entities.UserStatus;
import io.julienmetral.tasks.identity.repositories.UserProfileRepository;
import io.julienmetral.tasks.identity.repositories.UserRepository;
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
import lombok.RequiredArgsConstructor;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/**
 * Turns the task events into one {@link TaskNotificationCreated} per recipient, with the rules the webhooks and the
 * notification streams share: nobody hears about their own action, and only an active account receives anything.
 * Runs inside the publisher's transaction. Emails keep their own rules and switches ({@code TaskNotificationSender}).
 */
@Component
@RequiredArgsConstructor
public class TaskNotificationPublisher {

    private final UserRepository userRepository;
    private final UserProfileRepository userProfileRepository;
    private final CommentExcerpts commentExcerpts;
    private final ApplicationEventPublisher eventPublisher;

    @EventListener
    public void onAssigned(TaskAssigned event) {
        publish(WebhookEvent.TASK_ASSIGNED, event.assigneeId(), event.actorId(),
                task(event.taskId(), event.reference(), event.title()), Map.of());
    }

    @EventListener
    public void onUnassigned(TaskUnassigned event) {
        publish(WebhookEvent.TASK_UNASSIGNED, event.previousAssigneeId(), event.actorId(),
                task(event.taskId(), event.reference(), event.title()), Map.of());
    }

    @EventListener
    public void onCancelled(TaskCancelled event) {
        Map<String, Object> details = new LinkedHashMap<>();
        details.put("reason", event.reason());

        publish(WebhookEvent.TASK_CANCELLED, event.assigneeId(), event.actorId(),
                task(event.taskId(), event.reference(), event.title()), details);
    }

    @EventListener
    public void onDeleted(TaskDeleted event) {
        publish(WebhookEvent.TASK_DELETED, event.assigneeId(), event.actorId(),
                task(event.taskId(), event.reference(), event.title()), Map.of());
    }

    @EventListener
    public void onCommentAdded(TaskCommentAdded event) {
        publish(WebhookEvent.TASK_COMMENTED, event.assigneeId(), event.authorId(),
                task(event.taskId(), event.reference(), event.title()),
                Map.of("comment", comment(event.commentId(), event.body())));
    }

    @EventListener
    public void onMentioned(UsersMentionedInComment event) {
        Map<String, Object> task = task(event.taskId(), event.reference(), event.title());
        Map<String, Object> details = Map.of("comment", comment(event.commentId(), event.body()));

        event.mentionedUserIds().forEach(userId ->
                publish(WebhookEvent.TASK_MENTIONED, userId, event.authorId(), task, details));
    }

    @EventListener
    public void onDueSoon(TaskDueSoon event) {
        publish(WebhookEvent.TASK_DUE_SOON, event.assigneeId(), null,
                task(event.taskId(), event.reference(), event.title()), Map.of("dueAt", event.dueAt().toString()));
    }

    @EventListener
    public void onOverdue(TaskOverdue event) {
        publish(WebhookEvent.TASK_OVERDUE, event.assigneeId(), null,
                task(event.taskId(), event.reference(), event.title()), Map.of("dueAt", event.dueAt().toString()));
    }

    private void publish(
            WebhookEvent event,
            UUID recipientId,
            UUID actorId,
            Map<String, Object> task,
            Map<String, Object> details
    ) {
        if (recipientId == null || Objects.equals(recipientId, actorId) || !isActive(recipientId)) {
            return;
        }

        Map<String, Object> data = new LinkedHashMap<>();
        data.put("task", task);
        data.put("actor", actor(actorId));
        data.putAll(details);

        eventPublisher.publishEvent(new TaskNotificationCreated(event, recipientId, data));
    }

    // The account itself, never the status cache: its entry can lag behind a disabling by up to its time to live
    private boolean isActive(UUID userId) {
        return userRepository.findById(userId).map(UserStatus::of).orElse(null) == UserStatus.ACTIVE;
    }

    private Map<String, Object> actor(UUID actorId) {
        if (actorId == null) {
            return null;
        }

        Map<String, Object> actor = new LinkedHashMap<>();
        actor.put("id", actorId);
        actor.put("displayName", userProfileRepository.findById(actorId).map(UserProfile::getDisplayName).orElse(null));

        return actor;
    }

    private static Map<String, Object> task(UUID id, String reference, String title) {
        Map<String, Object> task = new LinkedHashMap<>();
        task.put("id", id);
        task.put("reference", reference);
        task.put("title", title);

        return task;
    }

    private Map<String, Object> comment(UUID id, String body) {
        Map<String, Object> comment = new LinkedHashMap<>();
        comment.put("id", id);
        comment.put("excerpt", commentExcerpts.of(body));

        return comment;
    }
}
