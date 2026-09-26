package io.julienmetral.tasks.config;

import io.julienmetral.tasks.config.RateLimitProperties.Limit;
import io.julienmetral.tasks.ratelimit.repositories.RateLimitQueries;
import io.julienmetral.tasks.ratelimit.services.RateLimitCleanupJob;
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
import java.time.Clock;
import java.time.Duration;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

class RateLimitConfigurationTest {

    // A cron that never fires while a test context is open
    private static final String YEARLY_CRON = "0 0 0 1 1 *";

    // Each limit differs from the others, so a limit bound to the wrong key fails the test
    private static final Map<String, String> VALID = Map.ofEntries(
            Map.entry("rate-limit.purge-cron", YEARLY_CRON),
            Map.entry("rate-limit.login-per-ip.requests", "1"),
            Map.entry("rate-limit.login-per-ip.window", "1m"),
            Map.entry("rate-limit.login-per-email.requests", "2"),
            Map.entry("rate-limit.login-per-email.window", "2m"),
            Map.entry("rate-limit.sign-up-per-ip.requests", "3"),
            Map.entry("rate-limit.sign-up-per-ip.window", "3m"),
            Map.entry("rate-limit.password-reset-per-ip.requests", "4"),
            Map.entry("rate-limit.password-reset-per-ip.window", "4m"),
            Map.entry("rate-limit.password-reset-per-email.requests", "5"),
            Map.entry("rate-limit.password-reset-per-email.window", "5m"),
            Map.entry("rate-limit.verification-resend-per-user.requests", "6"),
            Map.entry("rate-limit.verification-resend-per-user.window", "6m")
    );

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withUserConfiguration(RateLimitConfiguration.class);

    private final ApplicationContextRunner configured = runner.withPropertyValues(validPropertiesWithout(null));

    @Test
    void bindsEachNestedLimit() {
        configured.run(context -> {
            assertThat(context).hasNotFailed();
            RateLimitProperties properties = context.getBean(RateLimitProperties.class);
            assertThat(properties.purgeCron()).isEqualTo(YEARLY_CRON);
            assertThat(properties.loginPerIp()).isEqualTo(new Limit(1, Duration.ofMinutes(1)));
            assertThat(properties.loginPerEmail()).isEqualTo(new Limit(2, Duration.ofMinutes(2)));
            assertThat(properties.signUpPerIp()).isEqualTo(new Limit(3, Duration.ofMinutes(3)));
            assertThat(properties.passwordResetPerIp()).isEqualTo(new Limit(4, Duration.ofMinutes(4)));
            assertThat(properties.passwordResetPerEmail()).isEqualTo(new Limit(5, Duration.ofMinutes(5)));
            assertThat(properties.verificationResendPerUser()).isEqualTo(new Limit(6, Duration.ofMinutes(6)));
        });
    }

    @Test
    void missingEnabledTurnsTheLimitsOn() {
        configured.run(context -> assertThat(context.getBean(RateLimitProperties.class).enabled()).isTrue());
    }

    @Test
    void limitsCanBeTurnedOff() {
        configured.withPropertyValues("rate-limit.enabled=false")
                .run(context -> assertThat(context.getBean(RateLimitProperties.class).enabled()).isFalse());
    }

    @Test
    void applicationYamlShipsTheDocumentedDefaults() {
        runner.withInitializer(context -> context.getEnvironment().getPropertySources()
                        .addLast(mainApplicationYaml()))
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    RateLimitProperties properties = context.getBean(RateLimitProperties.class);
                    assertThat(properties.enabled()).isTrue();
                    assertThat(properties.purgeCron()).isEqualTo("0 20 * * * *");
                    assertThat(properties.loginPerIp()).isEqualTo(new Limit(30, Duration.ofMinutes(1)));
                    assertThat(properties.loginPerEmail()).isEqualTo(new Limit(10, Duration.ofMinutes(15)));
                    assertThat(properties.signUpPerIp()).isEqualTo(new Limit(20, Duration.ofHours(1)));
                    assertThat(properties.passwordResetPerIp()).isEqualTo(new Limit(20, Duration.ofHours(1)));
                    assertThat(properties.passwordResetPerEmail()).isEqualTo(new Limit(3, Duration.ofHours(1)));
                    assertThat(properties.verificationResendPerUser()).isEqualTo(new Limit(3, Duration.ofHours(1)));
                    assertThat(context.getEnvironment().getProperty("server.forward-headers-strategy"))
                            .isEqualTo("none");
                });
    }

    @Test
    void schedulesTheCleanupOnThePurgeCron() {
        configured
                .withUserConfiguration(SchedulingConfiguration.class, RateLimitCleanupJob.class)
                .withBean(RateLimitQueries.class, () -> mock(RateLimitQueries.class))
                .withBean(Clock.class, Clock::systemUTC)
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
                )
                .run(context -> {
                    assertThat(context).hasNotFailed();
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
    void blankPurgeCronFailsStartup() {
        configured.withPropertyValues("rate-limit.purge-cron= ")
                .run(context -> assertThat(context).getFailure()
                        .hasStackTraceContaining("NotBlank.rate-limit.purgeCron"));
    }

    @Test
    void missingLimitFailsStartup() {
        runner.withPropertyValues(validPropertiesWithout("rate-limit.sign-up-per-ip."))
                .run(context -> assertThat(context).getFailure()
                        .hasStackTraceContaining("NotNull.rate-limit.signUpPerIp"));
    }

    @Test
    void zeroRequestsFailsStartup() {
        configured.withPropertyValues("rate-limit.login-per-email.requests=0")
                .run(context -> assertThat(context).getFailure()
                        .hasStackTraceContaining("Positive.rate-limit.loginPerEmail.requests"));
    }

    @Test
    void limitWithoutRequestsFailsStartup() {
        runner.withPropertyValues(validPropertiesWithout("rate-limit.password-reset-per-email.requests"))
                .run(context -> assertThat(context).getFailure()
                        .hasStackTraceContaining("Positive.rate-limit.passwordResetPerEmail.requests"));
    }

    @Test
    void limitWithoutWindowFailsStartup() {
        runner.withPropertyValues(validPropertiesWithout("rate-limit.verification-resend-per-user.window"))
                .run(context -> assertThat(context).getFailure()
                        .hasStackTraceContaining("NotNull.rate-limit.verificationResendPerUser.window"));
    }
    @Test
    void zeroWindowFailsStartup() {
        configured.withPropertyValues("rate-limit.login-per-ip.window=0s")
                .run(context -> assertThat(context).hasFailed());
    }

    /** Every valid property, except those whose key starts with {@code keyPrefix} when it is not null. */
    private static String[] validPropertiesWithout(String keyPrefix) {
        return VALID.entrySet().stream()
                .filter(entry -> keyPrefix == null || !entry.getKey().startsWith(keyPrefix))
                .map(entry -> entry.getKey() + "=" + entry.getValue())
                .toArray(String[]::new);
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
