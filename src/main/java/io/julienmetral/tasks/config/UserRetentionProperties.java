package io.julienmetral.tasks.config;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.validation.annotation.Validated;

import java.time.Duration;

/**
 * @param enabled          run the scheduled retention job
 * @param cron             when it runs (Spring cron, six fields), in the server time zone
 * @param anonymizeAfter   how long a deleted user keeps their personal data before it is erased
 * @param inactivityPeriod how long without login or token refresh before an account is warned
 * @param deletionNotice   how long after the warning an account that stayed inactive is deleted
 */
@Validated
@ConfigurationProperties(prefix = "identity.retention")
public record UserRetentionProperties(
        @DefaultValue("true") boolean enabled,
        @NotBlank String cron,
        @NotNull Duration anonymizeAfter,
        @NotNull Duration inactivityPeriod,
        @NotNull Duration deletionNotice
) {
}
