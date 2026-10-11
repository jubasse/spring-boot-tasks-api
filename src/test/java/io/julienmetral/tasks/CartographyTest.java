package io.julienmetral.tasks;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.IntStream;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;

class CartographyTest {

    private static final Path MAP = Path.of("docs", "cartography.md");

    private static final Path MAIN_SOURCES =
            Path.of("src", "main", "java", "io", "julienmetral", "tasks");

    private static final Path TEST_SUPPORT =
            Path.of("src", "test", "java", "io", "julienmetral", "tasks", "support");

    private static final String HOW_TO_UPDATE = "Missing from docs/cartography.md; update the map";

    private static final String PACKAGES_HEADING = "## Packages";

    // Anchored at the start of the line: a nested annotation type is indented, a top-level one is
    // not
    private static final Pattern TOP_LEVEL_ANNOTATION =
            Pattern.compile("^(?:public\\s+)?@interface\\s+(\\w+)", Pattern.MULTILINE);

    private static final Pattern ROW_NAMING_A_PATH = Pattern.compile("^\\|\\s*`([^`]+)`\\s*\\|");

    @Test
    void scanFindsThePackagesAnnotationsAndTestHelpers() {
        assertThat(packagesWithClasses(MAIN_SOURCES))
                .contains("config", "notification/webhook", "identity/security")
                .doesNotContain("", "identity");
        assertThat(annotations(MAIN_SOURCES, TEST_SUPPORT))
                .contains("@AdminOnly", "@IntegrationTest");
        assertThat(testHelpers(TEST_SUPPORT)).contains("TestClock", "@IntegrationTest");
    }

    @Test
    void everyPackageWithClassesIsMapped() {
        assertThat(unmapped(packagesWithClasses(MAIN_SOURCES), map())).as(HOW_TO_UPDATE).isEmpty();
    }

    @Test
    void everyMappedPackageExists() {
        assertThat(stalePackages(map(), MAIN_SOURCES))
                .as(
                        "Packages section rows of docs/cartography.md naming no directory with classes; update the map")
                .isEmpty();
    }

    @Test
    void everyAnnotationOfTheProjectIsMapped() {
        assertThat(unmapped(annotations(MAIN_SOURCES, TEST_SUPPORT), map()))
                .as(HOW_TO_UPDATE)
                .isEmpty();
    }

    @Test
    void everyTestHelperIsMapped() {
        assertThat(unmapped(testHelpers(TEST_SUPPORT), map())).as(HOW_TO_UPDATE).isEmpty();
    }

    @Test
    void itemsOutsideBackticksAreReportedAsUnmapped() {
        String map = "`config`, @AdminOnly and `TestClocks`";

        assertThat(unmapped(List.of("config", "@AdminOnly", "TestClock"), map))
                .containsExactly("@AdminOnly", "TestClock");
    }

    @Test
    void packagesRowWithoutClassesIsReportedAndOtherSectionsAreIgnored() {
        String map =
                """
                ## Packages

                | Package | Role |
                |---|---|
                | `config` | configuration |
                | `identity` | holds only packages |
                | `nowhere` | does not exist |

                ## Annotations

                | `elsewhere` | not a package |
                """;

        assertThat(stalePackages(map, MAIN_SOURCES)).containsExactly("identity", "nowhere");
    }

    @Test
    void mapWithoutPackagesSectionIsReported() {
        assertThat(stalePackages("# Map\n", MAIN_SOURCES))
                .singleElement()
                .asString()
                .contains(PACKAGES_HEADING);
    }

    @Test
    void annotationNestedInAClassIsIgnored() {
        Path fixtures =
                Path.of("src", "test", "java", "io", "julienmetral", "tasks", "shared", "security");

        assertThat(annotations(fixtures)).doesNotContain("@Undescribed", "@MisspelledPlaceholder");
    }

    private static String map() {
        assertThat(MAP)
                .as("docs/cartography.md, the map of the project for coding agents")
                .isRegularFile();
        return read(MAP);
    }

    private static List<String> unmapped(List<String> items, String map) {
        return items.stream().filter(item -> !map.contains("`" + item + "`")).toList();
    }

    private static List<String> packagesWithClasses(Path base) {
        try (Stream<Path> directories = Files.walk(base)) {
            return directories
                    .filter(Files::isDirectory)
                    .filter(directory -> !directory.equals(base))
                    .filter(CartographyTest::containsJavaFile)
                    .map(
                            directory ->
                                    base.relativize(directory)
                                            .toString()
                                            .replace(directory.getFileSystem().getSeparator(), "/"))
                    .sorted()
                    .toList();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static List<String> stalePackages(String map, Path base) {
        List<String> lines = map.lines().toList();
        int heading =
                IntStream.range(0, lines.size())
                        .filter(index -> lines.get(index).startsWith(PACKAGES_HEADING))
                        .findFirst()
                        .orElse(-1);
        if (heading < 0) {
            return List.of("the map has no '" + PACKAGES_HEADING + "' section");
        }

        List<String> stale = new ArrayList<>();
        for (String line : lines.subList(heading + 1, lines.size())) {
            if (line.startsWith("## ")) {
                break;
            }
            Matcher row = ROW_NAMING_A_PATH.matcher(line);
            if (row.find()) {
                Path directory = base.resolve(row.group(1));
                if (!Files.isDirectory(directory) || !containsJavaFile(directory)) {
                    stale.add(row.group(1));
                }
            }
        }
        return stale;
    }

    private static List<String> annotations(Path... roots) {
        return Stream.of(roots)
                .flatMap(root -> javaFiles(root).stream())
                .flatMap(file -> TOP_LEVEL_ANNOTATION.matcher(read(file)).results())
                .map(match -> "@" + match.group(1))
                .sorted()
                .toList();
    }

    private static List<String> testHelpers(Path support) {
        List<String> annotations = annotations(support);
        return javaFiles(support).stream()
                .map(file -> file.getFileName().toString().replaceFirst("\\.java$", ""))
                .map(name -> annotations.contains("@" + name) ? "@" + name : name)
                .sorted()
                .toList();
    }

    private static List<Path> javaFiles(Path root) {
        try (Stream<Path> files = Files.walk(root)) {
            return files.filter(Files::isRegularFile).filter(CartographyTest::isJavaFile).toList();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static boolean containsJavaFile(Path directory) {
        try (Stream<Path> files = Files.list(directory)) {
            return files.anyMatch(file -> Files.isRegularFile(file) && isJavaFile(file));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static boolean isJavaFile(Path file) {
        return file.getFileName().toString().endsWith(".java");
    }

    private static String read(Path file) {
        try {
            return Files.readString(file);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
