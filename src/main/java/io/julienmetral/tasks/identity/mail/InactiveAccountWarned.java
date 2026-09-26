package io.julienmetral.tasks.identity.mail;

import java.time.Instant;

public record InactiveAccountWarned(
        String email,
        String displayName,
        Instant deletionAt
) {
}
