package io.julienmetral.tasks.config;

import io.julienmetral.tasks.shared.exceptions.ProblemType;
import io.swagger.v3.oas.models.examples.Example;
import io.swagger.v3.oas.models.headers.Header;
import io.swagger.v3.oas.models.media.Content;
import io.swagger.v3.oas.models.media.JsonSchema;
import io.swagger.v3.oas.models.media.MediaType;
import io.swagger.v3.oas.models.media.Schema;
import io.swagger.v3.oas.models.responses.ApiResponse;
import org.springframework.http.HttpStatus;

import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * The problem documents of the API (RFC 9457) as OpenAPI schemas and responses. Written by hand: the schema springdoc
 * derives from Spring's {@code ProblemDetail} describes a {@code properties} object, while the JSON flattens it.
 */
final class ProblemDocumentation {

    static final String PROBLEM_JSON = "application/problem+json";

    static final String PROBLEM = "Problem";

    static final String VALIDATION_PROBLEM = "ValidationProblem";

    static final String INVALID_VALUE = "InvalidValue";

    private ProblemDocumentation() {
    }

    static Map<String, Schema> schemas() {
        Map<String, Schema> schemas = new LinkedHashMap<>();

        schemas.put(PROBLEM, object()
                .description("A problem document (RFC 9457). docs/problems.md lists every problem and what to do.")
                .addProperty("type", string().format("uri")
                        .description("What went wrong; absent when the status says it all (about:blank). "
                                + "It links to the documentation of the problem."))
                .addProperty("title", string()
                        .description("Short summary, the same for every occurrence: the problem type's title, or "
                                + "the status phrase."))
                .addProperty("status", new JsonSchema().types(Set.of("integer")).format("int32")
                        .minimum(BigDecimal.valueOf(100)).maximum(BigDecimal.valueOf(599)))
                .addProperty("detail", string()
                        .description("This occurrence, for a person to read. It follows Accept-Language: do not "
                                + "parse it."))
                .addProperty("instance", string().format("uri-reference").description("The path of the request."))
                .required(List.of("title", "status")));

        schemas.put(INVALID_VALUE, object()
                .description("One invalid value of a request.")
                .addProperty("detail", string().description("What is wrong with the value."))
                .addProperty("pointer", string()
                        .description("JSON Pointer to the value in the JSON body, such as #/email; # is the body."))
                .addProperty("parameter", string()
                        .description("Name of the query, path, form or multipart parameter, when not in a JSON body."))
                .required(List.of("detail")));

        schemas.put(VALIDATION_PROBLEM, new JsonSchema().allOf(List.of(
                reference(PROBLEM),
                object().addProperty("errors", new JsonSchema().types(Set.of("array"))
                        .items(reference(INVALID_VALUE))
                        .description("Each invalid value, with the validation-error type.")))));

        return schemas;
    }

    /** A response documenting problems of one status, with an example of each typed problem. */
    static ApiResponse response(String description, String schema, List<ProblemType> types) {
        MediaType media = new MediaType().schema(reference(schema));

        for (ProblemType type : types) {
            Map<String, Object> value = new LinkedHashMap<>();
            value.put("type", type.type().toString());
            value.put("title", type.title());
            value.put("status", type.status().value());
            if (type == ProblemType.VALIDATION_ERROR) {
                value.put("errors", List.of(Map.of("detail", "must not be blank", "pointer", "#/title")));
            }
            media.addExamples(type.slug(), new Example().summary(type.title()).value(value));
        }

        return new ApiResponse()
                .description(description)
                .content(new Content().addMediaType(PROBLEM_JSON, media));
    }

    static ApiResponse response(String description) {
        return response(description, PROBLEM, List.of());
    }

    static ApiResponse withoutBody(String description, String header, String headerDescription) {
        return new ApiResponse()
                .description(description)
                .addHeaderObject(header, header(headerDescription, string()));
    }

    static Header header(String description, Schema<?> schema) {
        return new Header().description(description).schema(schema);
    }

    static String describe(HttpStatus status) {
        return status.getReasonPhrase();
    }

    static JsonSchema reference(String schema) {
        return (JsonSchema) new JsonSchema().$ref("#/components/schemas/" + schema);
    }

    static JsonSchema object() {
        return (JsonSchema) new JsonSchema().types(Set.of("object"));
    }

    static JsonSchema string() {
        return (JsonSchema) new JsonSchema().types(Set.of("string"));
    }
}
