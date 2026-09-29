package io.julienmetral.tasks.config;

import io.swagger.v3.oas.models.Components;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.Operation;
import io.swagger.v3.oas.models.PathItem;
import io.swagger.v3.oas.models.Paths;
import io.swagger.v3.oas.models.responses.ApiResponses;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;

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

    private static OpenAPI document(Paths paths) {
        return new OpenAPI().paths(paths).components(new Components().schemas(new LinkedHashMap<>()));
    }

    private static Operation operation(String id) {
        return new Operation().operationId(id).responses(new ApiResponses());
    }
}
