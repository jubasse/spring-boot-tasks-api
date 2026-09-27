package io.julienmetral.tasks.config;

import io.julienmetral.tasks.config.ServedOpenApi.Operation;
import io.julienmetral.tasks.identity.security.PublicEndpoints;
import io.julienmetral.tasks.shared.exceptions.ProblemType;
import io.julienmetral.tasks.support.IntegrationTest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.security.web.servlet.util.matcher.PathPatternRequestMatcher;
import org.springframework.test.web.servlet.MockMvc;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.util.Arrays;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.function.Predicate;
import java.util.stream.Collectors;

import static io.julienmetral.tasks.config.OpenApiConfiguration.CONTENT_TOO_LARGE;
import static io.julienmetral.tasks.config.OpenApiConfiguration.FORBIDDEN;
import static io.julienmetral.tasks.config.OpenApiConfiguration.NOT_FOUND;
import static io.julienmetral.tasks.config.OpenApiConfiguration.SERVICE_UNAVAILABLE;
import static io.julienmetral.tasks.config.OpenApiConfiguration.UNAUTHORIZED;
import static io.julienmetral.tasks.config.OpenApiConfiguration.UNEXPECTED_ERROR;
import static io.julienmetral.tasks.config.OpenApiConfiguration.UNSUPPORTED_MEDIA_TYPE;
import static io.julienmetral.tasks.config.OpenApiConfiguration.responseReference;
import static org.assertj.core.api.Assertions.assertThat;

// The committed docs/openapi.json shows what a new endpoint changed, not whether it follows the rules below
@IntegrationTest
class OpenApiDocumentTests {

    private static final String PROBLEM_JSON = "application/problem+json";

    private static final String TASKS = "/api/v1/tasks";

    private static final String IDENTICONS = "/api/v1/identicons";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JsonMapper jsonMapper;

    @Value("${spring.data.web.pageable.max-page-size}")
    private int maxPageSize;

    private ServedOpenApi api;

    @BeforeEach
    void fetchDocument() throws Exception {
        api = ServedOpenApi.fetch(mockMvc, jsonMapper);
    }

    @Test
    void operationIdsAreUniqueAndCarryNoNumberedSuffix() {
        List<String> ids = api.operations().stream().map(Operation::id).toList();

        assertThat(ids).isNotEmpty().doesNotContain("").doesNotHaveDuplicates();
        // springdoc appends _1, _2 to a repeated handler method name: renaming the method is the fix
        assertThat(ids).noneMatch(id -> id.matches(".*_\\d+"));
    }

    @Test
    void everyOperationHasOneDeclaredTagAndASummary() {
        Map<String, String> declaredTags = api.document().path("tags").valueStream()
                .collect(Collectors.toMap(
                        tag -> tag.path("name").asString(),
                        tag -> tag.path("description").asString()));

        assertThat(declaredTags.values()).allSatisfy(description -> assertThat(description).isNotBlank());
        assertThat(api.operations()).allSatisfy(operation -> {
            JsonNode tags = operation.node().path("tags");

            assertThat(tags.size()).as("tags of %s", operation).isEqualTo(1);
            assertThat(declaredTags).as("tag of %s", operation).containsKey(tags.path(0).asString());
            assertThat(operation.node().path("summary").asString("")).as("summary of %s", operation).isNotBlank();
        });
    }

    @Test
    void everyOperationNeedingATokenDocumentsTheUnauthorizedResponse() {
        assertThat(operations(operation -> !operation.isPublic())).allSatisfy(operation ->
                assertThat(responseReferenceOf(operation, "401")).as("401 of %s", operation)
                        .isEqualTo(responseReference(UNAUTHORIZED)));
    }

    // A public operation may still answer 401 about the credentials in its body (login, refresh), never about a token
    @Test
    void publicOperationsDoNotDocumentTheUnauthorizedResponseOfAMissingToken() {
        assertThat(operations(Operation::isPublic)).isNotEmpty().allSatisfy(operation ->
                assertThat(responseReferenceOf(operation, "401")).as("401 of %s", operation)
                        .isNotEqualTo(responseReference(UNAUTHORIZED)));
    }

    @Test
    void everyTaskOperationDocumentsTheForbiddenResponseOfAnInactiveAccount() {
        assertThat(operations(operation -> operation.path().startsWith(TASKS))).isNotEmpty().allSatisfy(operation ->
                assertThat(responseReferenceOf(operation, "403")).as("403 of %s", operation)
                        .isEqualTo(responseReference(FORBIDDEN)));
    }

    // Matched as SecurityConfiguration matches them, not as PathDocumentation does, so that the two cannot share a bug
    @Test
    void operationsWithoutSecurityAreExactlyThoseOfThePublicEndpoints() {
        assertThat(api.operations()).allSatisfy(operation ->
                assertThat(operation.isPublic())
                        .as("%s documented as public", operation)
                        .isEqualTo(PublicEndpoints.ALL.stream().anyMatch(endpoint -> matches(endpoint, operation))));
    }

    @Test
    void everyPublicEndpointMatchesADocumentedOperation() {
        assertThat(PublicEndpoints.ALL).allSatisfy(endpoint ->
                assertThat(api.operations())
                        .as("operations matching %s", endpoint)
                        .anyMatch(operation -> matches(endpoint, operation)));
    }

    @Test
    void everyErrorResponseIsAProblemDocumentExceptThoseOfTheAccessChecks() {
        Set<String> withoutBody = Set.of(responseReference(UNAUTHORIZED), responseReference(FORBIDDEN));

        api.operations().forEach(operation -> operation.responses().forEachEntry((code, response) -> {
            if (isError(code) && !withoutBody.contains(response.path("$ref").asString(""))) {
                assertThat(api.resolve(response).path("content").propertyNames())
                        .as("content of the %s of %s", code, operation)
                        .containsExactly(PROBLEM_JSON);
            }
        }));
    }

    @Test
    void responsesOfTheAccessChecksHaveNoBodyButAWwwAuthenticateHeader() {
        for (String component : List.of(UNAUTHORIZED, FORBIDDEN)) {
            JsonNode response = api.document().path("components").path("responses").required(component);

            assertThat(response.has("content")).as("%s has a body", component).isFalse();
            assertThat(response.path("headers").has("WWW-Authenticate")).as(component).isTrue();
        }
    }

    @Test
    void everyOperationDocumentsAnUnexpectedFailure() {
        assertThat(api.operations()).allSatisfy(operation ->
                assertThat(responseReferenceOf(operation, "default")).as("default of %s", operation)
                        .isEqualTo(responseReference(UNEXPECTED_ERROR)));
    }

    @Test
    void everyOperationTakingInputDocumentsAValidationError() {
        List<Operation> takingInput = operations(operation ->
                !operation.node().path("parameters").isEmpty() || operation.node().has("requestBody"));

        assertThat(takingInput).isNotEmpty().allSatisfy(operation ->
                assertThat(examplesOf(operation, "400")).as("400 examples of %s", operation)
                        .containsKey(ProblemType.VALIDATION_ERROR.slug()));
    }

    // An identicon exists for every id
    @Test
    void everyOperationOnANamedResourceDocumentsNotFound() {
        List<Operation> onAResource = operations(operation ->
                operation.path().contains("{") && !operation.path().startsWith(IDENTICONS));

        assertThat(onAResource).isNotEmpty().allSatisfy(operation ->
                assertThat(responseReferenceOf(operation, "404")).as("404 of %s", operation)
                        .isEqualTo(responseReference(NOT_FOUND)));
        assertThat(api.operation("getIdenticon").responses().has("404")).isFalse();
    }

    @Disabled("bug: the @ApiResponse(401) on login and refreshTokens stops springdoc from adding their 200, so "
            + "AuthResponseDto is documented nowhere")
    @Test
    void everyOperationDocumentsOneSuccessResponse() {
        assertThat(api.operations()).allSatisfy(operation ->
                assertThat(operation.responses().propertyNames())
                        .as("success responses of %s", operation)
                        .filteredOn(code -> code.startsWith("2"))
                        .hasSize(1));
    }

    @Test
    void createdResponsesGiveTheLocationOfTheNewResource() {
        List<Operation> creating = operations(operation -> operation.responses().has("201"));

        assertThat(creating).isNotEmpty().allSatisfy(operation ->
                assertThat(operation.responses().path("201").path("headers").has("Location"))
                        .as("Location of %s", operation)
                        .isTrue());
    }

    @Test
    void everyUploadDocumentsTheLimitsOfAFile() {
        assertThat(uploads()).isNotEmpty().allSatisfy(operation -> {
            assertThat(responseReferenceOf(operation, "413")).as("413 of %s", operation)
                    .isEqualTo(responseReference(CONTENT_TOO_LARGE));
            assertThat(responseReferenceOf(operation, "415")).as("415 of %s", operation)
                    .isEqualTo(responseReference(UNSUPPORTED_MEDIA_TYPE));
            assertThat(responseReferenceOf(operation, "503")).as("503 of %s", operation)
                    .isEqualTo(responseReference(SERVICE_UNAVAILABLE));
        });
    }

    @Disabled("bug: both addComment handlers fill one operation, and OperationDocumentation.documentProblems lets the "
            + "JSON handler's 422 replace the multipart one's, dropping infected-file")
    @Test
    void everyUploadShowsTheInfectedFileProblem() {
        assertThat(uploads()).isNotEmpty().allSatisfy(operation ->
                assertThat(examplesOf(operation, "422")).as("422 examples of %s", operation)
                        .containsKey(ProblemType.INFECTED_FILE.slug()));
    }

    @Test
    void commentFormDocumentsTheBodyAndTheFiles() {
        JsonNode content = api.operation("addComment").node().path("requestBody").path("content");
        JsonNode form = content.path(MediaType.MULTIPART_FORM_DATA_VALUE).path("schema").path("allOf");
        JsonNode files = form.path(1).path("properties").path("files");

        assertThat(content.path(MediaType.APPLICATION_JSON_VALUE).path("schema").path("$ref").asString())
                .isEqualTo("#/components/schemas/TaskCommentDto");
        assertThat(form.path(0).path("$ref").asString()).isEqualTo("#/components/schemas/TaskCommentDto");
        assertThat(api.document().path("components").path("schemas").path("TaskCommentDto").path("properties")
                .has("body")).isTrue();
        assertThat(files.path("type").asString()).isEqualTo("array");
        assertThat(files.path("items").path("format").asString()).isEqualTo("binary");
    }

    @Test
    void pageSizeIsCappedAtTheMaximumSpringDataApplies() {
        List<JsonNode> sizes = api.operations().stream()
                .flatMap(operation -> operation.node().path("parameters").valueStream())
                .filter(parameter -> parameter.path("in").asString().equals("query")
                        && parameter.path("name").asString().equals("size"))
                .toList();

        assertThat(sizes).isNotEmpty().allSatisfy(size ->
                assertThat(size.path("schema").path("maximum").asInt()).isEqualTo(maxPageSize));
        assertThat(api.document().path("info").path("description").asString())
                .contains("A page holds at most " + maxPageSize + " items.");
    }

    @Test
    void pageSchemasRequireTheirContentAndMetadata() {
        Map<String, JsonNode> schemas = new LinkedHashMap<>();
        api.document().path("components").path("schemas").forEachEntry(schemas::put);

        assertThat(schemas.keySet()).filteredOn(name -> name.startsWith("PagedModel")).isNotEmpty()
                .allSatisfy(name -> assertThat(requiredOf(schemas.get(name))).as(name)
                        .containsExactly("content", "page"));
        assertThat(requiredOf(schemas.get("PageMetadata")))
                .containsExactlyInAnyOrder("number", "size", "totalElements", "totalPages");
    }

    @Test
    void examplesShowProblemTypesUnderTheirOwnStatus() {
        Map<String, ProblemType> typesByUri = Arrays.stream(ProblemType.values())
                .collect(Collectors.toMap(type -> type.type().toString(), Function.identity()));

        api.operations().forEach(operation -> operation.responses().propertyNames().forEach(code ->
                examplesOf(operation, code).forEach((name, example) -> {
                    JsonNode value = example.path("value");
                    ProblemType type = typesByUri.get(value.path("type").asString());
                    String where = "example " + name + " of the " + code + " of " + operation;

                    assertThat(type).as(where).isNotNull();
                    assertThat(name).as(where).isEqualTo(type.slug());
                    assertThat(value.path("title").asString()).as(where).isEqualTo(type.title());
                    assertThat(value.path("status").asInt()).as(where)
                            .isEqualTo(type.status().value())
                            .isEqualTo(Integer.parseInt(code));
                })));
    }

    @Test
    void everyProblemTypeHasAnExample() {
        Set<ProblemType> shown = EnumSet.noneOf(ProblemType.class);

        api.operations().forEach(operation -> operation.responses().propertyNames().forEach(code ->
                examplesOf(operation, code).keySet().forEach(name -> Arrays.stream(ProblemType.values())
                        .filter(type -> type.slug().equals(name))
                        .forEach(shown::add))));

        assertThat(shown).containsExactlyInAnyOrder(ProblemType.values());
    }

    private List<Operation> operations(Predicate<Operation> filter) {
        return api.operations().stream().filter(filter).toList();
    }

    private List<Operation> uploads() {
        return operations(operation -> operation.consumes(MediaType.MULTIPART_FORM_DATA_VALUE));
    }

    private static String responseReferenceOf(Operation operation, String code) {
        return operation.responses().path(code).path("$ref").asString("");
    }

    private Map<String, JsonNode> examplesOf(Operation operation, String code) {
        Map<String, JsonNode> examples = new LinkedHashMap<>();
        api.resolve(operation.responses().path(code)).path("content").path(PROBLEM_JSON).path("examples")
                .forEachEntry(examples::put);
        return examples;
    }

    private static List<String> requiredOf(JsonNode schema) {
        return schema.path("required").valueStream().map(JsonNode::asString).toList();
    }

    private static boolean isError(String code) {
        return code.startsWith("4") || code.startsWith("5") || code.equals("default");
    }

    private static boolean matches(PublicEndpoints.Endpoint endpoint, Operation operation) {
        return PathPatternRequestMatcher.pathPattern(endpoint.method(), endpoint.pattern())
                .matches(operation.sampleRequest());
    }
}
