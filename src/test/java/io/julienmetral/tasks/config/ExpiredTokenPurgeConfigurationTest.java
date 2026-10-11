package io.julienmetral.tasks.config;

import io.julienmetral.tasks.identity.services.ExpiredTokenPurgeJob;
import io.julienmetral.tasks.identity.services.ExpiredTokenPurgeService;
import org.junit.jupiter.api.Test;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.PropertySource;
import org.springframework.core.io.ClassPathResource;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.ScheduledAnnotationBeanPostProcessor;
import org.springframework.scheduling.config.CronTask;
import org.springframework.scheduling.config.ScheduledTask;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.time.Duration;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

class ExpiredTokenPurgeConfigurationTest {

    // A cron that never fires while a test context is open
    private static final String YEARLY_CRON = "0 0 0 1 1 *";

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withUserConfiguration(ExpiredTokenPurgeConfiguration.class, Scheduling.class, ExpiredTokenPurgeJob.class)
            .withBean(ExpiredTokenPurgeService.class, () -> mock(ExpiredTokenPurgeService.class));

    private final ApplicationContextRunner configured = runner.withPropertyValues(
            "identity.token-purge.cron=" + YEARLY_CRON,
            "identity.token-purge.retention=3d"
    );

    @Test
    void bindsTheProperties() {
        configured.withPropertyValues("identity.token-purge.enabled=true").run(context -> {
            assertThat(context).hasNotFailed();
            ExpiredTokenPurgeProperties properties = context.getBean(ExpiredTokenPurgeProperties.class);
            assertThat(properties.enabled()).isTrue();
            assertThat(properties.cron()).isEqualTo(YEARLY_CRON);
            assertThat(properties.retention()).isEqualTo(Duration.ofDays(3));
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
            assertThat(context).hasNotFailed().hasSingleBean(ExpiredTokenPurgeJob.class);
            assertThat(context.getBean(ExpiredTokenPurgeProperties.class).enabled()).isTrue();
        });
    }

    @Test
    void disabledPurgeRemovesTheJob() {
        configured.withPropertyValues("identity.token-purge.enabled=false").run(context -> {
            assertThat(context).hasNotFailed().doesNotHaveBean(ExpiredTokenPurgeJob.class);
            assertThat(context.getBean(ExpiredTokenPurgeProperties.class).enabled()).isFalse();
        });
    }

    @Test
    void zeroRetentionIsAccepted() {
        configured.withPropertyValues("identity.token-purge.retention=0s")
                .run(context -> assertThat(context).hasNotFailed());
    }

    @Test
    void negativeRetentionFailsStartup() {
        configured.withPropertyValues("identity.token-purge.retention=-1s")
                .run(context -> assertThat(context).getFailure()
                        .hasStackTraceContaining("DurationMin.identity.token-purge.retention"));
    }

    @Test
    void missingRetentionFailsStartup() {
        runner.withPropertyValues(
                "identity.token-purge.enabled=false",
                "identity.token-purge.cron=" + YEARLY_CRON
        ).run(context -> assertThat(context).getFailure()
                .hasStackTraceContaining("NotNull.identity.token-purge.retention"));
    }

    @Test
    void blankCronFailsStartup() {
        runner.withPropertyValues(
                "identity.token-purge.enabled=false",
                "identity.token-purge.cron= ",
                "identity.token-purge.retention=7d"
        ).run(context -> assertThat(context).getFailure()
                .hasStackTraceContaining("NotBlank.identity.token-purge.cron"));
    }

    @Test
    void applicationYamlShipsTheDocumentedDefaults() {
        runner.withInitializer(context -> context.getEnvironment().getPropertySources()
                        .addLast(mainApplicationYaml()))
                .run(context -> {
                    assertThat(context).hasNotFailed().hasSingleBean(ExpiredTokenPurgeJob.class);
                    ExpiredTokenPurgeProperties properties = context.getBean(ExpiredTokenPurgeProperties.class);
                    assertThat(properties.enabled()).isTrue();
                    assertThat(properties.cron()).isEqualTo("0 15 4 * * *");
                    assertThat(properties.retention()).isEqualTo(Duration.ofDays(7));
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

    // SchedulingConfiguration would also require the properties of every other job it enables
    @Configuration
    @EnableScheduling
    static class Scheduling {
    }
}
