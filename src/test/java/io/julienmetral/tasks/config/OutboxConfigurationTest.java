package io.julienmetral.tasks.config;

import io.julienmetral.tasks.messaging.services.OutboxRelay;
import io.julienmetral.tasks.messaging.services.OutboxRelayJob;
import org.junit.jupiter.api.Test;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.core.env.PropertySource;
import org.springframework.core.io.ClassPathResource;
import org.springframework.scheduling.annotation.ScheduledAnnotationBeanPostProcessor;
import org.springframework.scheduling.config.CronTask;
import org.springframework.scheduling.config.FixedDelayTask;
import org.springframework.scheduling.config.ScheduledTask;
import org.springframework.scheduling.config.Task;
import tools.jackson.databind.json.JsonMapper;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.time.Duration;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

class OutboxConfigurationTest {

    // A cron that never fires while a test context is open
    private static final String YEARLY_CRON = "0 0 0 1 1 *";

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withUserConfiguration(MessagingConfiguration.class, SchedulingConfiguration.class, OutboxRelayJob.class)
            .withBean(JsonMapper.class, () -> JsonMapper.builder().build())
            .withBean(OutboxRelay.class, () -> mock(OutboxRelay.class))
            // SchedulingConfiguration also validates the media cleanup, task reminder and user retention properties
            .withPropertyValues(
                    "media.cleanup.enabled=false",
                    "media.cleanup.cron=" + YEARLY_CRON,
                    "media.cleanup.retention=30d",
                    "media.cleanup.orphan-grace-period=1d",
                    "task.reminders.enabled=false",
                    "task.reminders.cron=" + YEARLY_CRON,
                    "task.reminders.due-soon-lead-time=24h",
                    "task.reminders.overdue-lookback=7d",
                    "identity.retention.enabled=false",
                    "identity.retention.cron=" + YEARLY_CRON,
                    "identity.retention.anonymize-after=30d",
                    "identity.retention.inactivity-period=730d",
                    "identity.retention.deletion-notice=30d"
            );

    // The poller runs once when the context starts, then not again before an hour
    private final ApplicationContextRunner configured = runner.withPropertyValues(
            "messaging.outbox.poll-interval=1h",
            "messaging.outbox.batch-size=50",
            "messaging.outbox.max-retry-delay=15m",
            "messaging.outbox.confirm-timeout=3s",
            "messaging.outbox.retention=2d",
            "messaging.outbox.purge-cron=" + YEARLY_CRON
    );

    @Test
    void bindsTheProperties() {
        configured.run(context -> {
            assertThat(context).hasNotFailed();
            OutboxProperties properties = context.getBean(OutboxProperties.class);
            assertThat(properties.pollInterval()).isEqualTo(Duration.ofHours(1));
            assertThat(properties.batchSize()).isEqualTo(50);
            assertThat(properties.maxRetryDelay()).isEqualTo(Duration.ofMinutes(15));
            assertThat(properties.confirmTimeout()).isEqualTo(Duration.ofSeconds(3));
            assertThat(properties.retention()).isEqualTo(Duration.ofDays(2));
            assertThat(properties.purgeCron()).isEqualTo(YEARLY_CRON);
        });
    }

    @Test
    void schedulesThePollerEveryPollIntervalAndThePurgeOnItsCron() {
        configured.run(context -> {
            List<Task> tasks = context.getBean(ScheduledAnnotationBeanPostProcessor.class)
                    .getScheduledTasks().stream()
                    .map(ScheduledTask::getTask)
                    .toList();

            assertThat(tasks)
                    .filteredOn(FixedDelayTask.class::isInstance)
                    .singleElement()
                    .satisfies(task -> assertThat(((FixedDelayTask) task).getIntervalDuration())
                            .isEqualTo(Duration.ofHours(1)));
            assertThat(tasks)
                    .filteredOn(CronTask.class::isInstance)
                    .extracting(task -> ((CronTask) task).getExpression())
                    .containsExactly(YEARLY_CRON);
        });
    }

    @Test
    void missingPollIntervalFailsStartup() {
        runner.withPropertyValues(
                "messaging.outbox.batch-size=50",
                "messaging.outbox.max-retry-delay=15m",
                "messaging.outbox.confirm-timeout=3s",
                "messaging.outbox.retention=2d",
                "messaging.outbox.purge-cron=" + YEARLY_CRON
        ).run(context -> assertThat(context).getFailure()
                .hasStackTraceContaining("NotNull.messaging.outbox.pollInterval"));
    }

    @Test
    void zeroBatchSizeFailsStartup() {
        configured.withPropertyValues("messaging.outbox.batch-size=0")
                .run(context -> assertThat(context).getFailure()
                        .hasStackTraceContaining("Positive.messaging.outbox.batchSize"));
    }

    @Test
    void missingMaxRetryDelayFailsStartup() {
        runner.withPropertyValues(
                "messaging.outbox.poll-interval=1h",
                "messaging.outbox.batch-size=50",
                "messaging.outbox.confirm-timeout=3s",
                "messaging.outbox.retention=2d",
                "messaging.outbox.purge-cron=" + YEARLY_CRON
        ).run(context -> assertThat(context).getFailure()
                .hasStackTraceContaining("NotNull.messaging.outbox.maxRetryDelay"));
    }

    @Test
    void missingConfirmTimeoutFailsStartup() {
        runner.withPropertyValues(
                "messaging.outbox.poll-interval=1h",
                "messaging.outbox.batch-size=50",
                "messaging.outbox.max-retry-delay=15m",
                "messaging.outbox.retention=2d",
                "messaging.outbox.purge-cron=" + YEARLY_CRON
        ).run(context -> assertThat(context).getFailure()
                .hasStackTraceContaining("NotNull.messaging.outbox.confirmTimeout"));
    }

    @Test
    void missingRetentionFailsStartup() {
        runner.withPropertyValues(
                "messaging.outbox.poll-interval=1h",
                "messaging.outbox.batch-size=50",
                "messaging.outbox.max-retry-delay=15m",
                "messaging.outbox.confirm-timeout=3s",
                "messaging.outbox.purge-cron=" + YEARLY_CRON
        ).run(context -> assertThat(context).getFailure()
                .hasStackTraceContaining("NotNull.messaging.outbox.retention"));
    }

    @Test
    void blankPurgeCronFailsStartup() {
        configured.withPropertyValues("messaging.outbox.purge-cron= ")
                .run(context -> assertThat(context).getFailure()
                        .hasStackTraceContaining("NotBlank.messaging.outbox.purgeCron"));
    }

    @Test
    void applicationYamlShipsTheDocumentedDefaults() {
        runner.withInitializer(context -> context.getEnvironment().getPropertySources()
                        .addLast(mainApplicationYaml()))
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    OutboxProperties properties = context.getBean(OutboxProperties.class);
                    assertThat(properties.pollInterval()).isEqualTo(Duration.ofSeconds(5));
                    assertThat(properties.batchSize()).isEqualTo(100);
                    assertThat(properties.maxRetryDelay()).isEqualTo(Duration.ofMinutes(10));
                    assertThat(properties.confirmTimeout()).isEqualTo(Duration.ofSeconds(5));
                    assertThat(properties.retention()).isEqualTo(Duration.ofDays(7));
                    assertThat(properties.purgeCron()).isEqualTo("0 0 * * * *");
                    assertThat(context.getEnvironment().getProperty("spring.rabbitmq.publisher-confirm-type"))
                            .isEqualTo("correlated");
                    assertThat(context.getEnvironment().getProperty("spring.rabbitmq.publisher-returns"))
                            .isEqualTo("true");
                    assertThat(context.getEnvironment().getProperty("spring.rabbitmq.template.mandatory"))
                            .isEqualTo("true");
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
