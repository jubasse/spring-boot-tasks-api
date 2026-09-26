package io.julienmetral.tasks.media.exceptions;

import io.julienmetral.tasks.media.model.MediaUsage;

public class UnsupportedMediaTypeException extends RuntimeException {

    public UnsupportedMediaTypeException(String detectedContentType, MediaUsage usage) {
        super("Files of type " + detectedContentType + " are not accepted for " + usage);
    }
}
