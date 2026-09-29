package io.julienmetral.tasks.export.batch;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;

/** Where a run writes its file before storing it: local, so a run resumed on another instance writes it again. */
final class ExportFiles {

    private static final Path DIRECTORY = Path.of(System.getProperty("java.io.tmpdir"), "exports");

    private ExportFiles() {
    }

    static Path of(UUID exportId, String extension) {
        try {
            Files.createDirectories(DIRECTORY);
        } catch (IOException exception) {
            throw new UncheckedIOException(exception);
        }

        return DIRECTORY.resolve(exportId + "." + extension);
    }

    static void deleteAll(UUID exportId) {
        for (String extension : new String[] {"csv", "zip"}) {
            try {
                Files.deleteIfExists(DIRECTORY.resolve(exportId + "." + extension));
            } catch (IOException exception) {
                throw new UncheckedIOException(exception);
            }
        }
    }
}
