package io.julienmetral.tasks.config;

import io.julienmetral.tasks.identity.security.PublicEndpoints;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.Operation;
import io.swagger.v3.oas.models.PathItem;
import io.swagger.v3.oas.models.Paths;
import io.swagger.v3.oas.models.media.Schema;
import io.swagger.v3.oas.models.parameters.Parameter;
import io.swagger.v3.oas.models.responses.ApiResponses;
import org.springdoc.core.customizers.OpenApiCustomizer;
import org.springframework.http.server.PathContainer;
import org.springframework.web.util.pattern.PathPatternParser;

import java.math.BigDecimal;
import java.util.List;
import java.util.Set;

import static io.julienmetral.tasks.config.OpenApiConfiguration.BAD_REQUEST;
import static io.julienmetral.tasks.config.OpenApiConfiguration.FORBIDDEN;
import static io.julienmetral.tasks.config.OpenApiConfiguration.NOT_FOUND;
import static io.julienmetral.tasks.config.OpenApiConfiguration.UNAUTHORIZED;
import static io.julienmetral.tasks.config.OpenApiConfiguration.UNEXPECTED_ERROR;
import static io.julienmetral.tasks.config.OperationDocumentation.reference;

/**
 * Documents what depends on an operation's path: public operations without the bearer requirement
 * ({@link PublicEndpoints}), 401 on the others, 403 on task and notification operations (they need an active account),
 * 400 when the operation takes input that can be invalid, 404 when its path names a resource, and a default response
 * for an unexpected failure.
 */
class PathDocumentation implements OpenApiCustomizer {

    private final int maxPageSize;

    PathDocumentation(int maxPageSize) {
        this.maxPageSize = maxPageSize;
    }

    // The paths SecurityConfiguration reserves to active accounts
    private static final List<String> ACTIVE_ACCOUNT_PATHS = List.of("/api/v1/tasks", "/api/v1/notifications");

    // An identicon exists for every id, so an unknown one is not a 404
    private static final String IDENTICONS = "/api/v1/identicons";

    @Override
    public void customise(OpenAPI openApi) {
        pinUnversionedPaths(openApi);

        openApi.getPaths().forEach((path, item) -> item.readOperationsMap().forEach((method, operation) ->
                document(path, method, operation)));

        requirePageFields(openApi);
    }

    /**
     * springdoc publishes the raw {@code /api/v{version}} prefix of a mapping without a version attribute, since it
     * cannot know that such a mapping answers the current version: every path became {@code /api/v{version}/...},
     * and the rules below read {@code {version}} as a resource id.
     */
    private static void pinUnversionedPaths(OpenAPI openApi) {
        String unversioned = ApiVersioningConfiguration.API_PATH + "/";
        String current = ApiVersioningConfiguration.CURRENT_PATH + "/";
        Paths pinned = new Paths();

        openApi.getPaths().forEach((path, item) -> pinned.addPathItem(path.replace(unversioned, current), item));
        openApi.setPaths(pinned);
    }

    static boolean isPublic(PathItem.HttpMethod method, String path) {
        // A documented path names its variables ({id}); any value matches the patterns of the security rules
        PathContainer concrete = PathContainer.parsePath(path.replaceAll("\\{[^}]+}", "x"));

        return PublicEndpoints.ALL.stream().anyMatch(endpoint ->
                endpoint.method().name().equals(method.name())
                        && PathPatternParser.defaultInstance.parse(endpoint.pattern()).matches(concrete));
    }

    private void document(String path, PathItem.HttpMethod method, Operation operation) {
        ApiResponses responses = operation.getResponses();

        if (isPublic(method, path)) {
            operation.setSecurity(List.of());
        } else {
            responses.putIfAbsent("401", reference(UNAUTHORIZED));
        }

        if (ACTIVE_ACCOUNT_PATHS.stream().anyMatch(path::startsWith)) {
            responses.putIfAbsent("403", reference(FORBIDDEN));
        }

        if (hasInput(operation)) {
            responses.putIfAbsent("400", reference(BAD_REQUEST));
        }

        if (path.contains("{") && !path.startsWith(IDENTICONS)) {
            responses.putIfAbsent("404", reference(NOT_FOUND));
        }

        responses.putIfAbsent("default", reference(UNEXPECTED_ERROR));

        documentPageSizeLimit(operation);
    }

    // Spring Data clamps a larger size silently (spring.data.web.pageable.max-page-size); springdoc does not show it
    private void documentPageSizeLimit(Operation operation) {
        if (operation.getParameters() == null) {
            return;
        }

        operation.getParameters().stream()
                .filter(parameter -> "query".equals(parameter.getIn()) && "size".equals(parameter.getName()))
                .filter(parameter -> parameter.getSchema() != null)
                .forEach(parameter -> parameter.getSchema().setMaximum(BigDecimal.valueOf(maxPageSize)));
    }

    // An optional text header, such as Last-Event-ID, accepts any value
    private static boolean hasInput(Operation operation) {
        return operation.getRequestBody() != null
                || (operation.getParameters() != null && operation.getParameters().stream()
                .anyMatch(parameter -> !isOptionalTextHeader(parameter)));
    }

    private static boolean isOptionalTextHeader(Parameter parameter) {
        return "header".equals(parameter.getIn())
                && !Boolean.TRUE.equals(parameter.getRequired())
                && parameter.getSchema() != null
                && parameter.getSchema().getTypes() != null
                && parameter.getSchema().getTypes().equals(Set.of("string"))
                && parameter.getSchema().getFormat() == null;
    }

    // springdoc does not mark the members of PagedModel as required (springdoc-openapi #3360), while every page has
    // them
    @SuppressWarnings("rawtypes")
    private static void requirePageFields(OpenAPI openApi) {
        openApi.getComponents().getSchemas().forEach((name, schema) -> {
            if (name.startsWith("PagedModel")) {
                require(schema, List.of("content", "page"));
            } else if (name.equals("PageMetadata")) {
                require(schema, List.of("number", "size", "totalElements", "totalPages"));
            }
        });
    }

    @SuppressWarnings("rawtypes")
    private static void require(Schema schema, List<String> properties) {
        if (schema.getProperties() != null && schema.getProperties().keySet().containsAll(properties)) {
            schema.setRequired(properties);
        }
    }
}
