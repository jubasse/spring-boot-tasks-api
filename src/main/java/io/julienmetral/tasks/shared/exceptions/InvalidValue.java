package io.julienmetral.tasks.shared.exceptions;

import com.fasterxml.jackson.annotation.JsonInclude;
import tools.jackson.core.JacksonException;

import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZonedDateTime;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * One invalid value of a request, as an item of the {@code errors} member of a {@link ProblemType#VALIDATION_ERROR}
 * problem, in the shape of RFC 9457's example. {@code pointer} is a JSON Pointer into the JSON body;
 * {@code parameter} names a query, path, form or multipart parameter. Exactly one of them is set.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record InvalidValue(String detail, String pointer, String parameter) {

    static final Comparator<InvalidValue> ORDER = Comparator
            .comparing((InvalidValue value) -> value.pointer() != null ? value.pointer() : value.parameter())
            .thenComparing(InvalidValue::detail);

    static InvalidValue inBody(String detail, List<String> path) {
        return new InvalidValue(detail, pointer(path), null);
    }

    static InvalidValue inParameter(String detail, String name) {
        return new InvalidValue(detail, null, name);
    }

    /** Splits a Spring property path ({@code items[0].name}) into the tokens of a JSON Pointer. */
    static List<String> propertyPath(String field) {
        return Arrays.stream(field.replaceAll("\\[([^]]*)]", ".$1").split("\\."))
                .filter(token -> !token.isEmpty())
                .toList();
    }

    static List<String> jsonPath(JacksonException exception) {
        return exception.getPath().stream()
                .map(reference -> reference.getPropertyName() != null
                        ? reference.getPropertyName()
                        : String.valueOf(reference.getIndex()))
                .toList();
    }

    /**
     * What a value of this type must look like, in the client's terms: a Java type name or a parser message would
     * reveal the implementation without helping the client.
     */
    static String expected(Class<?> type) {
        if (type == null) {
            return "has an invalid value";
        }
        if (type.isEnum()) {
            return Arrays.stream(type.getEnumConstants())
                    .map(constant -> ((Enum<?>) constant).name())
                    .collect(Collectors.joining(", ", "must be one of ", ""));
        }
        if (type == UUID.class) {
            return "must be a UUID";
        }
        if (type == boolean.class || type == Boolean.class) {
            return "must be true or false";
        }
        if (type.isPrimitive() || Number.class.isAssignableFrom(type)) {
            return "must be a number";
        }
        if (type == Instant.class || type == OffsetDateTime.class || type == ZonedDateTime.class
                || type == LocalDateTime.class) {
            return "must be an ISO 8601 date and time";
        }
        if (type == LocalDate.class) {
            return "must be an ISO 8601 date";
        }
        return "has an invalid value";
    }

    // RFC 6901 escapes "~" and "/" inside a token; the "#" prefix is the URI fragment form RFC 9457's example uses,
    // and "#" alone points to the whole body (an error on the object rather than one of its fields)
    private static String pointer(List<String> path) {
        if (path.isEmpty()) {
            return "#";
        }
        return path.stream()
                .map(token -> token.replace("~", "~0").replace("/", "~1"))
                .collect(Collectors.joining("/", "#/", ""));
    }
}
