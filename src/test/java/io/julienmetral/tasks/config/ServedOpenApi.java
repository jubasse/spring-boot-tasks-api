package io.julienmetral.tasks.config;

import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.test.web.servlet.MockMvc;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** The OpenAPI document the application serves at {@code /v3/api-docs}, with its operations listed. */
record ServedOpenApi(JsonNode document, List<Operation> operations) {

    private static final Set<String> HTTP_METHODS =
            Set.of("get", "put", "post", "delete", "patch", "head", "options", "trace");

    private static final String RESPONSE_REFERENCE = OpenApiConfiguration.responseReference("");

    static ServedOpenApi fetch(MockMvc mockMvc, JsonMapper jsonMapper) throws Exception {
        String body = mockMvc.perform(get("/v3/api-docs").header(HttpHeaders.ACCEPT_LANGUAGE, "en"))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString(StandardCharsets.UTF_8);
        JsonNode document = jsonMapper.readTree(body);

        List<Operation> operations = new ArrayList<>();
        for (Map.Entry<String, JsonNode> path : document.required("paths").properties()) {
            for (Map.Entry<String, JsonNode> operation : path.getValue().properties()) {
                if (HTTP_METHODS.contains(operation.getKey())) {
                    operations.add(new Operation(
                            path.getKey(),
                            HttpMethod.valueOf(operation.getKey().toUpperCase(Locale.ROOT)),
                            operation.getValue()));
                }
            }
        }
        return new ServedOpenApi(document, List.copyOf(operations));
    }

    Operation operation(String operationId) {
        return operations.stream()
                .filter(operation -> operation.id().equals(operationId))
                .findFirst()
                .orElseThrow(() -> new AssertionError("No operation " + operationId));
    }

    /** The response itself, or the component response it references. */
    JsonNode resolve(JsonNode response) {
        String reference = response.path("$ref").asString("");

        if (!reference.startsWith(RESPONSE_REFERENCE)) {
            return response;
        }
        return document.path("components")
                .path("responses")
                .required(reference.substring(RESPONSE_REFERENCE.length()));
    }

    record Operation(String path, HttpMethod method, JsonNode node) {

        String id() {
            return node.path("operationId").asString("");
        }

        boolean isPublic() {
            JsonNode security = node.path("security");

            return security.isArray() && security.isEmpty();
        }

        JsonNode responses() {
            return node.path("responses");
        }

        boolean consumes(String mediaType) {
            return node.path("requestBody").path("content").has(mediaType);
        }

        /** The path with a random UUID in each variable, as a client would call it. */
        String concretePath() {
            return path.replaceAll("\\{[^}]+}", UUID.randomUUID().toString());
        }

        MockHttpServletRequest sampleRequest() {
            return new MockHttpServletRequest(method.name(), concretePath());
        }

        @Override
        public String toString() {
            return method + " " + path;
        }
    }
}
