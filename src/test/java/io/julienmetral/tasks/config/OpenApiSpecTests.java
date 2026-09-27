package io.julienmetral.tasks.config;

import io.julienmetral.tasks.support.IntegrationTest;
import org.junit.jupiter.api.Test;
import org.skyscreamer.jsonassert.JSONAssert;
import org.skyscreamer.jsonassert.JSONCompareMode;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import tools.jackson.databind.SerializationFeature;
import tools.jackson.databind.cfg.JsonNodeFeature;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.startsWith;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@IntegrationTest
class OpenApiSpecTests {

    private static final Path COMMITTED_SPEC = Path.of("docs", "openapi.json");

    private static final String UPDATE_PROPERTY = "openapi.update";

    // The version comes from build-info and changes with every release; this is the value an application started
    // without build-info serves
    private static final String NORMALIZED_VERSION = "development";

    private static final String HOW_TO_UPDATE = """
            The API documentation served at /v3/api-docs differs from %s. If the change is intended, regenerate the \
            file with ./mvnw test -Dtest=OpenApiSpecTests -D%s=true, review its diff and commit it.\
            """.formatted(COMMITTED_SPEC, UPDATE_PROPERTY);

    private static final JsonMapper SORTED_PRETTY_PRINTER = JsonMapper.builder()
            .enable(SerializationFeature.INDENT_OUTPUT)
            .enable(JsonNodeFeature.WRITE_PROPERTIES_SORTED)
            .build();

    @Autowired
    private MockMvc mockMvc;

    @Test
    void servedDocumentMatchesTheCommittedSpec() throws Exception {
        String served = normalizedServedSpec();

        if (Boolean.getBoolean(UPDATE_PROPERTY)) {
            Files.writeString(COMMITTED_SPEC, served, StandardCharsets.UTF_8);
            return;
        }

        assertThat(COMMITTED_SPEC).as(HOW_TO_UPDATE).exists();
        JSONAssert.assertEquals(
                HOW_TO_UPDATE + "\nDifferences:",
                Files.readString(COMMITTED_SPEC, StandardCharsets.UTF_8),
                served,
                JSONCompareMode.STRICT);
    }

    @Test
    void apiDocsAreServedWithoutAToken() throws Exception {
        mockMvc.perform(get("/v3/api-docs"))
                .andExpect(status().isOk())
                .andExpect(header().string(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE));
    }

    @Test
    void swaggerUiConfigurationIsServedWithoutAToken() throws Exception {
        mockMvc.perform(get("/v3/api-docs/swagger-config"))
                .andExpect(status().isOk());
    }

    @Test
    void swaggerUiIsServedWithoutAToken() throws Exception {
        mockMvc.perform(get("/swagger-ui/index.html"))
                .andExpect(status().isOk())
                .andExpect(header().string(HttpHeaders.CONTENT_TYPE, startsWith("text/html")));
    }

    @Test
    void swaggerUiShortcutRedirectsWithoutAToken() throws Exception {
        mockMvc.perform(get("/swagger-ui.html"))
                .andExpect(status().isFound())
                .andExpect(redirectedUrl("/swagger-ui/index.html"));
    }

    private String normalizedServedSpec() throws Exception {
        ObjectNode spec = (ObjectNode) ServedOpenApi.fetch(mockMvc, SORTED_PRETTY_PRINTER).document();
        ((ObjectNode) spec.required("info")).put("version", NORMALIZED_VERSION);

        return SORTED_PRETTY_PRINTER.writeValueAsString(spec) + "\n";
    }
}
