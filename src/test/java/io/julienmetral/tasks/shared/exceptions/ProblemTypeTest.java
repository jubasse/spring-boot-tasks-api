package io.julienmetral.tasks.shared.exceptions;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.http.ProblemDetail;

import java.io.IOException;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;

class ProblemTypeTest {

    private static final Path DOCUMENT = Path.of("docs", "problems.md");

    private static final String DOCUMENT_URL =
            "https://github.com/jubasse/spring-boot-tasks-api/blob/main/docs/problems.md";

    // A section documents a type when its first paragraph states "**Status 409, title "...".**"
    private static final Pattern STATUS_AND_TITLE = Pattern.compile("^\\*\\*Status (\\d{3}), title \"([^\"]+)\"\\.\\*\\*");

    @ParameterizedTest
    @EnumSource(ProblemType.class)
    void typeIsAnAbsoluteLinkToAHeadingOfTheDocument(ProblemType problemType) throws IOException {
        URI type = problemType.type();

        assertThat(type.isAbsolute()).isTrue();
        assertThat(type.getScheme()).isEqualTo("https");
        assertThat(type.toString()).isEqualTo(DOCUMENT_URL + "#" + type.getFragment());
        assertThat(sectionsByAnchor()).containsKey(type.getFragment());
    }

    @ParameterizedTest
    @EnumSource(ProblemType.class)
    void documentStatesTheStatusAndTitleOfTheType(ProblemType problemType) throws IOException {
        ProblemDetail problem = problemType.problem("detail");
        String section = sectionsByAnchor().get(problemType.type().getFragment());

        Matcher statusAndTitle = STATUS_AND_TITLE.matcher(firstParagraph(section));

        assertThat(statusAndTitle.find())
                .as("section %s starts with its status and title", problemType.type().getFragment())
                .isTrue();
        assertThat(Integer.parseInt(statusAndTitle.group(1))).isEqualTo(problem.getStatus());
        assertThat(statusAndTitle.group(2)).isEqualTo(problem.getTitle());
    }

    @Test
    void everyTypeTheDocumentDescribesExistsInTheCode() throws IOException {
        List<String> documented = sectionsByAnchor().entrySet().stream()
                .filter(section -> STATUS_AND_TITLE.matcher(firstParagraph(section.getValue())).find())
                .map(Map.Entry::getKey)
                .toList();

        assertThat(documented).containsExactlyInAnyOrderElementsOf(
                Arrays.stream(ProblemType.values()).map(type -> type.type().getFragment()).toList()
        );
    }

    @Test
    void titlesAreUnique() {
        List<String> titles = Arrays.stream(ProblemType.values())
                .map(type -> type.problem("detail").getTitle())
                .toList();

        assertThat(titles).doesNotHaveDuplicates().doesNotContainNull();
    }

    @ParameterizedTest
    @EnumSource(ProblemType.class)
    void problemCarriesTheStatusOfItsType(ProblemType problemType) {
        ProblemDetail problem = problemType.problem("Something went wrong");

        assertThat(problem.getStatus()).isEqualTo(problemType.status().value());
        assertThat(problem.getType()).isEqualTo(problemType.type());
        assertThat(problem.getDetail()).isEqualTo("Something went wrong");
    }

    private static Map<String, String> sectionsByAnchor() throws IOException {
        Map<String, String> sections = new LinkedHashMap<>();
        String anchor = null;
        StringBuilder body = new StringBuilder();

        for (String line : Files.readAllLines(DOCUMENT)) {
            if (line.startsWith("## ")) {
                if (anchor != null) {
                    sections.put(anchor, body.toString());
                }
                anchor = githubAnchor(line.substring(3));
                body.setLength(0);
            } else if (anchor != null) {
                body.append(line).append('\n');
            }
        }
        if (anchor != null) {
            sections.put(anchor, body.toString());
        }
        return sections;
    }

    private static String githubAnchor(String heading) {
        return heading.strip()
                .toLowerCase(Locale.ROOT)
                .replaceAll("[^a-z0-9 _-]", "")
                .replace(' ', '-');
    }

    private static String firstParagraph(String section) {
        return section.strip().split("\n\n", 2)[0];
    }
}
