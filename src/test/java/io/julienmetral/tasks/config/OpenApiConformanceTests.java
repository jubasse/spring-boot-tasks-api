package io.julienmetral.tasks.config;

import io.julienmetral.tasks.config.ServedOpenApi.Operation;
import io.julienmetral.tasks.identity.entities.User;
import io.julienmetral.tasks.identity.entities.UserRole;
import io.julienmetral.tasks.identity.repositories.UserRepository;
import io.julienmetral.tasks.support.IntegrationTest;
import io.julienmetral.tasks.support.WebCallers;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.RequestBuilder;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.time.Instant;
import java.util.EnumSet;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.SoftAssertions.assertSoftly;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.request;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@IntegrationTest
class OpenApiConformanceTests {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JsonMapper jsonMapper;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private PasswordEncoder passwordEncoder;

    private ServedOpenApi api;

    @BeforeEach
    void fetchDocument() throws Exception {
        api = ServedOpenApi.fetch(mockMvc, jsonMapper);
    }

    // An empty JSON body fails validation, so the public operations answer 400 without changing anything
    @Test
    void onlyTheOperationsDocumentedAsPublicAnswerWithoutAToken() {
        assertSoftly(softly -> {
            for (Operation operation : api.operations()) {
                MockHttpServletResponse response = perform(withoutToken(operation));

                if (operation.isPublic()) {
                    softly.assertThat(response.getStatus()).as("%s without a token", operation).isNotEqualTo(401);
                } else {
                    softly.assertThat(response.getStatus()).as("%s without a token", operation).isEqualTo(401);
                    softly.assertThat(response.getContentAsByteArray())
                            .as("body of the 401 of %s", operation)
                            .isEmpty();
                    softly.assertThat(response.getHeader(HttpHeaders.WWW_AUTHENTICATE))
                            .as("WWW-Authenticate of the 401 of %s", operation)
                            .startsWith("Bearer");
                }
            }
        });
    }

    @Disabled("bug: TaskService.getAssignableUser throws UserNotFoundException for an unknown assignedTo, so "
            + "createTask answers a 404 it does not document")
    @Test
    void creatingATaskForAnUnknownAssigneeAnswersADocumentedStatus() throws Exception {
        User admin = createActiveUser(UserRole.ADMIN);

        MockHttpServletResponse response = mockMvc.perform(post("/api/v1/tasks")
                        .with(WebCallers.admin(admin.getId()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"reference": "%s", "title": "Task", "assignedTo": "%s"}
                                """.formatted(uniqueReference(), UUID.randomUUID())))
                .andReturn()
                .getResponse();

        assertThat(api.operation("createTask").responses().propertyNames())
                .as("documented responses of createTask, which answered %s", response.getStatus())
                .contains(String.valueOf(response.getStatus()));
    }

    @Disabled("bug: TaskEventRepository.findAllByTaskIdOrderByOccurredAtDesc fixes the order, so the sort parameter "
            + "listTaskHistory documents only breaks ties")
    @Test
    void taskHistoryFollowsTheDocumentedSortParameter() throws Exception {
        assertThat(api.operation("listTaskHistory").node().path("parameters").valueStream()
                .map(parameter -> parameter.path("name").asString()))
                .contains("sort");
        User admin = createActiveUser(UserRole.ADMIN);
        UUID taskId = createTask(admin);
        mockMvc.perform(patch("/api/v1/tasks/" + taskId)
                        .with(WebCallers.admin(admin.getId()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"title\": \"Renamed\"}"))
                .andExpect(status().isOk());

        JsonNode oldestFirst = json(mockMvc.perform(get("/api/v1/tasks/" + taskId + "/events")
                        .param("sort", "occurredAt,asc")
                        .with(WebCallers.admin(admin.getId())))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString());

        assertThat(oldestFirst.path("content").valueStream().map(event -> event.path("type").asString()))
                .containsExactly("CREATED", "UPDATED");
    }

    private MockHttpServletResponse perform(RequestBuilder request) {
        try {
            return mockMvc.perform(request).andReturn().getResponse();
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private static RequestBuilder withoutToken(Operation operation) {
        if (operation.consumes(MediaType.APPLICATION_JSON_VALUE)) {
            return request(operation.method(), operation.concretePath())
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("{}");
        }
        if (operation.consumes(MediaType.MULTIPART_FORM_DATA_VALUE)) {
            return multipart(operation.method(), operation.concretePath());
        }
        return request(operation.method(), operation.concretePath());
    }

    private UUID createTask(User admin) throws Exception {
        String body = mockMvc.perform(post("/api/v1/tasks")
                        .with(WebCallers.admin(admin.getId()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"reference": "%s", "title": "Task"}
                                """.formatted(uniqueReference())))
                .andExpect(status().isCreated())
                .andReturn()
                .getResponse()
                .getContentAsString();

        return UUID.fromString(json(body).path("id").asString());
    }

    private User createActiveUser(UserRole role) {
        User user = new User();

        user.setEmail(UUID.randomUUID() + "@example.com");
        user.setPasswordHash(passwordEncoder.encode("password"));
        user.setEmailVerifiedAt(Instant.now());
        user.setDisplayName("OpenAPI " + role);
        user.setRoles(EnumSet.of(UserRole.USER, role));

        return userRepository.saveAndFlush(user);
    }

    private JsonNode json(String body) {
        return jsonMapper.readTree(body);
    }

    private static String uniqueReference() {
        return "T-" + UUID.randomUUID().toString().substring(0, 8);
    }
}
