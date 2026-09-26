package io.julienmetral.tasks.task.services;

import io.julienmetral.tasks.config.TaskReminderProperties;
import io.julienmetral.tasks.task.entities.TaskReminderKind;
import io.julienmetral.tasks.task.events.TaskDueSoon;
import io.julienmetral.tasks.task.events.TaskOverdue;
import io.julienmetral.tasks.task.repositories.TaskReminderQueries;
import io.julienmetral.tasks.task.repositories.TaskReminderQueries.Reminder;
import lombok.RequiredArgsConstructor;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;
import java.util.List;

@Service
@RequiredArgsConstructor
public class TaskReminderService {

    private final TaskReminderQueries queries;
    private final TaskReminderProperties properties;
    private final ApplicationEventPublisher eventPublisher;
    private final Clock clock;

    /**
     * Publishes {@link TaskDueSoon} for open tasks due within the lead time and {@link TaskOverdue} for those that
     * passed their due date within the lookback, each once per due date and assignee. The sent reminders are
     * recorded in the same transaction, and the emails go after commit: a failed run sends nothing and the next one
     * retries.
     */
    @Transactional
    public TaskReminderReport sendDueReminders() {
        // Several instances may run the schedule: only the one holding the lock works
        if (!queries.tryLock()) {
            return TaskReminderReport.skippedRun();
        }

        Instant now = clock.instant();

        List<Reminder> dueSoon = queries.recordReminders(
                TaskReminderKind.DUE_SOON, now, now.plus(properties.dueSoonLeadTime()), now);
        List<Reminder> overdue = queries.recordReminders(
                TaskReminderKind.OVERDUE, now.minus(properties.overdueLookback()), now, now);

        dueSoon.forEach(reminder -> eventPublisher.publishEvent(new TaskDueSoon(
                reminder.taskId(), reminder.reference(), reminder.title(), reminder.dueAt(), reminder.recipientId()
        )));
        overdue.forEach(reminder -> eventPublisher.publishEvent(new TaskOverdue(
                reminder.taskId(), reminder.reference(), reminder.title(), reminder.dueAt(), reminder.recipientId()
        )));

        return new TaskReminderReport(false, dueSoon.size(), overdue.size());
    }
}
