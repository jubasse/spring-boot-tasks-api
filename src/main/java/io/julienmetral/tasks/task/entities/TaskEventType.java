package io.julienmetral.tasks.task.entities;

public enum TaskEventType {
    CREATED,
    UPDATED,
    ASSIGNED,
    UNASSIGNED,
    STATUS_CHANGED,
    CANCELLED,
    ARCHIVED,
    UNARCHIVED,
    ATTACHMENT_ADDED,
    ATTACHMENT_REMOVED
}
