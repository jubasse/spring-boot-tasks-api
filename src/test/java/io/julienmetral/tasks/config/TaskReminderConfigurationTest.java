package io.julienmetral.tasks.config;

import io.julienmetral.tasks.task.services.TaskReminderJob;
import io.julienmetral.tasks.task.services.TaskReminderService;
import org.junit.jupiter.api.Test;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.core.env.PropertySource;
import org.springframework.core.io.ClassPathResource;
import org.springframework.scheduling.annotation.ScheduledAnnotationBeanPostProcessor;
import org.springframework.scheduling.config.CronTask;
import org.springframework.scheduling.config.ScheduledTask;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.time.Duration;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

class TaskReminderConfigurationTest {

    // A cron that never fires while a test context is open
    private static final String YEARLY_CRON = "0 0 0 1 1 *";

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withUserConfiguration(SchedulingConfiguration.class, TaskReminderJob.class)
            .withBean(TaskReminderService.class, () -> mock(TaskReminderService.class))
            // SchedulingConfiguration also validates the media cleanup properties
            .withPropertyValues(
                    "media.cleanup.enabled=false",
                    "media.cleanup.cron=" + YEARLY_CRON,
                    "media.cleanup.retention=30d",
                    "media.cleanup.orphan-grace-period=1d"
            );

    private final ApplicationContextRunner configured = runner.withPropertyValues(
            "task.reminders.cron=" + YEARLY_CRON,
            "task.reminders.due-soon-lead-time=24h",
            "task.reminders.overdue-lookback=7d"
    );

    @Test
    void bindsTheProperties() {
        configured.withPropertyValues("task.reminders.enabled=true").run(context -> {
            assertThat(context).hasNotFailed();
            TaskReminderProperties properties = context.getBean(TaskReminderProperties.class);
            assertThat(properties.enabled()).isTrue();
            assertThat(properties.cron()).isEqualTo(YEARLY_CRON);
            assertThat(properties.dueSoonLeadTime()).isEqualTo(Duration.ofHours(24));
            assertThat(properties.overdueLookback()).isEqualTo(Duration.ofDays(7));
        });
    }

    @Test
    void schedulesTheJobOnTheConfiguredCron() {
        configured.run(context -> {
            List<String> crons = context.getBean(ScheduledAnnotationBeanPostProcessor.class)
                    .getScheduledTasks().stream()
                    .map(ScheduledTask::getTask)
                    .filter(CronTask.class::isInstance)
                    .map(task -> ((CronTask) task).getExpression())
                    .toList();
            assertThat(crons).containsExactly(YEARLY_CRON);
        });
    }

    @Test
    void missingEnabledKeepsTheJob() {
        configured.run(context -> {
            assertThat(context).hasNotFailed().hasSingleBean(TaskReminderJob.class);
            assertThat(context.getBean(TaskReminderProperties.class).enabled()).isTrue();
        });
    }

    @Test
    void disabledRemindersRemoveTheJob() {
        configured.withPropertyValues("task.reminders.enabled=false").run(context -> {
            assertThat(context).hasNotFailed().doesNotHaveBean(TaskReminderJob.class);
            assertThat(context.getBean(TaskReminderProperties.class).enabled()).isFalse();
        });
    }

    @Test
    void blankCronFailsStartup() {
        runner.withPropertyValues(
                "task.reminders.enabled=false",
                "task.reminders.cron= ",
                "task.reminders.due-soon-lead-time=24h",
                "task.reminders.overdue-lookback=7d"
        ).run(context -> assertThat(context).getFailure()
                .hasStackTraceContaining("NotBlank.task.reminders.cron"));
    }

    @Test
    void missingDueSoonLeadTimeFailsStartup() {
        runner.withPropertyValues(
                "task.reminders.enabled=false",
                "task.reminders.cron=" + YEARLY_CRON,
                "task.reminders.overdue-lookback=7d"
        ).run(context -> assertThat(context).getFailure()
                .hasStackTraceContaining("NotNull.task.reminders.dueSoonLeadTime"));
    }

    @Test
    void missingOverdueLookbackFailsStartup() {
        runner.withPropertyValues(
                "task.reminders.enabled=false",
                "task.reminders.cron=" + YEARLY_CRON,
                "task.reminders.due-soon-lead-time=24h"
        ).run(context -> assertThat(context).getFailure()
                .hasStackTraceContaining("NotNull.task.reminders.overdueLookback"));
    }

    @Test
    void applicationYamlShipsTheDocumentedDefaults() {
        runner.withInitializer(context -> context.getEnvironment().getPropertySources()
                        .addLast(mainApplicationYaml()))
                .withPropertyValues("task.reminders.enabled=false")
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    TaskReminderProperties properties = context.getBean(TaskReminderProperties.class);
                    assertThat(properties.cron()).isEqualTo("0 */15 * * * *");
                    assertThat(properties.dueSoonLeadTime()).isEqualTo(Duration.ofHours(24));
                    assertThat(properties.overdueLookback()).isEqualTo(Duration.ofDays(7));
                });
    }

    private static PropertySource<?> mainApplicationYaml() {
        try {
            return new YamlPropertySourceLoader()
                    .load("main application.yaml", new ClassPathResource("application.yaml"))
                    .getFirst();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
