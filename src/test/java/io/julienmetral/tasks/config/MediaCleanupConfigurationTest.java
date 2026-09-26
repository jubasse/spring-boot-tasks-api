package io.julienmetral.tasks.config;

import io.julienmetral.tasks.media.services.MediaCleanupJob;
import io.julienmetral.tasks.media.services.MediaCleanupService;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.scheduling.annotation.ScheduledAnnotationBeanPostProcessor;

import java.time.Clock;
import java.time.Duration;
import java.time.ZoneOffset;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

class MediaCleanupConfigurationTest {

    // A cron that never fires while a test context is open
    private static final String YEARLY_CRON = "0 0 0 1 1 *";

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withUserConfiguration(SchedulingConfiguration.class, ClockConfiguration.class, MediaCleanupJob.class)
            .withBean(MediaCleanupService.class, () -> mock(MediaCleanupService.class))
            // SchedulingConfiguration also validates the reminder and user retention properties
            .withPropertyValues(
                    "task.reminders.cron=" + YEARLY_CRON,
                    "task.reminders.due-soon-lead-time=24h",
                    "task.reminders.overdue-lookback=7d",
                    "identity.retention.cron=" + YEARLY_CRON,
                    "identity.retention.anonymize-after=30d",
                    "identity.retention.inactivity-period=730d",
                    "identity.retention.deletion-notice=30d"
            );

    private final ApplicationContextRunner configured = runner.withPropertyValues(
            "media.cleanup.cron=" + YEARLY_CRON,
            "media.cleanup.retention=30d",
            "media.cleanup.orphan-grace-period=1d"
    );

    @Test
    void bindsTheProperties() {
        configured.withPropertyValues("media.cleanup.enabled=true").run(context -> {
            assertThat(context).hasNotFailed();
            MediaCleanupProperties properties = context.getBean(MediaCleanupProperties.class);
            assertThat(properties.enabled()).isTrue();
            assertThat(properties.cron()).isEqualTo(YEARLY_CRON);
            assertThat(properties.retention()).isEqualTo(Duration.ofDays(30));
            assertThat(properties.orphanGracePeriod()).isEqualTo(Duration.ofDays(1));
        });
    }

    @Test
    void enablesScheduling() {
        configured.run(context -> assertThat(context).hasSingleBean(ScheduledAnnotationBeanPostProcessor.class));
    }

    @Test
    void missingEnabledKeepsTheJob() {
        configured.run(context -> {
            assertThat(context).hasNotFailed().hasSingleBean(MediaCleanupJob.class);
            assertThat(context.getBean(MediaCleanupProperties.class).enabled()).isTrue();
        });
    }

    @Test
    void disabledCleanupRemovesTheJob() {
        configured.withPropertyValues("media.cleanup.enabled=false").run(context -> {
            assertThat(context).hasNotFailed().doesNotHaveBean(MediaCleanupJob.class);
            assertThat(context.getBean(MediaCleanupProperties.class).enabled()).isFalse();
        });
    }

    @Test
    void blankCronFailsStartup() {
        runner.withPropertyValues(
                "media.cleanup.enabled=false",
                "media.cleanup.cron= ",
                "media.cleanup.retention=30d",
                "media.cleanup.orphan-grace-period=1d"
        ).run(context -> assertThat(context).getFailure()
                .hasStackTraceContaining("NotBlank.media.cleanup.cron"));
    }

    @Test
    void missingRetentionFailsStartup() {
        runner.withPropertyValues(
                "media.cleanup.enabled=false",
                "media.cleanup.cron=" + YEARLY_CRON,
                "media.cleanup.orphan-grace-period=1d"
        ).run(context -> assertThat(context).getFailure()
                .hasStackTraceContaining("NotNull.media.cleanup.retention"));
    }

    @Test
    void missingOrphanGracePeriodFailsStartup() {
        runner.withPropertyValues(
                "media.cleanup.enabled=false",
                "media.cleanup.cron=" + YEARLY_CRON,
                "media.cleanup.retention=30d"
        ).run(context -> assertThat(context).getFailure()
                .hasStackTraceContaining("NotNull.media.cleanup.orphanGracePeriod"));
    }

    @Test
    void providesAUtcClock() {
        configured.run(context -> {
            Clock clock = context.getBean(Clock.class);
            assertThat(clock.getZone()).isEqualTo(ZoneOffset.UTC);
            assertThat(clock).isEqualTo(Clock.systemUTC());
        });
    }
}
