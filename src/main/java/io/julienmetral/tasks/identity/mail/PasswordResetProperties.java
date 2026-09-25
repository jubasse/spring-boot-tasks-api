package io.julienmetral.tasks.identity.mail;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

import java.time.Duration;

/**
 * @param ttl      how long a reset link stays valid
 * @param resetUrl page that receives {@code ?token=...}, asks for a new password and calls
 *                 {@code POST /api/v1/auth/password-reset/confirm}
 */
@Validated
@ConfigurationProperties(prefix = "identity.password-reset")
public record PasswordResetProperties(
        @NotNull Duration ttl,
        @NotBlank String resetUrl
) {
}
