package io.julienmetral.tasks.notification.mail;

import io.julienmetral.tasks.identity.entities.User;
import io.julienmetral.tasks.identity.entities.UserStatus;
import io.julienmetral.tasks.identity.entities.UserProfile;
import io.julienmetral.tasks.identity.repositories.UserRepository;
import io.julienmetral.tasks.identity.repositories.UserProfileRepository;
import io.julienmetral.tasks.mail.MailMessage;
import io.julienmetral.tasks.mail.MailService;
import io.julienmetral.tasks.notification.entities.TaskNotificationType;
import io.julienmetral.tasks.notification.services.NotificationSettingsService;
import io.julienmetral.tasks.task.events.TaskAssigned;
import io.julienmetral.tasks.task.events.TaskCancelled;
import io.julienmetral.tasks.task.events.TaskCommentAdded;
import io.julienmetral.tasks.task.events.TaskDeleted;
import io.julienmetral.tasks.task.events.TaskDueSoon;
import io.julienmetral.tasks.task.events.TaskOverdue;
import io.julienmetral.tasks.task.events.TaskUnassigned;
import io.julienmetral.tasks.task.events.UsersMentionedInComment;
import io.julienmetral.tasks.task.services.CommentMentions;
import lombok.RequiredArgsConstructor;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * Emails the assignee concerned by a task change or a due-date reminder, and the users mentioned in a comment.
 * Nobody is emailed about their own action, and only active users (enabled, verified email) who kept the matching
 * setting on receive anything. Runs inside the publisher's transaction; {@link MailService} sends after commit.
 */
@Component
@RequiredArgsConstructor
public class TaskNotificationSender {

    private static final String FOOTER = "\n\nYou can turn these emails off in your notification settings.\n";

    private static final int MAX_EXCERPT_LENGTH = 1_000;

    // Users have no time zone yet, so dates are shown in UTC and say so
    private static final DateTimeFormatter DUE_DATE = DateTimeFormatter
            .ofPattern("yyyy-MM-dd HH:mm 'UTC'")
            .withZone(ZoneOffset.UTC);

    private final UserRepository userRepository;
    private final UserProfileRepository userProfileRepository;
    private final NotificationSettingsService settingsService;
    private final MailService mailService;

    @EventListener
    public void onAssigned(TaskAssigned event) {
        notify(TaskNotificationType.ASSIGNED, event.assigneeId(), event.actorId(),
                "Task %s was assigned to you".formatted(event.reference()),
                "%s assigned you the task %s: \"%s\".".formatted(
                        actorName(event.actorId()), event.reference(), event.title()));
    }

    @EventListener
    public void onUnassigned(TaskUnassigned event) {
        notify(TaskNotificationType.UNASSIGNED, event.previousAssigneeId(), event.actorId(),
                "Task %s is no longer assigned to you".formatted(event.reference()),
                "%s assigned the task %s: \"%s\" to someone else.".formatted(
                        actorName(event.actorId()), event.reference(), event.title()));
    }

    @EventListener
    public void onCancelled(TaskCancelled event) {
        String reason = event.reason() == null ? "" : "\n\nReason: " + event.reason();

        notify(TaskNotificationType.CANCELLED, event.assigneeId(), event.actorId(),
                "Task %s was cancelled".formatted(event.reference()),
                "%s cancelled the task %s: \"%s\".%s".formatted(
                        actorName(event.actorId()), event.reference(), event.title(), reason));
    }

    @EventListener
    public void onDeleted(TaskDeleted event) {
        notify(TaskNotificationType.DELETED, event.assigneeId(), event.actorId(),
                "Task %s was deleted".formatted(event.reference()),
                "%s deleted the task %s: \"%s\".".formatted(
                        actorName(event.actorId()), event.reference(), event.title()));
    }

    @EventListener
    public void onCommentAdded(TaskCommentAdded event) {
        UUID assigneeId = event.assigneeId();

        // The null check comes first: Set.of(...).contains(null) throws, and this listener runs inside the comment's
        // transaction
        if (assigneeId == null) {
            return;
        }

        // A mentioned assignee gets the mention email instead, unless they turned mentions off
        if (event.mentionedUserIds().contains(assigneeId)
                && settingsService.isEnabled(assigneeId, TaskNotificationType.MENTIONED)) {
            return;
        }

        notify(TaskNotificationType.COMMENTED, assigneeId, event.authorId(),
                "New comment on task %s".formatted(event.reference()),
                "%s commented on the task %s: \"%s\".\n\n%s".formatted(
                        actorName(event.authorId()), event.reference(), event.title(), excerpt(event.body())));
    }

    @EventListener
    public void onMentioned(UsersMentionedInComment event) {
        String actorName = actorName(event.authorId());
        String subject = "%s mentioned you on task %s".formatted(actorName, event.reference());
        String body = "%s mentioned you in a comment on the task %s: \"%s\".\n\n%s".formatted(
                actorName, event.reference(), event.title(), excerpt(event.body()));

        event.mentionedUserIds().forEach(userId ->
                notify(TaskNotificationType.MENTIONED, userId, event.authorId(), subject, body));
    }

    @EventListener
    public void onDueSoon(TaskDueSoon event) {
        notify(TaskNotificationType.DUE_SOON, event.assigneeId(), null,
                "Task %s is due soon".formatted(event.reference()),
                "The task %s: \"%s\" is due on %s.".formatted(
                        event.reference(), event.title(), dueDate(event.dueAt())));
    }

    @EventListener
    public void onOverdue(TaskOverdue event) {
        notify(TaskNotificationType.OVERDUE, event.assigneeId(), null,
                "Task %s is overdue".formatted(event.reference()),
                "The task %s: \"%s\" was due on %s and is not done yet.".formatted(
                        event.reference(), event.title(), dueDate(event.dueAt())));
    }

    private void notify(TaskNotificationType type, UUID recipientId, UUID actorId, String subject, String body) {
        if (recipientId == null || Objects.equals(recipientId, actorId)) {
            return;
        }

        User recipient = userRepository
                .findById(recipientId)
                .filter(user -> UserStatus.of(user) == UserStatus.ACTIVE)
                .orElse(null);

        if (recipient == null || !settingsService.isEnabled(recipientId, type)) {
            return;
        }

        mailService.send(new MailMessage(
                recipient.getEmail(),
                subject,
                "Hello %s,\n\n%s%s".formatted(recipient.getDisplayName(), body, FOOTER)
        ));
    }

    private static String dueDate(Instant dueAt) {
        return DUE_DATE.format(dueAt);
    }

    private String excerpt(String body) {
        Map<UUID, String> names = userProfileRepository
                .findAllById(CommentMentions.parse(body))
                .stream()
                .collect(Collectors.toMap(UserProfile::getId, UserProfile::getDisplayName));
        String rendered = CommentMentions.render(body, names::get);

        return rendered.length() <= MAX_EXCERPT_LENGTH
                ? rendered
                : rendered.substring(0, MAX_EXCERPT_LENGTH) + "...";
    }

    private String actorName(UUID actorId) {
        return actorId == null
                ? "Someone"
                : userRepository.findById(actorId).map(User::getDisplayName).orElse("Someone");
    }
}
