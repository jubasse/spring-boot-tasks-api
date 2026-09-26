package io.julienmetral.tasks.config;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.validation.annotation.Validated;

import java.time.Duration;

/**
 * @param enabled           run the scheduled media cleanup
 * @param cron              when it runs (Spring cron, six fields), in the server time zone
 * @param retention         how long files of soft-deleted tasks and users are kept before being purged
 * @param orphanGracePeriod minimum age of an unreferenced media row or an object without a row before it is purged;
 *                          protects uploads still in progress
 */
@Validated
@ConfigurationProperties(prefix = "media.cleanup")
public record MediaCleanupProperties(
        @DefaultValue("true") boolean enabled,
        @NotBlank String cron,
        @NotNull Duration retention,
        @NotNull Duration orphanGracePeriod
) {
}
