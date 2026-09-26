package io.julienmetral.tasks.media.exceptions;

import org.springframework.util.unit.DataSize;

public class MediaTooLargeException extends RuntimeException {

    private static final long KILOBYTE = DataSize.ofKilobytes(1).toBytes();

    private static final long MEGABYTE = DataSize.ofMegabytes(1).toBytes();

    public MediaTooLargeException(DataSize maxSize) {
        super("The file exceeds the maximum size of " + readable(maxSize));
    }

    // DataSize.toMegabytes() rounds down: a 1.5 MB limit would read "1 MB"
    private static String readable(DataSize size) {
        long bytes = size.toBytes();

        if (bytes % MEGABYTE == 0) {
            return bytes / MEGABYTE + " MB";
        }

        if (bytes % KILOBYTE == 0) {
            return bytes / KILOBYTE + " KB";
        }

        return bytes + " bytes";
    }
}
