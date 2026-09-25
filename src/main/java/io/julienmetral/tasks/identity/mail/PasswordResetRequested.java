package io.julienmetral.tasks.identity.mail;

import java.time.Instant;

/** Published when a reset token is issued; the email is sent once the transaction commits. */
public record PasswordResetRequested(
        String email,
        String displayName,
        String token,
        Instant expiresAt
) {
}
