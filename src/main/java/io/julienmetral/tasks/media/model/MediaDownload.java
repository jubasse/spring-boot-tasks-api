package io.julienmetral.tasks.media.model;

import java.net.URL;
import java.time.Instant;

/** A presigned URL that serves the file directly from object storage until {@code expiresAt}. */
public record MediaDownload(
        URL url,
        Instant expiresAt
) {
}
