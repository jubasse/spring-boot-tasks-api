package io.julienmetral.tasks.export;

import io.julienmetral.tasks.export.repositories.BatchMetadataQueries;
import io.julienmetral.tasks.export.services.DataExportPurgeJob;
import io.julienmetral.tasks.export.services.DataExportRecoveryJob;
import io.julienmetral.tasks.export.services.DataExportService;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import java.time.Clock;
import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

class ExportConfigurationTest {

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withUserConfiguration(ExportConfiguration.class, DataExportPurgeJob.class, DataExportRecoveryJob.class)
            .withBean(DataExportService.class, () -> mock(DataExportService.class))
            .withBean(BatchMetadataQueries.class, () -> mock(BatchMetadataQueries.class))
            .withBean(Clock.class, Clock::systemUTC);

    private final ApplicationContextRunner configured =
            runner.withPropertyValues("exports.download-page-url=https://app.example/exports");

    @Test
    void bindsTheDefaultsOfEverySettingButTheDownloadPage() {
        configured.run(context -> {
            assertThat(context).hasNotFailed();
            ExportProperties properties = context.getBean(ExportProperties.class);
            assertThat(properties.downloadPageUrl()).isEqualTo("https://app.example/exports");
            assertThat(properties.retention()).isEqualTo(Duration.ofDays(7));
            assertThat(properties.historyRetention()).isEqualTo(Duration.ofDays(30));
            assertThat(properties.chunkSize()).isEqualTo(500);
            assertThat(properties.lease()).isEqualTo(Duration.ofMinutes(15));
            assertThat(properties.maxAttempts()).isEqualTo(3);
            assertThat(properties.purgeEnabled()).isTrue();
            assertThat(properties.purgeCron()).isEqualTo("0 30 4 * * *");
            assertThat(properties.recoveryEnabled()).isTrue();
            assertThat(properties.recoveryInterval()).isEqualTo(Duration.ofMinutes(5));
        });
    }

    @Test
    void missingSwitchesKeepThePurgeAndTheRecovery() {
        configured.run(context -> assertThat(context)
                .hasSingleBean(DataExportPurgeJob.class)
                .hasSingleBean(DataExportRecoveryJob.class));
    }

    @Test
    void disabledPurgeRemovesOnlyThePurgeJob() {
        configured.withPropertyValues("exports.purge-enabled=false").run(context -> assertThat(context)
                .doesNotHaveBean(DataExportPurgeJob.class)
                .hasSingleBean(DataExportRecoveryJob.class));
    }

    @Test
    void disabledRecoveryRemovesOnlyTheRecoveryJob() {
        configured.withPropertyValues("exports.recovery-enabled=false").run(context -> assertThat(context)
                .hasSingleBean(DataExportPurgeJob.class)
                .doesNotHaveBean(DataExportRecoveryJob.class));
    }

    @Test
    void missingDownloadPageFailsStartup() {
        runner.run(context -> assertThat(context).getFailure()
                .hasStackTraceContaining("NotBlank.exports.downloadPageUrl"));
    }

    @Test
    void retentionShorterThanAnHourFailsStartup() {
        configured.withPropertyValues("exports.retention=59m").run(context -> assertThat(context).getFailure()
                .hasStackTraceContaining("DurationMin.exports.retention"));
    }

    @Test
    void retentionLongerThanThirtyDaysFailsStartup() {
        configured.withPropertyValues("exports.retention=31d").run(context -> assertThat(context).getFailure()
                .hasStackTraceContaining("DurationMax.exports.retention"));
    }

    @Test
    void leaseShorterThanAMinuteFailsStartup() {
        configured.withPropertyValues("exports.lease=30s").run(context -> assertThat(context).getFailure()
                .hasStackTraceContaining("DurationMin.exports.lease"));
    }

    @Test
    void noAttemptFailsStartup() {
        configured.withPropertyValues("exports.max-attempts=0").run(context -> assertThat(context).getFailure()
                .hasStackTraceContaining("Min.exports.maxAttempts"));
    }

    @Test
    void emptyChunkFailsStartup() {
        configured.withPropertyValues("exports.chunk-size=0").run(context -> assertThat(context).getFailure()
                .hasStackTraceContaining("Min.exports.chunkSize"));
    }
}
