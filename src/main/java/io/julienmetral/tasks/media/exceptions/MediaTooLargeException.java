package io.julienmetral.tasks.media.exceptions;

import org.springframework.util.unit.DataSize;

public class MediaTooLargeException extends RuntimeException {

    public MediaTooLargeException(DataSize maxSize) {
        super("The file exceeds the maximum size of " + maxSize.toMegabytes() + " MB");
    }
}
