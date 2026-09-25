package io.julienmetral.tasks.task.controllers;

import io.julienmetral.tasks.identity.entities.User;
import io.julienmetral.tasks.identity.entities.UserRole;
import io.julienmetral.tasks.task.repositories.TaskRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Only ACTIVE users (enabled, not deleted, email verified) can use {@code /api/v1/tasks/**}, and
 * only ACTIVE users can be assigned a task.
 */
class TaskAccessRulesApiTests extends AbstractUserStateTaskApiTests {

    @Autowired
    private TaskRepository taskRepository;

    // --- Access to the task endpoints ---

    @Test
    void unverifiedUserIsForbiddenOnEveryTaskEndpoint() throws Exception {
        User admin = createUser(UserRole.ADMIN);
        UUID taskId = createTask(admin, null);
        User unverified = createUnverifiedUser(UserRole.ADMIN);

        assertForbiddenEverywhere(as(unverified, UserRole.ADMIN), taskId);
        assertTaskUntouched(admin, taskId);
    }

    @Test
    void disabledUserIsForbiddenOnEveryTaskEndpoint() throws Exception {
        User admin = createUser(UserRole.ADMIN);
        UUID taskId = createTask(admin, null);
        User disabled = createDisabledUser(UserRole.ADMIN);

        assertForbiddenEverywhere(as(disabled, UserRole.ADMIN), taskId);
        assertTaskUntouched(admin, taskId);
    }

    @Test
    void deletedUserIsForbiddenOnEveryTaskEndpoint() throws Exception {
        User admin = createUser(UserRole.ADMIN);
        UUID taskId = createTask(admin, null);
        User deleted = createDeletedUser(UserRole.ADMIN);

        assertForbiddenEverywhere(as(deleted, UserRole.ADMIN), taskId);
        assertTaskUntouched(admin, taskId);
    }

    @Test
    void unknownUserIsForbiddenOnEveryTaskEndpoint() throws Exception {
        User admin = createUser(UserRole.ADMIN);
        UUID taskId = createTask(admin, null);
        RequestPostProcessor unknown = jwtFor(UUID.randomUUID());

        assertForbiddenEverywhere(unknown, taskId);
    }

    @Test
    void tokenWithoutUidClaimIsForbiddenOnTasks() throws Exception {
        RequestPostProcessor noUid = jwt()
                .authorities(new SimpleGrantedAuthority("ROLE_ADMIN"));

        mockMvc.perform(get(TASKS).with(noUid)).andExpect(status().isForbidden());
    }

    @Test
    void assigneeWhoIsDisabledLosesAccessToTheirOwnTask() throws Exception {
        User admin = createUser(UserRole.ADMIN);
        User assignee = createUser(UserRole.USER);
        UUID taskId = createTask(admin, assignee);

        mockMvc.perform(get(TASKS + "/" + taskId).with(asUser(assignee))).andExpect(status().isOk());

        disableThroughApi(assignee);

        mockMvc.perform(get(TASKS + "/" + taskId).with(asUser(assignee))).andExpect(status().isForbidden());
        mockMvc.perform(patch(TASKS + "/" + taskId + "/status").with(asUser(assignee))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"status\": \"IN_PROGRESS\"}"))
                .andExpect(status().isForbidden());
    }

    @Test
    void requestsWithoutTokenAreUnauthorized() throws Exception {
        User admin = createUser(UserRole.ADMIN);
        UUID taskId = createTask(admin, null);

        mockMvc.perform(get(TASKS)).andExpect(status().isUnauthorized());
        mockMvc.perform(get(TASKS + "/" + taskId)).andExpect(status().isUnauthorized());
        mockMvc.perform(get(TASKS + "/" + taskId + "/events")).andExpect(status().isUnauthorized());
        mockMvc.perform(post(TASKS).contentType(MediaType.APPLICATION_JSON).content(createBody(null)))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void realTokenOfUnverifiedUserIsForbiddenOnTasks() throws Exception {
        User unverified = createUnverifiedUser(UserRole.USER);
        String token = login(unverified);

        mockMvc.perform(get(TASKS).header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
                .andExpect(status().isForbidden());
        // User endpoints stay reachable, e.g. to read one's own profile
        mockMvc.perform(get("/api/v1/users/" + unverified.getId())
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
                .andExpect(status().isOk());
    }

    @Test
    void stillValidTokenStopsWorkingOnceUserIsDisabled() throws Exception {
        User admin = createUser(UserRole.ADMIN);
        UUID taskId = createTask(admin, null);
        String token = login(admin);

        mockMvc.perform(get(TASKS + "/" + taskId).header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
                .andExpect(status().isOk());

        disableThroughApi(admin);

        mockMvc.perform(get(TASKS + "/" + taskId).header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
                .andExpect(status().isForbidden());
        mockMvc.perform(get(TASKS + "/" + taskId + "/events").header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
                .andExpect(status().isForbidden());
    }

    @Test
    void stillValidTokenStopsWorkingOnceUserIsDeleted() throws Exception {
        User admin = createUser(UserRole.ADMIN);
        UUID taskId = createTask(admin, null);
        String token = login(admin);

        deleteThroughApi(admin);

        mockMvc.perform(get(TASKS + "/" + taskId).header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
                .andExpect(status().isForbidden());
    }

    @Test
    void userEndpointsAreNotRestrictedToActiveUsers() throws Exception {
        User unverified = createUnverifiedUser(UserRole.USER);
        User disabled = createDisabledUser(UserRole.USER);

        mockMvc.perform(get("/api/v1/users/" + unverified.getId()).with(asUser(unverified)))
                .andExpect(status().isOk());
        mockMvc.perform(get("/api/v1/users/" + disabled.getId()).with(asUser(disabled)))
                .andExpect(status().isOk());
        mockMvc.perform(patch("/api/v1/users/" + unverified.getId()).with(asUser(unverified))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"displayName\": \"Renamed\"}"))
                .andExpect(status().isOk());
    }

    @Test
    void disabledAdminCanStillUseUserEndpoints() throws Exception {
        User disabledAdmin = createDisabledUser(UserRole.ADMIN);
        User target = createUser(UserRole.USER);

        mockMvc.perform(get("/api/v1/users/" + target.getId()).with(as(disabledAdmin, UserRole.ADMIN)))
                .andExpect(status().isOk());
    }

    @Test
    void activeUserWithoutAdminRoleCanReadTasks() throws Exception {
        User admin = createUser(UserRole.ADMIN);
        User user = createUser(UserRole.USER);
        UUID taskId = createTask(admin, null);

        mockMvc.perform(get(TASKS).with(asUser(user))).andExpect(status().isOk());
        mockMvc.perform(get(TASKS + "/" + taskId).with(asUser(user))).andExpect(status().isOk());
        mockMvc.perform(get(TASKS + "/" + taskId + "/events").with(asUser(user))).andExpect(status().isOk());
    }

    // --- Assignment rules ---

    @Test
    void createWithUnverifiedAssigneeIsRejected() throws Exception {
        User admin = createUser(UserRole.ADMIN);
        User unverified = createUnverifiedUser(UserRole.USER);

        assertCreateRejected(admin, unverified.getId(), "UNVERIFIED");
    }

    @Test
    void createWithDisabledAssigneeIsRejected() throws Exception {
        User admin = createUser(UserRole.ADMIN);
        User disabled = createDisabledUser(UserRole.USER);

        assertCreateRejected(admin, disabled.getId(), "DISABLED");
    }

    @Test
    void createWithDeletedAssigneeIsNotFound() throws Exception {
        User admin = createUser(UserRole.ADMIN);
        User deleted = createDeletedUser(UserRole.USER);
        String reference = uniqueReference();

        mockMvc.perform(post(TASKS).with(asAdmin(admin))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(createBody(reference, deleted.getId())))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.title").value("User not found"));

        assertThat(taskRepository.existsByReferenceIncludingDeleted(reference)).isFalse();
    }

    @Test
    void createWithActiveAssigneeSucceeds() throws Exception {
        User admin = createUser(UserRole.ADMIN);
        User assignee = createUser(UserRole.USER);

        mockMvc.perform(post(TASKS).with(asAdmin(admin))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(createBody(assignee.getId())))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.assignedTo.id").value(assignee.getId().toString()))
                .andExpect(jsonPath("$.assignedTo.status").value("ACTIVE"));
    }

    @Test
    void assignToUnverifiedUserIsRejected() throws Exception {
        User admin = createUser(UserRole.ADMIN);
        User unverified = createUnverifiedUser(UserRole.USER);

        assertAssignRejected(admin, unverified.getId(), "UNVERIFIED");
    }

    @Test
    void assignToDisabledUserIsRejected() throws Exception {
        User admin = createUser(UserRole.ADMIN);
        User disabled = createDisabledUser(UserRole.USER);

        assertAssignRejected(admin, disabled.getId(), "DISABLED");
    }

    @Test
    void assignToDeletedUserIsNotFound() throws Exception {
        User admin = createUser(UserRole.ADMIN);
        User assignee = createUser(UserRole.USER);
        User deleted = createDeletedUser(UserRole.USER);
        UUID taskId = createTask(admin, assignee);
        int eventsBefore = events(taskId).size();

        assign(admin, taskId, deleted.getId())
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.title").value("User not found"));

        assertThat(events(taskId)).hasSize(eventsBefore);
        getTask(admin, taskId).andExpect(jsonPath("$.assignedTo.id").value(assignee.getId().toString()));
    }

    @Test
    void assignToUnknownUserIsNotFound() throws Exception {
        User admin = createUser(UserRole.ADMIN);
        UUID taskId = createTask(admin, null);

        assign(admin, taskId, UUID.randomUUID())
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.title").value("User not found"));

        assertThat(events(taskId)).hasSize(1);
    }

    // --- Helpers ---

    private void assertCreateRejected(User admin, UUID assigneeId, String expectedStatus) throws Exception {
        String reference = uniqueReference();

        mockMvc.perform(post(TASKS).with(asAdmin(admin))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(createBody(reference, assigneeId)))
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.title").value("User cannot be assigned"))
                .andExpect(jsonPath("$.status").value(422))
                .andExpect(jsonPath("$.detail").value(containsString(expectedStatus)));

        assertThat(taskRepository.existsByReferenceIncludingDeleted(reference)).isFalse();
    }

    private void assertAssignRejected(User admin, UUID assigneeId, String expectedStatus) throws Exception {
        User previous = createUser(UserRole.USER);
        UUID taskId = createTask(admin, previous);
        int eventsBefore = events(taskId).size();

        assign(admin, taskId, assigneeId)
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.title").value("User cannot be assigned"))
                .andExpect(jsonPath("$.detail").value(containsString(expectedStatus)));

        assertThat(events(taskId)).hasSize(eventsBefore);
        getTask(admin, taskId)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.assignedTo.id").value(previous.getId().toString()))
                .andExpect(jsonPath("$.version").value(0));
    }

    /** Sends one request to every task and task-history endpoint and expects 403 each time. */
    private void assertForbiddenEverywhere(RequestPostProcessor auth, UUID taskId) throws Exception {
        String task = TASKS + "/" + taskId;
        User someone = createUser(UserRole.USER);

        List<MockHttpServletRequestBuilder> requests = List.of(
                get(TASKS),
                get(TASKS).param("archived", "true"),
                post(TASKS).contentType(MediaType.APPLICATION_JSON).content(createBody(null)),
                get(task),
                patch(task).contentType(MediaType.APPLICATION_JSON).content("{\"title\": \"Hacked\"}"),
                patch(task + "/status").contentType(MediaType.APPLICATION_JSON).content("{\"status\": \"DONE\"}"),
                patch(task + "/assign").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"userId\": \"" + someone.getId() + "\"}"),
                post(task + "/archive"),
                post(task + "/unarchive"),
                post(task + "/cancel").contentType(MediaType.APPLICATION_JSON).content("{\"reason\": \"No\"}"),
                delete(task),
                get(task + "/events")
        );

        for (MockHttpServletRequestBuilder request : requests) {
            mockMvc.perform(request.with(auth)).andExpect(status().isForbidden());
        }
    }

    /** The task still exists, unchanged, with only its CREATED event. */
    private void assertTaskUntouched(User admin, UUID taskId) throws Exception {
        getTask(admin, taskId)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.title").value("Task"))
                .andExpect(jsonPath("$.status").value("TO_DO"))
                .andExpect(jsonPath("$.archivedAt").isEmpty())
                .andExpect(jsonPath("$.assignedTo").isEmpty())
                .andExpect(jsonPath("$.version").value(0));

        assertThat(events(taskId)).hasSize(1);
    }

    private RequestPostProcessor jwtFor(UUID uid) {
        return jwt()
                .jwt(token -> token.claim("uid", uid.toString()))
                .authorities(new SimpleGrantedAuthority("ROLE_ADMIN"));
    }

    private String createBody(UUID assignedTo) {
        return createBody(uniqueReference(), assignedTo);
    }

    private String createBody(String reference, UUID assignedTo) {
        String assigned = assignedTo == null ? "null" : "\"" + assignedTo + "\"";

        return """
                {"reference": "%s", "title": "Task", "assignedTo": %s}
                """.formatted(reference, assigned);
    }

    private String login(User user) throws Exception {
        String body = mockMvc.perform(post("/api/v1/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\": \"%s\", \"password\": \"password\"}".formatted(user.getEmail())))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString();

        return json(body).get("accessToken").asString();
    }
}
