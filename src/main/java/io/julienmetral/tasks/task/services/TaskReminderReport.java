package io.julienmetral.tasks.task.services;

/**
 * @param dueSoon due-soon reminders recorded (an email follows only for active assignees who kept it on)
 * @param overdue overdue reminders recorded, with the same caveat
 */
public record TaskReminderReport(
        int dueSoon,
        int overdue
) {
}
