package io.julienmetral.tasks.shared.exceptions;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import tools.jackson.databind.exc.MismatchedInputException;
import tools.jackson.databind.json.JsonMapper;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZonedDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowableOfType;
import static org.junit.jupiter.params.provider.Arguments.arguments;

class InvalidValueTest {

    private final JsonMapper jsonMapper = JsonMapper.builder().build();

    @Test
    void propertyPathSplitsIndexesAndNestedFieldsIntoPointerTokens() {
        assertThat(InvalidValue.propertyPath("items[0].name")).containsExactly("items", "0", "name");
    }

    @Test
    void propertyPathKeepsAMapKeyAsOneToken() {
        assertThat(InvalidValue.propertyPath("labels[colour].value")).containsExactly("labels", "colour", "value");
    }

    @Test
    void propertyPathOfATopLevelFieldIsThatField() {
        assertThat(InvalidValue.propertyPath("email")).containsExactly("email");
    }

    @Test
    void bodyValueHasAJsonPointerAndNoParameter() {
        InvalidValue value = InvalidValue.inBody("must not be blank", InvalidValue.propertyPath("items[0].name"));

        assertThat(value).isEqualTo(new InvalidValue("must not be blank", "#/items/0/name", null));
    }

    @Test
    void emptyPathPointsToTheWholeBody() {
        assertThat(InvalidValue.inBody("must be valid", List.of()).pointer()).isEqualTo("#");
    }

    @Test
    void tildeAndSlashInATokenAreEscapedAsRfc6901Asks() {
        assertThat(InvalidValue.inBody("invalid", List.of("a/b", "c~d")).pointer()).isEqualTo("#/a~1b/c~0d");
    }

    @Test
    void tildeIsEscapedBeforeSlashSoAnEscapeIsNeverEscapedTwice() {
        assertThat(InvalidValue.inBody("invalid", List.of("~1", "/")).pointer()).isEqualTo("#/~01/~1");
    }

    @Test
    void parameterValueNamesTheParameterAndHasNoPointer() {
        assertThat(InvalidValue.inParameter("must be a UUID", "id"))
                .isEqualTo(new InvalidValue("must be a UUID", null, "id"));
    }

    @Test
    void jsonPathOfAMismatchWalksThroughArraysAndObjects() {
        MismatchedInputException mismatch = mismatch(
                "{\"lines\": [{\"quantity\": 1}, {\"quantity\": \"many\"}]}", Order.class);

        assertThat(InvalidValue.jsonPath(mismatch)).containsExactly("lines", "1", "quantity");
    }

    @Test
    void jsonPathOfAMismatchUnderAMapKeyBecomesAnEscapedPointer() {
        MismatchedInputException mismatch = mismatch("{\"counts\": {\"a/b\": \"many\"}}", Counts.class);

        assertThat(InvalidValue.inBody("must be a number", InvalidValue.jsonPath(mismatch)).pointer())
                .isEqualTo("#/counts/a~1b");
    }

    @ParameterizedTest
    @MethodSource
    void expectedDescribesEachTypeFamilyInTheClientsTerms(Class<?> type, String expected) {
        assertThat(InvalidValue.expected(type)).isEqualTo(expected);
    }

    static Stream<Arguments> expectedDescribesEachTypeFamilyInTheClientsTerms() {
        return Stream.of(
                arguments(Colour.class, "must be one of RED, GREEN, BLUE"),
                arguments(UUID.class, "must be a UUID"),
                arguments(boolean.class, "must be true or false"),
                arguments(Boolean.class, "must be true or false"),
                arguments(int.class, "must be a number"),
                arguments(long.class, "must be a number"),
                arguments(Integer.class, "must be a number"),
                arguments(Long.class, "must be a number"),
                arguments(BigDecimal.class, "must be a number"),
                arguments(Instant.class, "must be an ISO 8601 date and time"),
                arguments(OffsetDateTime.class, "must be an ISO 8601 date and time"),
                arguments(ZonedDateTime.class, "must be an ISO 8601 date and time"),
                arguments(LocalDateTime.class, "must be an ISO 8601 date and time"),
                arguments(LocalDate.class, "must be an ISO 8601 date"),
                arguments(String.class, "has an invalid value"),
                arguments(Map.class, "has an invalid value")
        );
    }

    @Test
    void unknownTypeHasAnInvalidValue() {
        assertThat(InvalidValue.expected(null)).isEqualTo("has an invalid value");
    }

    @Test
    void valuesAreOrderedByLocationThenDetail() {
        List<InvalidValue> sorted = Stream.of(
                new InvalidValue("size must be between 8 and 128", "#/password", null),
                new InvalidValue("must be a UUID", null, "id"),
                new InvalidValue("must not be blank", "#/password", null),
                new InvalidValue("must not be blank", "#/displayName", null)
        ).sorted(InvalidValue.ORDER).toList();

        assertThat(sorted).containsExactly(
                new InvalidValue("must not be blank", "#/displayName", null),
                new InvalidValue("must not be blank", "#/password", null),
                new InvalidValue("size must be between 8 and 128", "#/password", null),
                new InvalidValue("must be a UUID", null, "id")
        );
    }

    private MismatchedInputException mismatch(String json, Class<?> type) {
        MismatchedInputException mismatch = catchThrowableOfType(
                MismatchedInputException.class, () -> jsonMapper.readValue(json, type));

        assertThat(mismatch).isNotNull();
        return mismatch;
    }

    enum Colour { RED, GREEN, BLUE }

    record Order(List<Line> lines) {
    }

    record Line(int quantity) {
    }

    record Counts(Map<String, Integer> counts) {
    }
}
