package io.julienmetral.tasks.identity.mail;

import java.time.Instant;
import java.util.UUID;

/** Published when a verification token is issued; the email is sent once the transaction commits. */
public record EmailVerificationRequested(
        UUID userId,
        String email,
        String displayName,
        String token,
        Instant expiresAt
) {
}
