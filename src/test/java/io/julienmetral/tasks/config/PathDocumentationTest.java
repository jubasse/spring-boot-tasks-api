package io.julienmetral.tasks.config;

import io.swagger.v3.oas.models.Components;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.Operation;
import io.swagger.v3.oas.models.PathItem;
import io.swagger.v3.oas.models.Paths;
import io.swagger.v3.oas.models.media.Schema;
import io.swagger.v3.oas.models.parameters.Parameter;
import io.swagger.v3.oas.models.parameters.RequestBody;
import io.swagger.v3.oas.models.responses.ApiResponses;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

class PathDocumentationTest {

    private final PathDocumentation documentation = new PathDocumentation(100);

    @Test
    void unversionedPathIsPublishedUnderTheCurrentVersionWithItsOperations() {
        Operation getTask = operation("getTask");
        Operation deleteTask = operation("deleteTask");
        PathItem task = new PathItem().get(getTask).delete(deleteTask);
        OpenAPI openApi = document(new Paths().addPathItem("/api/v{version}/tasks/{id}", task));

        documentation.customise(openApi);

        assertThat(openApi.getPaths()).containsOnlyKeys("/api/v1/tasks/{id}");
        PathItem published = openApi.getPaths().get("/api/v1/tasks/{id}");
        assertThat(published).isSameAs(task);
        assertThat(published.getGet()).isSameAs(getTask);
        assertThat(published.getDelete()).isSameAs(deleteTask);
    }

    @Test
    void pathsOutsideTheVersionedApiAreUntouchedAndKeepTheirOrder() {
        PathItem other = new PathItem().get(operation("other"));
        PathItem alreadyPinned = new PathItem().get(operation("listUsers"));
        OpenAPI openApi = document(new Paths()
                .addPathItem("/other/{id}", other)
                .addPathItem("/api/v{version}/tasks", new PathItem().get(operation("listTasks")))
                .addPathItem("/api/v1/users", alreadyPinned));

        documentation.customise(openApi);

        assertThat(openApi.getPaths().keySet()).containsExactly("/other/{id}", "/api/v1/tasks", "/api/v1/users");
        assertThat(openApi.getPaths().get("/other/{id}")).isSameAs(other);
        assertThat(openApi.getPaths().get("/api/v1/users")).isSameAs(alreadyPinned);
    }

    @Test
    void versionPrefixIsNotDocumentedAsAResourceId() {
        Operation listTasks = operation("listTasks");
        OpenAPI openApi = document(new Paths().addPathItem("/api/v{version}/tasks", new PathItem().get(listTasks)));

        documentation.customise(openApi);

        assertThat(listTasks.getResponses()).doesNotContainKey("404").containsKeys("401", "403");
    }

    @Test
    void exportOperationsDocumentTheForbiddenResponseOfAnInactiveAccount() {
        Operation listExports = operation("listExports");
        Operation getExport = operation("getExport");
        OpenAPI openApi = document(new Paths()
                .addPathItem("/api/v{version}/exports", new PathItem().get(listExports))
                .addPathItem("/api/v{version}/exports/{id}", new PathItem().get(getExport)));

        documentation.customise(openApi);

        assertThat(listExports.getResponses()).containsKeys("401", "403").doesNotContainKey("404");
        assertThat(getExport.getResponses()).containsKeys("401", "403", "404");
    }

    @Test
    void operationOutsideTheActiveAccountPathsGetsNoForbiddenResponseFromItsPath() {
        Operation getUser = operation("getUser");
        OpenAPI openApi = document(new Paths().addPathItem("/api/v{version}/users/{id}", new PathItem().get(getUser)));

        documentation.customise(openApi);

        assertThat(getUser.getResponses()).containsKeys("401", "404").doesNotContainKey("403");
    }

    @Test
    void publicEndpointIsRecognisedThroughItsPinnedPath() {
        Operation login = operation("login");
        OpenAPI openApi = document(new Paths().addPathItem("/api/v{version}/auth/login", new PathItem().post(login)));

        documentation.customise(openApi);

        assertThat(login.getSecurity()).isEmpty();
        assertThat(login.getResponses()).doesNotContainKey("401");
    }

    @Test
    void notificationOperationsDocumentTheForbiddenResponseOfAnInactiveAccount() {
        Operation stream = operation("streamNotifications");
        OpenAPI openApi = document(new Paths()
                .addPathItem("/api/v{version}/notifications/stream", new PathItem().get(stream)));

        documentation.customise(openApi);

        assertThat(stream.getResponses()).containsKey("403");
    }

    @Test
    void operationsOnAnAccountGetNoForbiddenResponseFromTheirPath() {
        Operation settings = operation("getNotificationSettings");
        OpenAPI openApi = document(new Paths()
                .addPathItem("/api/v{version}/users/{id}/notification-settings", new PathItem().get(settings)));

        documentation.customise(openApi);

        assertThat(settings.getResponses()).doesNotContainKey("403");
    }

    @Test
    void optionalTextHeaderIsNoInputThatCanBeInvalid() {
        Operation stream = operation("streamNotifications")
                .addParametersItem(header("Last-Event-ID", false, schema("string", null)));
        OpenAPI openApi = document(new Paths()
                .addPathItem("/api/v{version}/notifications/stream", new PathItem().get(stream)));

        documentation.customise(openApi);

        assertThat(stream.getResponses()).doesNotContainKey("400");
    }

    static Stream<Arguments> inputsThatCanBeInvalid() {
        return Stream.of(
                Arguments.of("required text header", operation("read")
                        .addParametersItem(header("X-Tenant", true, schema("string", null)))),
                Arguments.of("optional header with a format", operation("read")
                        .addParametersItem(header("X-Request-Id", false, schema("string", "uuid")))),
                Arguments.of("optional number header", operation("read")
                        .addParametersItem(header("X-Page", false, schema("integer", null)))),
                Arguments.of("optional text query parameter", operation("read")
                        .addParametersItem(new Parameter().in("query").name("q").required(false)
                                .schema(schema("string", null)))),
                Arguments.of("optional text header next to a query parameter", operation("read")
                        .addParametersItem(header("Last-Event-ID", false, schema("string", null)))
                        .addParametersItem(new Parameter().in("query").name("size").schema(schema("integer", null)))),
                Arguments.of("request body", operation("create").requestBody(new RequestBody()))
        );
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("inputsThatCanBeInvalid")
    void inputThatCanBeInvalidDocumentsTheBadRequestResponse(String input, Operation operation) {
        OpenAPI openApi = document(new Paths().addPathItem("/api/v{version}/users", new PathItem().post(operation)));

        documentation.customise(openApi);

        assertThat(operation.getResponses()).containsKey("400");
    }

    private static OpenAPI document(Paths paths) {
        return new OpenAPI().paths(paths).components(new Components().schemas(new LinkedHashMap<>()));
    }

    private static Operation operation(String id) {
        return new Operation().operationId(id).responses(new ApiResponses());
    }

    private static Parameter header(String name, boolean required, Schema<?> schema) {
        return new Parameter().in("header").name(name).required(required).schema(schema);
    }

    // As springdoc describes a type in an OpenAPI 3.1 document: a set of types
    private static Schema<?> schema(String type, String format) {
        return new Schema<>().types(new LinkedHashSet<>(Set.of(type))).format(format);
    }
}
