package io.julienmetral.tasks.export.batch;

import org.springframework.util.FileSystemUtils;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;

/**
 * The local directory where a run writes its files before storing the result: one per export, deleted when the job
 * ends. Local on purpose: a run resumed on another instance writes them again.
 */
public final class ExportFiles {

    private static final Path DIRECTORY = Path.of(System.getProperty("java.io.tmpdir"), "exports");

    private ExportFiles() {
    }

    public static Path of(UUID exportId, String name) {
        Path directory = DIRECTORY.resolve(exportId.toString());

        try {
            Files.createDirectories(directory);
        } catch (IOException exception) {
            throw new UncheckedIOException(exception);
        }

        return directory.resolve(name);
    }

    static void deleteAll(UUID exportId) {
        try {
            FileSystemUtils.deleteRecursively(DIRECTORY.resolve(exportId.toString()));
        } catch (IOException exception) {
            throw new UncheckedIOException(exception);
        }
    }
}
