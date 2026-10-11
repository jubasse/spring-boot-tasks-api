package io.julienmetral.tasks.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalStateException;

import com.fasterxml.jackson.annotation.JsonInclude;
import io.swagger.v3.oas.models.Components;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.Operation;
import io.swagger.v3.oas.models.PathItem;
import io.swagger.v3.oas.models.Paths;
import io.swagger.v3.oas.models.media.Content;
import io.swagger.v3.oas.models.media.JsonSchema;
import io.swagger.v3.oas.models.media.MediaType;
import io.swagger.v3.oas.models.media.Schema;
import io.swagger.v3.oas.models.parameters.RequestBody;
import io.swagger.v3.oas.models.responses.ApiResponse;
import io.swagger.v3.oas.models.responses.ApiResponses;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Test;

class NullabilityDocumentationTest {

    record NullabilityFixtureResponse(String id, @Nullable String note) {}

    record NullabilityFixtureRequest(String id, @Nullable String note) {}

    @JsonInclude(JsonInclude.Include.NON_NULL)
    record NullabilityFixtureSparse(String id, @Nullable String note) {}

    private final NullabilityDocumentation documentation = new NullabilityDocumentation();

    @Test
    void responseRequiresANullableFieldAndAllowsNull() {
        OpenAPI openApi = api(null, "NullabilityFixtureResponse");

        documentation.customise(openApi);

        Schema<?> schema = schema(openApi, "NullabilityFixtureResponse");
        assertThat(schema.getRequired()).containsExactly("id", "note");
        assertThat(property(schema, "note").getTypes()).containsExactly("string", "null");
        assertThat(property(schema, "id").getTypes()).containsExactly("string");
    }

    @Test
    void requestLeavesANullableFieldOptional() {
        OpenAPI openApi = api("NullabilityFixtureRequest", null);

        documentation.customise(openApi);

        Schema<?> schema = schema(openApi, "NullabilityFixtureRequest");
        assertThat(schema.getRequired()).containsExactly("id");
        assertThat(property(schema, "note").getTypes()).containsExactly("string", "null");
    }

    @Test
    void fieldLeftOutWhenNullIsOptionalAndNeverNull() {
        OpenAPI openApi = api(null, "NullabilityFixtureSparse");

        documentation.customise(openApi);

        Schema<?> schema = schema(openApi, "NullabilityFixtureSparse");
        assertThat(schema.getRequired()).containsExactly("id");
        assertThat(property(schema, "note").getTypes()).containsExactly("string");
    }

    @Test
    void schemaSharedByARequestAndAResponseCannotHaveANullableField() {
        OpenAPI openApi = api("NullabilityFixtureResponse", "NullabilityFixtureResponse");

        assertThatIllegalStateException()
                .isThrownBy(() -> documentation.customise(openApi))
                .withMessageContaining("NullabilityFixtureResponse")
                .withMessageContaining("note");
    }

    private static OpenAPI api(@Nullable String requestSchema, @Nullable String responseSchema) {
        Map<String, Schema> schemas = new LinkedHashMap<>();
        Operation operation = new Operation();

        if (requestSchema != null) {
            schemas.put(requestSchema, object());
            operation.requestBody(new RequestBody().content(content(requestSchema)));
        }
        if (responseSchema != null) {
            schemas.put(responseSchema, object());
            operation.responses(
                    new ApiResponses()
                            .addApiResponse(
                                    "200", new ApiResponse().content(content(responseSchema))));
        }
        return new OpenAPI()
                .paths(new Paths().addPathItem("/things", new PathItem().post(operation)))
                .components(new Components().schemas(schemas));
    }

    private static Schema<?> object() {
        return new JsonSchema()
                .types(Set.of("object"))
                .addProperty("id", new JsonSchema().types(Set.of("string")))
                .addProperty("note", new JsonSchema().types(Set.of("string")));
    }

    private static Content content(String schemaName) {
        return new Content()
                .addMediaType(
                        "application/json",
                        new MediaType()
                                .schema(
                                        new JsonSchema()
                                                .$ref("#/components/schemas/" + schemaName)));
    }

    private static Schema<?> schema(OpenAPI openApi, String name) {
        return openApi.getComponents().getSchemas().get(name);
    }

    private static Schema<?> property(Schema<?> schema, String name) {
        return schema.getProperties().get(name);
    }
}
