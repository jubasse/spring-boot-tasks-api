package io.julienmetral.tasks.task.services;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBooleanProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Slf4j
@Component
@RequiredArgsConstructor
@ConditionalOnBooleanProperty(name = "task.reminders.enabled", matchIfMissing = true)
public class TaskReminderJob {

    private final TaskReminderService reminderService;

    @Scheduled(cron = "${task.reminders.cron}")
    public void run() {
        TaskReminderReport report = reminderService.sendDueReminders();

        if (report.skipped()) {
            log.info("Task reminders skipped: another instance is sending them");
            return;
        }

        if (report.dueSoon() > 0 || report.overdue() > 0) {
            log.info("Task reminders: {} due soon, {} overdue", report.dueSoon(), report.overdue());
        }
    }
}
