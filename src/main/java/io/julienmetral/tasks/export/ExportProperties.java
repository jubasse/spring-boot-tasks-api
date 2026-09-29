package io.julienmetral.tasks.export;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import org.hibernate.validator.constraints.time.DurationMax;
import org.hibernate.validator.constraints.time.DurationMin;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.validation.annotation.Validated;

import java.time.Duration;

/**
 * @param downloadPageUrl  page the ready email links to, with {@code ?id=...}; it calls
 *                         {@code GET /api/v1/exports/{id}} for the download link
 * @param retention        how long a produced file stays downloadable; it holds personal data, so keep it short
 * @param historyRetention how long an expired or failed export stays listed, and the Spring Batch history with it
 * @param chunkSize        rows read and written per transaction
 * @param lease            how long a run may last before it counts as interrupted; above the longest export
 * @param maxAttempts      runs of one export, the first included, before it fails for good
 * @param purgeEnabled     whether this instance deletes expired files and old history
 * @param purgeCron        when they are deleted (Spring cron, six fields)
 * @param recoveryEnabled  whether this instance resumes the exports of an instance that stopped
 * @param recoveryInterval how often interrupted exports are looked for
 */
@Validated
@ConfigurationProperties(prefix = "exports")
public record ExportProperties(
        @NotBlank String downloadPageUrl,
        @DefaultValue("7d") @NotNull @DurationMin(hours = 1) @DurationMax(days = 30) Duration retention,
        @DefaultValue("30d") @NotNull Duration historyRetention,
        @DefaultValue("500") @Min(1) int chunkSize,
        @DefaultValue("15m") @NotNull @DurationMin(minutes = 1) Duration lease,
        @DefaultValue("3") @Min(1) int maxAttempts,
        @DefaultValue("true") boolean purgeEnabled,
        @DefaultValue("0 30 4 * * *") String purgeCron,
        @DefaultValue("true") boolean recoveryEnabled,
        @DefaultValue("5m") @NotNull @DurationMin(minutes = 1) Duration recoveryInterval
) {
}
