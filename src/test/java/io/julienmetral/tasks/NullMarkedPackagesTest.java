package io.julienmetral.tasks;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;

/**
 * NullAway checks only {@code @NullMarked} code (its {@code OnlyNullMarked} option): a package
 * without the annotation compiles unchecked, silently.
 */
class NullMarkedPackagesTest {

    private static final Path MAIN_SOURCES = Path.of("src", "main", "java");

    private static final Path ROOT_PACKAGE = MAIN_SOURCES.resolve(Path.of("io", "julienmetral"));

    private static final String PACKAGE_INFO = "package-info.java";

    private static final Pattern NULL_MARKED_PACKAGE =
            Pattern.compile(
                    "^@(?:org\\.jspecify\\.annotations\\.)?NullMarked\\s+package\\s",
                    Pattern.MULTILINE);

    @Test
    void scanFindsThePackagesWithClasses() {
        assertThat(packagesWithClasses())
                .contains(
                        "io.julienmetral.tasks",
                        "io.julienmetral.tasks.config",
                        "io.julienmetral.tasks.notification.webhook")
                .doesNotContain("io.julienmetral");
    }

    @Test
    void everyPackageWithClassesIsNullMarked() {
        assertThat(
                        directoriesWithClasses().stream()
                                .filter(directory -> !isNullMarked(directory))
                                .map(NullMarkedPackagesTest::packageName)
                                .toList())
                .as("Packages without a package-info.java carrying @NullMarked")
                .isEmpty();
    }

    @Test
    void annotationOnTheImportIsNotMistakenForThePackage() {
        assertThat(
                        NULL_MARKED_PACKAGE
                                .matcher(
                                        "package io.example;\n\nimport org.jspecify.annotations.NullMarked;\n")
                                .find())
                .isFalse();
        assertThat(
                        NULL_MARKED_PACKAGE
                                .matcher(
                                        "@NullMarked\npackage io.example;\n\nimport org.jspecify.annotations.NullMarked;\n")
                                .find())
                .isTrue();
    }

    private static List<String> packagesWithClasses() {
        return directoriesWithClasses().stream().map(NullMarkedPackagesTest::packageName).toList();
    }

    private static List<Path> directoriesWithClasses() {
        try (Stream<Path> directories = Files.walk(ROOT_PACKAGE)) {
            return directories
                    .filter(Files::isDirectory)
                    .filter(NullMarkedPackagesTest::containsClass)
                    .sorted()
                    .toList();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static boolean containsClass(Path directory) {
        try (Stream<Path> files = Files.list(directory)) {
            return files.map(file -> file.getFileName().toString())
                    .anyMatch(name -> name.endsWith(".java") && !name.equals(PACKAGE_INFO));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static String packageName(Path directory) {
        return MAIN_SOURCES
                .relativize(directory)
                .toString()
                .replace(directory.getFileSystem().getSeparator(), ".");
    }

    private static boolean isNullMarked(Path directory) {
        Path packageInfo = directory.resolve(PACKAGE_INFO);

        try {
            return Files.exists(packageInfo)
                    && NULL_MARKED_PACKAGE.matcher(Files.readString(packageInfo)).find();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
