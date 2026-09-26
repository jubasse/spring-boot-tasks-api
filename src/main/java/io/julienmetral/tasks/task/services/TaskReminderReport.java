package io.julienmetral.tasks.task.services;

/**
 * @param skipped another instance held the reminder lock, so nothing was done
 * @param dueSoon due-soon reminders recorded (an email follows only for active assignees who kept it on)
 * @param overdue overdue reminders recorded, with the same caveat
 */
public record TaskReminderReport(
        boolean skipped,
        int dueSoon,
        int overdue
) {

    public static TaskReminderReport skippedRun() {
        return new TaskReminderReport(true, 0, 0);
    }
}
