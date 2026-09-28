package io.julienmetral.tasks.config;

import io.julienmetral.tasks.TasksApplication;
import io.julienmetral.tasks.config.ServedOpenApi.Operation;
import io.julienmetral.tasks.support.IntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.mvc.method.RequestMappingInfo;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping;
import tools.jackson.databind.json.JsonMapper;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Predicate;

import static org.assertj.core.api.Assertions.assertThat;

@IntegrationTest
class ApiVersioningTests {

    private static final String VERSIONED_API = ApiVersioningConfiguration.API_PATH + "/";

    @Autowired
    @Qualifier("requestMappingHandlerMapping")
    private RequestMappingHandlerMapping handlerMapping;

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JsonMapper jsonMapper;

    @Test
    void everyApplicationHandlerIsServedUnderTheVersionedApiPath() {
        List<String> patterns = patternsOf(ApiVersioningTests::isApplicationHandler);

        assertThat(patterns).isNotEmpty().allSatisfy(pattern -> assertThat(pattern).startsWith(VERSIONED_API));
    }

    @Test
    void noApplicationControllerRepeatsTheApiPrefix() {
        List<String> patterns = patternsOf(ApiVersioningTests::isApplicationHandler);

        assertThat(patterns)
                .extracting(pattern -> pattern.substring(VERSIONED_API.length() - 1))
                .noneMatch(path -> path.startsWith("/api/") || path.matches("/v\\d+/.*"));
    }

    @Test
    void springdocAndFrameworkHandlersKeepTheirOwnPaths() {
        List<String> patterns = patternsOf(Predicate.not(ApiVersioningTests::isApplicationHandler));

        assertThat(patterns)
                .contains("/v3/api-docs", "/error")
                .noneMatch(pattern -> pattern.startsWith("/api/"));
    }

    @Test
    void publishedPathsNameTheCurrentVersionAndNoVersionParameter() throws Exception {
        ServedOpenApi api = ServedOpenApi.fetch(mockMvc, jsonMapper);

        assertThat(api.operations()).isNotEmpty().allSatisfy(operation -> {
            assertThat(operation.path()).startsWith("/api/v1/").doesNotContain("{version}");
            assertThat(operation.node().path("parameters").valueStream())
                    .noneMatch(parameter -> parameter.path("name").asString().equals("version"));
        });
        assertThat(api.operations()).extracting(Operation::path).contains("/api/v1/tasks", "/api/v1/tasks/{id}");
    }

    private List<String> patternsOf(Predicate<HandlerMethod> handlers) {
        return handlerMapping.getHandlerMethods().entrySet().stream()
                .filter(entry -> handlers.test(entry.getValue()))
                .map(Map.Entry::getKey)
                .map(RequestMappingInfo::getPatternValues)
                .flatMap(Set::stream)
                .toList();
    }

    private static boolean isApplicationHandler(HandlerMethod handler) {
        return handler.getBeanType().getPackageName().startsWith(TasksApplication.class.getPackageName());
    }
}
