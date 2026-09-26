package io.julienmetral.tasks.task.services;

import io.julienmetral.tasks.TestcontainersConfiguration;
import io.julienmetral.tasks.config.TaskReminderProperties;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Import;
import org.springframework.scheduling.config.CronTask;
import org.springframework.scheduling.config.ScheduledTaskHolder;
import org.springframework.scheduling.support.CronExpression;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

@Import(TestcontainersConfiguration.class)
@SpringBootTest(properties = "task.reminders.enabled=true")
class TaskReminderJobTests {

    @Autowired
    private ApplicationContext context;

    @Autowired
    private TaskReminderProperties properties;

    @Test
    void enabledJobIsScheduledOnTheConfiguredCronEveryFifteenMinutes() {
        assertThat(context.getBeansOfType(TaskReminderJob.class)).hasSize(1);

        List<CronTask> reminderTasks = context.getBeansOfType(ScheduledTaskHolder.class).values().stream()
                .flatMap(holder -> holder.getScheduledTasks().stream())
                .map(scheduled -> scheduled.getTask())
                .filter(CronTask.class::isInstance)
                .map(CronTask.class::cast)
                .filter(task -> task.getRunnable().toString().contains(TaskReminderJob.class.getName()))
                .toList();

        assertThat(reminderTasks).singleElement()
                .extracting(CronTask::getExpression)
                .isEqualTo(properties.cron());

        CronExpression cron = CronExpression.parse(properties.cron());
        LocalDateTime start = LocalDateTime.of(2100, 1, 1, 0, 0);
        LocalDateTime next = cron.next(start);
        assertThat(Duration.between(start, next)).isEqualTo(Duration.ofMinutes(15));
        assertThat(Duration.between(next, cron.next(next))).isEqualTo(Duration.ofMinutes(15));
    }
}
