package io.julienmetral.tasks.notification.mail;

import io.julienmetral.tasks.identity.entities.User;
import io.julienmetral.tasks.identity.entities.UserStatus;
import io.julienmetral.tasks.identity.repositories.UserRepository;
import io.julienmetral.tasks.mail.MailMessage;
import io.julienmetral.tasks.mail.MailService;
import io.julienmetral.tasks.notification.entities.TaskNotificationType;
import io.julienmetral.tasks.notification.services.NotificationSettingsService;
import io.julienmetral.tasks.task.events.TaskAssigned;
import io.julienmetral.tasks.task.events.TaskCancelled;
import io.julienmetral.tasks.task.events.TaskDeleted;
import io.julienmetral.tasks.task.events.TaskUnassigned;
import lombok.RequiredArgsConstructor;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

import java.util.Objects;
import java.util.UUID;

/**
 * Emails the assignee concerned by a task change. Nobody is emailed about their own action, and only active users
 * (enabled, verified email) who kept the matching setting on receive anything. Runs inside the task transaction;
 * {@link MailService} sends after commit.
 */
@Component
@RequiredArgsConstructor
public class TaskNotificationSender {

    private static final String FOOTER = "\n\nYou can turn these emails off in your notification settings.\n";

    private final UserRepository userRepository;
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

    private String actorName(UUID actorId) {
        return actorId == null
                ? "Someone"
                : userRepository.findById(actorId).map(User::getDisplayName).orElse("Someone");
    }
}
