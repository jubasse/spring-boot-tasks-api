package io.julienmetral.tasks.media.model;

/** An image re-encoded by the application, so it carries no metadata from the original file. */
public record ProcessedImage(
        byte[] content,
        String contentType,
        String extension
) {
}
