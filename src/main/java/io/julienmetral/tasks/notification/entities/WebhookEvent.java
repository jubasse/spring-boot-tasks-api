package io.julienmetral.tasks.notification.entities;

import com.fasterxml.jackson.annotation.JsonValue;

/**
 * The events a webhook endpoint can subscribe to, one per {@link TaskNotificationType}. {@link #type()} is the name
 * clients see, in the API and as the {@code type} of a delivered payload (Standard Webhooks convention).
 */
public enum WebhookEvent {
    TASK_ASSIGNED("task.assigned", TaskNotificationType.ASSIGNED),
    TASK_UNASSIGNED("task.unassigned", TaskNotificationType.UNASSIGNED),
    TASK_CANCELLED("task.cancelled", TaskNotificationType.CANCELLED),
    TASK_DELETED("task.deleted", TaskNotificationType.DELETED),
    TASK_COMMENTED("task.commented", TaskNotificationType.COMMENTED),
    TASK_MENTIONED("task.mentioned", TaskNotificationType.MENTIONED),
    TASK_DUE_SOON("task.due_soon", TaskNotificationType.DUE_SOON),
    TASK_OVERDUE("task.overdue", TaskNotificationType.OVERDUE);

    private final String type;
    private final TaskNotificationType notificationType;

    WebhookEvent(String type, TaskNotificationType notificationType) {
        this.type = type;
        this.notificationType = notificationType;
    }

    @JsonValue
    public String type() {
        return type;
    }

    public static WebhookEvent of(TaskNotificationType notificationType) {
        for (WebhookEvent event : values()) {
            if (event.notificationType == notificationType) {
                return event;
            }
        }

        throw new IllegalArgumentException("No webhook event for " + notificationType);
    }
}
