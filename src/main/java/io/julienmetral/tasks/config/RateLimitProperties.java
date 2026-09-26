package io.julienmetral.tasks.config;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import org.hibernate.validator.constraints.time.DurationMax;
import org.hibernate.validator.constraints.time.DurationMin;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.validation.annotation.Validated;

import java.time.Duration;

/**
 * @param enabled                   count requests and refuse those over a limit
 * @param purgeCron                 when counters of windows older than a day are deleted (Spring cron, six fields)
 * @param loginPerIp                login attempts from one client address
 * @param loginPerEmail             login attempts for one email, from any address
 * @param signUpPerIp               sign-ups from one client address
 * @param passwordResetPerIp        password reset requests and confirmations from one client address
 * @param passwordResetPerEmail     password reset requests for one email, which each send an email
 * @param verificationResendPerUser verification emails one user can ask to be sent again
 */
@Validated
@ConfigurationProperties(prefix = "rate-limit")
public record RateLimitProperties(
        @DefaultValue("true") boolean enabled,
        @NotBlank String purgeCron,
        @Valid @NotNull Limit loginPerIp,
        @Valid @NotNull Limit loginPerEmail,
        @Valid @NotNull Limit signUpPerIp,
        @Valid @NotNull Limit passwordResetPerIp,
        @Valid @NotNull Limit passwordResetPerEmail,
        @Valid @NotNull Limit verificationResendPerUser
) {

    /**
     * @param requests how many requests the window accepts
     * @param window   length of the window, from 1 second to 1 day; counting starts again at the next one. Counters
     *                 are kept one day, so a longer window would be purged while still counting
     */
    public record Limit(
            @Positive int requests,
            @NotNull @DurationMin(seconds = 1) @DurationMax(days = 1) Duration window
    ) {
    }
}
