package io.julienmetral.tasks.config;

import io.julienmetral.tasks.identity.controllers.IdenticonController;
import io.julienmetral.tasks.identity.dtos.AuthResponseDto;
import io.julienmetral.tasks.identity.repositories.AccountState;
import io.julienmetral.tasks.identity.repositories.UserRepository;
import io.julienmetral.tasks.identity.services.AuthService;
import io.julienmetral.tasks.support.WebLayerTest;
import io.julienmetral.tasks.task.entities.Task;
import io.julienmetral.tasks.task.services.TaskService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.Page;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.servlet.MockMvc;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

import static io.julienmetral.tasks.support.Problems.untypedProblem;
import static io.julienmetral.tasks.support.Problems.withoutJavaTypeNames;
import static io.julienmetral.tasks.support.WebCallers.everyAccountIsActive;
import static io.julienmetral.tasks.support.WebCallers.user;
import static org.hamcrest.Matchers.allOf;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebLayerTest
class ApiVersioningWebMvcTests {

    private static final String UNSUPPORTED_VERSION = "This API version is not supported: use /api/v1";

    private static final String NO_ENDPOINT = "No endpoint matches this path";

    private static final String NEW_TASK = "{\"reference\": \"T-1\", \"title\": \"Task\"}";

    private static final String CREDENTIALS = "{\"email\": \"alice@example.com\", \"password\": \"password123\"}";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private TaskService taskService;

    @Autowired
    private AuthService authService;

    @Autowired
    private UserRepository userRepository;

    @Nested
    class CurrentVersion {

        @BeforeEach
        void callersAreActive() {
            everyAccountIsActive(userRepository);
        }

        @Test
        void taskListIsServedUnderVersionOne() throws Exception {
            when(taskService.findAll(any(), any(), anyBoolean(), any())).thenReturn(Page.empty());

            mockMvc.perform(get("/api/v1/tasks").with(user(UUID.randomUUID())))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.content").isArray());
        }

        @Test
        void createdTaskIsLocatedUnderVersionOne() throws Exception {
            UUID id = UUID.randomUUID();
            when(taskService.create(any())).thenReturn(task(id));

            mockMvc.perform(post("/api/v1/tasks")
                            .with(user(UUID.randomUUID()))
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(NEW_TASK))
                    .andExpect(status().isCreated())
                    .andExpect(header().string(HttpHeaders.LOCATION, "/api/v1/tasks/" + id));
        }

        @Test
        void loginUnderVersionOneIsPublic() throws Exception {
            when(authService.login(any())).thenReturn(new AuthResponseDto(
                    "access", "Bearer", Instant.parse("2030-01-01T00:15:00Z"),
                    "refresh", Instant.parse("2030-01-31T00:00:00Z")));

            mockMvc.perform(post("/api/v1/auth/login").contentType(MediaType.APPLICATION_JSON).content(CREDENTIALS))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.accessToken").value("access"));
        }

        @Test
        void identiconUnderVersionOneIsPublic() throws Exception {
            mockMvc.perform(get("/api/v1/identicons/{id}", UUID.randomUUID()))
                    .andExpect(status().isOk())
                    .andExpect(content().contentTypeCompatibleWith("image/svg+xml"));
        }

        @Test
        void identiconUrlGivenToClientsIsServedWithoutAToken() throws Exception {
            String identiconUrl = IdenticonController.urlOf(UUID.randomUUID());

            mockMvc.perform(get(identiconUrl))
                    .andExpect(status().isOk())
                    .andExpect(content().contentTypeCompatibleWith("image/svg+xml"));
        }

        @Test
        void versionOneAnswersWithoutDeprecationOrSunsetHeaders() throws Exception {
            when(taskService.findAll(any(), any(), anyBoolean(), any())).thenReturn(Page.empty());

            mockMvc.perform(get("/api/v1/tasks").with(user(UUID.randomUUID())))
                    .andExpect(status().isOk())
                    .andExpect(header().doesNotExist("Deprecation"))
                    .andExpect(header().doesNotExist("Sunset"))
                    .andExpect(header().doesNotExist(HttpHeaders.LINK));
        }
    }

    @Nested
    class UnsupportedVersion {

        @ParameterizedTest
        @ValueSource(strings = {"v2", "v0", "v1.5", "vx", "v"})
        void unsupportedOrMalformedVersionIsAnUntypedBadRequestPointingToVersionOne(String version) throws Exception {
            mockMvc.perform(get("/api/{version}/tasks", version).with(user(UUID.randomUUID())))
                    .andExpect(untypedProblem(400, "Bad Request"))
                    .andExpect(jsonPath("$.detail").value(UNSUPPORTED_VERSION))
                    .andExpect(withoutJavaTypeNames())
                    .andExpect(content().string(allOf(
                            not(containsString("Invalid API version")),
                            not(containsString("format")),
                            not(containsString("segment")))));

            verifyNoInteractions(taskService);
        }

        @Test
        void unsupportedVersionIsRefusedBeforeTheBodyIsValidated() throws Exception {
            mockMvc.perform(post("/api/v2/tasks")
                            .with(user(UUID.randomUUID()))
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{}"))
                    .andExpect(untypedProblem(400, "Bad Request"))
                    .andExpect(jsonPath("$.detail").value(UNSUPPORTED_VERSION))
                    .andExpect(jsonPath("$.errors").doesNotExist());

            verifyNoInteractions(taskService);
        }

        @Test
        void anonymousRequestToAnUnsupportedVersionIsUnauthorizedBeforeTheVersionIsChecked() throws Exception {
            mockMvc.perform(get("/api/v2/tasks"))
                    .andExpect(status().isUnauthorized())
                    .andExpect(content().string(""));
        }

        @Test
        void publicEndpointIsPublicUnderVersionOneOnly() throws Exception {
            mockMvc.perform(post("/api/v2/auth/login").contentType(MediaType.APPLICATION_JSON).content(CREDENTIALS))
                    .andExpect(status().isUnauthorized());

            verifyNoInteractions(authService);
        }
    }

    @Nested
    class AnotherSpellingOfVersionOne {

        @BeforeEach
        void taskListWouldBeServed() {
            when(taskService.findAll(any(), any(), anyBoolean(), any())).thenReturn(Page.empty());
        }

        @ParameterizedTest
        @ValueSource(strings = {"v1.0", "v1.0.0", "v01", "vv1"})
        void doesNotLetAnUnverifiedAccountIntoTasks(String version) throws Exception {
            when(userRepository.findAccountStateById(any())).thenReturn(Optional.of(new AccountState(true, null)));

            mockMvc.perform(get("/api/{version}/tasks", version).with(user(UUID.randomUUID())))
                    .andExpect(status().is4xxClientError());

            verifyNoInteractions(taskService);
        }

        @Test
        void doesNotLetADisabledAccountIntoTasks() throws Exception {
            when(userRepository.findAccountStateById(any()))
                    .thenReturn(Optional.of(new AccountState(false, Instant.parse("2026-01-01T00:00:00Z"))));

            mockMvc.perform(get("/api/v1.0/tasks").with(user(UUID.randomUUID())))
                    .andExpect(status().is4xxClientError());

            verifyNoInteractions(taskService);
        }
    }

    @Nested
    class UnmappedPath {

        @ParameterizedTest
        @ValueSource(strings = {"/api/tasks", "/api/v1/unknown", "/api/v2/unknown", "/api/V1/tasks", "/api/", "/api"})
        void apiPathThatMapsNothingIsAnUntypedNotFound(String path) throws Exception {
            mockMvc.perform(get(path).with(user(UUID.randomUUID())))
                    .andExpect(untypedProblem(404, "Not Found"))
                    .andExpect(jsonPath("$.detail").value(NO_ENDPOINT));
        }

        @Test
        void controllerPathWithoutTheApiPrefixIsNotServed() throws Exception {
            mockMvc.perform(get("/tasks").with(user(UUID.randomUUID())))
                    .andExpect(untypedProblem(404, "Not Found"));

            verifyNoInteractions(taskService);
        }

        @Test
        void versionLikeSegmentOutsideTheApiIsNotReadAsAVersion() throws Exception {
            mockMvc.perform(get("/v2/tasks").with(user(UUID.randomUUID())))
                    .andExpect(untypedProblem(404, "Not Found"))
                    .andExpect(jsonPath("$.detail").value(NO_ENDPOINT));
        }
    }

    private static Task task(UUID id) {
        Task task = new Task();
        ReflectionTestUtils.setField(task, "id", id);
        task.setReference("T-1");
        task.setTitle("Task");
        return task;
    }
}
