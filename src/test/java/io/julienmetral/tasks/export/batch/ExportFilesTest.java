package io.julienmetral.tasks.export.batch;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class ExportFilesTest {

    private static final Path EXPORTS = Path.of(System.getProperty("java.io.tmpdir"), "exports");

    private final UUID exportId = UUID.randomUUID();

    private final UUID otherExportId = UUID.randomUUID();

    @AfterEach
    void deleteTheDirectories() {
        ExportFiles.deleteAll(exportId);
        ExportFiles.deleteAll(otherExportId);
    }

    @Test
    void fileOfAnExportLivesInADirectoryOfItsOwnThatExists() {
        Path file = ExportFiles.of(exportId, "my-data.json");

        assertThat(file).isEqualTo(EXPORTS.resolve(exportId.toString()).resolve("my-data.json"));
        assertThat(file.getParent()).isDirectory();
        assertThat(file).doesNotExist();
    }

    @Test
    void filesOfOneExportShareItsDirectory() {
        assertThat(ExportFiles.of(exportId, "my-data.json").getParent())
                .isEqualTo(ExportFiles.of(exportId, "my-data.pdf").getParent())
                .isNotEqualTo(ExportFiles.of(otherExportId, "my-data.json").getParent());
    }

    @Test
    void deleteAllDeletesTheDirectoryWithEveryFileInIt() throws IOException {
        Path json = Files.writeString(ExportFiles.of(exportId, "my-data.json"), "{}");
        Path pdf = Files.writeString(ExportFiles.of(exportId, "my-data.pdf"), "%PDF");

        ExportFiles.deleteAll(exportId);

        assertThat(json).doesNotExist();
        assertThat(pdf).doesNotExist();
        assertThat(json.getParent()).doesNotExist();
    }

    @Test
    void deleteAllLeavesTheFilesOfOtherExports() throws IOException {
        Files.writeString(ExportFiles.of(exportId, "export.csv"), "\"id\"");
        Path other = Files.writeString(ExportFiles.of(otherExportId, "export.csv"), "\"id\"");

        ExportFiles.deleteAll(exportId);

        assertThat(other).exists();
    }

    @Test
    void deleteAllOfAnExportThatWroteNothingDoesNothing() {
        ExportFiles.deleteAll(exportId);

        assertThat(EXPORTS.resolve(exportId.toString())).doesNotExist();
    }
}
