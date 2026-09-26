package io.julienmetral.tasks.media.model;

import java.util.Set;

/** What a file is uploaded for, which decides the accepted content types (detected from the bytes). */
public enum MediaUsage {

    AVATAR(Set.of(
            "image/jpeg",
            "image/png",
            "image/webp"
    )),

    TASK_ATTACHMENT(Set.of(
            "image/jpeg",
            "image/png",
            "image/gif",
            "image/webp",
            "application/pdf",
            "text/plain",
            "text/csv",
            // Tika's name for Markdown
            "text/x-web-markdown",
            "application/rtf",
            "application/msword",
            "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
            "application/vnd.oasis.opendocument.text",
            "application/vnd.ms-excel",
            "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
            "application/vnd.oasis.opendocument.spreadsheet",
            "application/vnd.ms-powerpoint",
            "application/vnd.openxmlformats-officedocument.presentationml.presentation",
            "application/vnd.oasis.opendocument.presentation",
            "application/zip"
    ));

    private final Set<String> allowedContentTypes;

    MediaUsage(Set<String> allowedContentTypes) {
        this.allowedContentTypes = allowedContentTypes;
    }

    public boolean allows(String contentType) {
        return allowedContentTypes.contains(contentType);
    }

    /** Object key prefix, e.g. {@code task-attachment}. */
    public String storagePrefix() {
        return name().toLowerCase().replace('_', '-');
    }
}
