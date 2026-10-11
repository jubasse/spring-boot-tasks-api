package io.julienmetral.tasks.config;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import org.hibernate.validator.constraints.time.DurationMin;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.validation.annotation.Validated;

import java.time.Duration;

/**
 * @param enabled   run the scheduled purge of expired refresh, email verification and password reset tokens
 * @param cron      when it runs (Spring cron, six fields), in the server time zone
 * @param retention how long a token is kept after it expired; a negative value would delete tokens still valid
 */
@Validated
@ConfigurationProperties(prefix = "identity.token-purge")
public record ExpiredTokenPurgeProperties(
        @DefaultValue("true") boolean enabled,
        @NotBlank String cron,
        @NotNull @DurationMin(seconds = 0) Duration retention
) {
}
