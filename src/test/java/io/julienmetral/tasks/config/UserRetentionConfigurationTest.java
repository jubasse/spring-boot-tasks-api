package io.julienmetral.tasks.config;

import io.julienmetral.tasks.identity.services.UserRetentionJob;
import io.julienmetral.tasks.identity.services.UserRetentionService;
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

class UserRetentionConfigurationTest {

    // A cron that never fires while a test context is open
    private static final String YEARLY_CRON = "0 0 0 1 1 *";

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withUserConfiguration(SchedulingConfiguration.class, UserRetentionJob.class)
            .withBean(UserRetentionService.class, () -> mock(UserRetentionService.class))
            // SchedulingConfiguration also validates the media cleanup and reminder properties
            .withPropertyValues(
                    "media.cleanup.enabled=false",
                    "media.cleanup.cron=" + YEARLY_CRON,
                    "media.cleanup.retention=30d",
                    "media.cleanup.orphan-grace-period=1d",
                    "task.reminders.enabled=false",
                    "task.reminders.cron=" + YEARLY_CRON,
                    "task.reminders.due-soon-lead-time=24h",
                    "task.reminders.overdue-lookback=7d"
            );

    private final ApplicationContextRunner configured = runner.withPropertyValues(
            "identity.retention.cron=" + YEARLY_CRON,
            "identity.retention.anonymize-after=30d",
            "identity.retention.inactivity-period=730d",
            "identity.retention.deletion-notice=14d"
    );

    @Test
    void bindsTheProperties() {
        configured.withPropertyValues("identity.retention.enabled=true").run(context -> {
            assertThat(context).hasNotFailed();
            UserRetentionProperties properties = context.getBean(UserRetentionProperties.class);
            assertThat(properties.enabled()).isTrue();
            assertThat(properties.cron()).isEqualTo(YEARLY_CRON);
            assertThat(properties.anonymizeAfter()).isEqualTo(Duration.ofDays(30));
            assertThat(properties.inactivityPeriod()).isEqualTo(Duration.ofDays(730));
            assertThat(properties.deletionNotice()).isEqualTo(Duration.ofDays(14));
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
            assertThat(context).hasNotFailed().hasSingleBean(UserRetentionJob.class);
            assertThat(context.getBean(UserRetentionProperties.class).enabled()).isTrue();
        });
    }

    @Test
    void disabledRetentionRemovesTheJob() {
        configured.withPropertyValues("identity.retention.enabled=false").run(context -> {
            assertThat(context).hasNotFailed().doesNotHaveBean(UserRetentionJob.class);
            assertThat(context.getBean(UserRetentionProperties.class).enabled()).isFalse();
        });
    }

    @Test
    void blankCronFailsStartup() {
        runner.withPropertyValues(
                "identity.retention.enabled=false",
                "identity.retention.cron= ",
                "identity.retention.anonymize-after=30d",
                "identity.retention.inactivity-period=730d",
                "identity.retention.deletion-notice=30d"
        ).run(context -> assertThat(context).getFailure()
                .hasStackTraceContaining("NotBlank.identity.retention.cron"));
    }

    @Test
    void missingAnonymizeAfterFailsStartup() {
        runner.withPropertyValues(
                "identity.retention.enabled=false",
                "identity.retention.cron=" + YEARLY_CRON,
                "identity.retention.inactivity-period=730d",
                "identity.retention.deletion-notice=30d"
        ).run(context -> assertThat(context).getFailure()
                .hasStackTraceContaining("NotNull.identity.retention.anonymizeAfter"));
    }

    @Test
    void missingInactivityPeriodFailsStartup() {
        runner.withPropertyValues(
                "identity.retention.enabled=false",
                "identity.retention.cron=" + YEARLY_CRON,
                "identity.retention.anonymize-after=30d",
                "identity.retention.deletion-notice=30d"
        ).run(context -> assertThat(context).getFailure()
                .hasStackTraceContaining("NotNull.identity.retention.inactivityPeriod"));
    }

    @Test
    void missingDeletionNoticeFailsStartup() {
        runner.withPropertyValues(
                "identity.retention.enabled=false",
                "identity.retention.cron=" + YEARLY_CRON,
                "identity.retention.anonymize-after=30d",
                "identity.retention.inactivity-period=730d"
        ).run(context -> assertThat(context).getFailure()
                .hasStackTraceContaining("NotNull.identity.retention.deletionNotice"));
    }

    @Test
    void applicationYamlShipsTheDocumentedDefaults() {
        runner.withInitializer(context -> context.getEnvironment().getPropertySources()
                        .addLast(mainApplicationYaml()))
                .withPropertyValues("identity.retention.enabled=false")
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    UserRetentionProperties properties = context.getBean(UserRetentionProperties.class);
                    assertThat(properties.cron()).isEqualTo("0 0 4 * * *");
                    assertThat(properties.anonymizeAfter()).isEqualTo(Duration.ofDays(30));
                    assertThat(properties.inactivityPeriod()).isEqualTo(Duration.ofDays(730));
                    assertThat(properties.deletionNotice()).isEqualTo(Duration.ofDays(30));
                });
    }

    @Test
    void applicationYamlEnablesTheJobByDefault() {
        runner.withInitializer(context -> context.getEnvironment().getPropertySources()
                        .addLast(mainApplicationYaml()))
                .run(context -> {
                    assertThat(context).hasNotFailed().hasSingleBean(UserRetentionJob.class);
                    assertThat(context.getBean(UserRetentionProperties.class).enabled()).isTrue();
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
