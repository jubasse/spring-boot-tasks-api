package io.julienmetral.tasks.identity.mail;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

import java.time.Duration;

/**
 * @param ttl       how long a verification link stays valid
 * @param verifyUrl page that receives {@code ?token=...} and calls {@code POST /api/v1/auth/verify-email}
 */
@Validated
@ConfigurationProperties(prefix = "identity.email-verification")
public record EmailVerificationProperties(
        @NotNull Duration ttl,
        @NotBlank String verifyUrl
) {
}
