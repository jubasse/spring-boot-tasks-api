package io.julienmetral.tasks.support;

import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.ResultMatcher;

import static org.hamcrest.Matchers.allOf;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The RFC 9457 contract of {@code docs/problems.md}, as MockMvc matchers. Types and titles are spelled out rather than
 * read from {@code ProblemType}, so that renaming one, which breaks clients, also breaks a test.
 */
public final class Problems {

    public static final String TYPES = "https://github.com/jubasse/spring-boot-tasks-api/blob/main/docs/problems.md#";

    public static final String INVALID_REQUEST = "One or more values of the request are invalid: see errors.";

    private Problems() {
    }

    public static ResultMatcher typedProblem(int status, String slug, String title) {
        return all(
                status().is(status),
                content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON),
                jsonPath("$.type").value(TYPES + slug),
                jsonPath("$.title").value(title),
                jsonPath("$.status").value(status)
        );
    }

    /** An {@code about:blank} problem: no {@code type} member, and the status phrase as title. */
    public static ResultMatcher untypedProblem(int status, String statusPhrase) {
        return all(
                status().is(status),
                content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON),
                jsonPath("$.type").doesNotExist(),
                jsonPath("$.title").value(statusPhrase),
                jsonPath("$.status").value(status)
        );
    }

    public static ResultMatcher validationError() {
        return all(
                typedProblem(400, "validation-error", "Invalid request"),
                jsonPath("$.detail").value(INVALID_REQUEST)
        );
    }

    /** A validation error whose only invalid value is the parameter {@code name}. */
    public static ResultMatcher invalidParameter(String name, String detail) {
        return all(
                validationError(),
                jsonPath("$.errors.length()").value(1),
                jsonPath("$.errors[0].parameter").value(name),
                jsonPath("$.errors[0].detail").value(detail),
                jsonPath("$.errors[0].pointer").doesNotExist()
        );
    }

    /** A validation error whose only invalid value is at {@code pointer} in the JSON body. */
    public static ResultMatcher invalidBodyValue(String pointer, String detail) {
        return all(
                validationError(),
                jsonPath("$.errors.length()").value(1),
                jsonPath("$.errors[0].pointer").value(pointer),
                jsonPath("$.errors[0].detail").value(detail),
                jsonPath("$.errors[0].parameter").doesNotExist()
        );
    }

    /** No Java class, package or parser wording anywhere in the body, as {@code docs/problems.md} promises. */
    public static ResultMatcher withoutJavaTypeNames() {
        return content().string(allOf(
                not(containsString("java")),
                not(containsString("jackson")),
                not(containsString("julienmetral")),
                not(containsString("Dto")),
                not(containsString("Cannot deserialize")),
                not(containsString("Failed to convert"))
        ));
    }

    private static ResultMatcher all(ResultMatcher... matchers) {
        return result -> {
            for (ResultMatcher matcher : matchers) {
                matcher.match(result);
            }
        };
    }
}
