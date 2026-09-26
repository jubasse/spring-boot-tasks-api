package io.julienmetral.tasks.identity.messaging;

import java.util.UUID;

/** Published in the upload's transaction, then queued for the worker once it commits. */
public record AvatarUploaded(
        UUID userId,
        UUID uploadId
) {
}
