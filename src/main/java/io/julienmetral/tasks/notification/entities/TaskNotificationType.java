package io.julienmetral.tasks.notification.entities;

/** The task events a user can be emailed about; each has a switch in {@link NotificationSettings}. */
public enum TaskNotificationType {
    ASSIGNED,
    UNASSIGNED,
    CANCELLED,
    DELETED
}
