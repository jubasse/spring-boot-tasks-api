package io.julienmetral.tasks.identity.messaging;

import java.util.UUID;

/** Written to the outbox in the upload's transaction; the worker receives it once that commits. */
public record AvatarUploaded(
        UUID userId,
        UUID uploadId
) {
}
