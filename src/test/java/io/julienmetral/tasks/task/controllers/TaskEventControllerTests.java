package io.julienmetral.tasks.task.controllers;

import io.julienmetral.tasks.identity.entities.User;
import io.julienmetral.tasks.identity.entities.UserRole;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import tools.jackson.databind.JsonNode;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.nullValue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The task event log: which event each mutation records (type, actor, payload), and the
 * {@code GET /api/v1/tasks/{taskId}/events} endpoint.
 */
class TaskEventControllerTests extends AbstractTaskApiTests {

    @Test
    void createRecordsCreatedEventByCreator() throws Exception {
        User admin = createUser(UserRole.ADMIN);
        UUID taskId = createTask(admin, null);

        mockMvc.perform(get(eventsOf(taskId)).with(asAdmin(admin)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content", hasSize(1)))
                .andExpect(jsonPath("$.content[0].type").value("CREATED"))
                .andExpect(jsonPath("$.content[0].id").isNotEmpty())
                .andExpect(jsonPath("$.content[0].occurredAt").isNotEmpty())
                .andExpect(jsonPath("$.content[0].actor.id").value(admin.getId().toString()))
                .andExpect(jsonPath("$.content[0].actor.displayName").value(admin.getDisplayName()))
                .andExpect(jsonPath("$.content[0].payload").value(nullValue()));
    }

    @Test
    void updateRecordsUpdatedEventByActor() throws Exception {
        User admin = createUser(UserRole.ADMIN);
        User editor = createUser(UserRole.ADMIN);
        UUID taskId = createTask(admin, null);

        mockMvc.perform(patch(TASKS + "/" + taskId).with(asAdmin(editor))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"title\": \"Renamed\"}"))
                .andExpect(status().isOk());

        JsonNode latest = latestEvent(admin, taskId);
        assertThat(latest.get("type").asString()).isEqualTo("UPDATED");
        assertThat(latest.get("actor").get("id").asString()).isEqualTo(editor.getId().toString());
        assertThat(latest.get("payload").isNull()).isTrue();
    }

    @Test
    void statusChangeRecordsFromAndTo() throws Exception {
        User admin = createUser(UserRole.ADMIN);
        User otherAdmin = createUser(UserRole.ADMIN);
        UUID taskId = createTask(admin, null);

        changeStatus(asAdmin(otherAdmin), taskId, "IN_PROGRESS");
        changeStatus(asAdmin(admin), taskId, "DONE");

        JsonNode page = eventsPage(admin, taskId, "");
        JsonNode content = page.get("content");
        assertThat(types(content)).containsExactly("STATUS_CHANGED", "STATUS_CHANGED", "CREATED");

        JsonNode done = content.get(0);
        assertThat(done.get("actor").get("id").asString()).isEqualTo(admin.getId().toString());
        assertThat(done.get("payload").get("from").asString()).isEqualTo("IN_PROGRESS");
        assertThat(done.get("payload").get("to").asString()).isEqualTo("DONE");

        JsonNode inProgress = content.get(1);
        assertThat(inProgress.get("actor").get("id").asString()).isEqualTo(otherAdmin.getId().toString());
        assertThat(inProgress.get("payload").get("from").asString()).isEqualTo("TO_DO");
        assertThat(inProgress.get("payload").get("to").asString()).isEqualTo("IN_PROGRESS");
    }

    @Test
    void assigneeIsRecordedAsActorOfTheirMutations() throws Exception {
        User admin = createUser(UserRole.ADMIN);
        User assignee = createUser(UserRole.USER);
        UUID taskId = createTask(admin, assignee);

        mockMvc.perform(patch(TASKS + "/" + taskId).with(asUser(assignee))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"title\": \"Renamed\"}"))
                .andExpect(status().isOk());
        changeStatus(asUser(assignee), taskId, "IN_PROGRESS");
        mockMvc.perform(post(TASKS + "/" + taskId + "/cancel").with(asUser(assignee))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"reason\": \"Blocked upstream\"}"))
                .andExpect(status().isOk());

        JsonNode content = eventsPage(admin, taskId, "").get("content");
        assertThat(types(content)).containsExactly("CANCELLED", "STATUS_CHANGED", "UPDATED", "CREATED");
        for (int i = 0; i < 3; i++) {
            assertThat(content.get(i).get("actor").get("id").asString()).isEqualTo(assignee.getId().toString());
        }
        assertThat(content.get(3).get("actor").get("id").asString()).isEqualTo(admin.getId().toString());
    }

    @Test
    void assignRecordsFromUserIdAndToUserId() throws Exception {
        User admin = createUser(UserRole.ADMIN);
        User first = createUser(UserRole.USER);
        User second = createUser(UserRole.USER);
        UUID taskId = createTask(admin, null);

        assign(admin, taskId, first.getId());
        assign(admin, taskId, second.getId());

        JsonNode content = eventsPage(admin, taskId, "").get("content");
        assertThat(types(content)).containsExactly("ASSIGNED", "ASSIGNED", "CREATED");

        JsonNode reassigned = content.get(0).get("payload");
        assertThat(reassigned.get("fromUserId").asString()).isEqualTo(first.getId().toString());
        assertThat(reassigned.get("toUserId").asString()).isEqualTo(second.getId().toString());
        assertThat(content.get(0).get("actor").get("id").asString()).isEqualTo(admin.getId().toString());

        JsonNode firstAssignment = content.get(1).get("payload");
        assertThat(firstAssignment.get("fromUserId").isNull()).isTrue();
        assertThat(firstAssignment.get("toUserId").asString()).isEqualTo(first.getId().toString());
    }

    @Test
    void reassigningToSameUserRecordsNoEvent() throws Exception {
        User admin = createUser(UserRole.ADMIN);
        User assignee = createUser(UserRole.USER);
        UUID taskId = createTask(admin, assignee);

        assign(admin, taskId, assignee.getId())
                .andExpect(jsonPath("$.assignedTo.id").value(assignee.getId().toString()))
                .andExpect(jsonPath("$.version").value(0));

        assertThat(types(eventsPage(admin, taskId, "").get("content"))).containsExactly("CREATED");
    }

    @Test
    void cancelRecordsReasonAndPreviousStatus() throws Exception {
        User admin = createUser(UserRole.ADMIN);
        UUID taskId = createTask(admin, null);

        changeStatus(asAdmin(admin), taskId, "BLOCKED");
        mockMvc.perform(post(TASKS + "/" + taskId + "/cancel").with(asAdmin(admin))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"reason\": \"Duplicate of another task\"}"))
                .andExpect(status().isOk());

        JsonNode latest = latestEvent(admin, taskId);
        assertThat(latest.get("type").asString()).isEqualTo("CANCELLED");
        assertThat(latest.get("actor").get("id").asString()).isEqualTo(admin.getId().toString());
        assertThat(latest.get("payload").get("reason").asString()).isEqualTo("Duplicate of another task");
        assertThat(latest.get("payload").get("from").asString()).isEqualTo("BLOCKED");
        assertThat(latest.get("payload").get("to").asString()).isEqualTo("CANCELLED");
    }

    @Test
    void archiveAndUnarchiveRecordEvents() throws Exception {
        User admin = createUser(UserRole.ADMIN);
        UUID taskId = createTask(admin, null);

        mockMvc.perform(post(TASKS + "/" + taskId + "/archive").with(asAdmin(admin))).andExpect(status().isOk());
        mockMvc.perform(post(TASKS + "/" + taskId + "/archive").with(asAdmin(admin))).andExpect(status().isOk());
        mockMvc.perform(post(TASKS + "/" + taskId + "/unarchive").with(asAdmin(admin))).andExpect(status().isOk());

        JsonNode content = eventsPage(admin, taskId, "").get("content");
        assertThat(types(content)).containsExactly("UNARCHIVED", "ARCHIVED", "CREATED");
        assertThat(content.get(0).get("actor").get("id").asString()).isEqualTo(admin.getId().toString());
        assertThat(content.get(0).get("payload").isNull()).isTrue();
    }

    @Test
    void sameStatusRecordsNoEvent() throws Exception {
        User admin = createUser(UserRole.ADMIN);
        UUID taskId = createTask(admin, null);

        changeStatus(asAdmin(admin), taskId, "TO_DO");

        assertThat(types(eventsPage(admin, taskId, "").get("content"))).containsExactly("CREATED");
    }

    @Test
    void forbiddenMutationRecordsNoEvent() throws Exception {
        User admin = createUser(UserRole.ADMIN);
        User stranger = createUser(UserRole.USER);
        UUID taskId = createTask(admin, null);

        mockMvc.perform(patch(TASKS + "/" + taskId + "/status").with(asUser(stranger))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"status\": \"DONE\"}"))
                .andExpect(status().isForbidden());

        assertThat(types(eventsPage(admin, taskId, "").get("content"))).containsExactly("CREATED");
    }

    @Test
    void eventsAreOrderedNewestFirstAndPaginated() throws Exception {
        User admin = createUser(UserRole.ADMIN);
        UUID taskId = createTask(admin, null);

        // CREATED + 4 STATUS_CHANGED = 5 events
        for (String s : List.of("IN_PROGRESS", "IN_REVIEW", "DONE", "TO_DO")) {
            changeStatus(asAdmin(admin), taskId, s);
        }

        JsonNode all = eventsPage(admin, taskId, "").get("content");
        assertThat(all).hasSize(5);
        List<Instant> occurred = new ArrayList<>();
        all.forEach(e -> occurred.add(Instant.parse(e.get("occurredAt").asString())));
        assertThat(occurred).isSortedAccordingTo((a, b) -> b.compareTo(a));
        assertThat(all.get(0).get("payload").get("to").asString()).isEqualTo("TO_DO");
        assertThat(all.get(4).get("type").asString()).isEqualTo("CREATED");

        JsonNode page0 = eventsPage(admin, taskId, "?page=0&size=2");
        JsonNode page1 = eventsPage(admin, taskId, "?page=1&size=2");
        JsonNode page2 = eventsPage(admin, taskId, "?page=2&size=2");

        assertThat(page0.get("content")).hasSize(2);
        assertThat(page1.get("content")).hasSize(2);
        assertThat(page2.get("content")).hasSize(1);
        assertThat(page0.get("content").get(0).get("id").asString()).isEqualTo(all.get(0).get("id").asString());
        assertThat(page1.get("content").get(0).get("id").asString()).isEqualTo(all.get(2).get("id").asString());
        assertThat(page2.get("content").get(0).get("type").asString()).isEqualTo("CREATED");

        JsonNode meta = pageMetadata(page0);
        assertThat(meta.get("totalElements").asLong()).isEqualTo(5);
        assertThat(meta.get("totalPages").asInt()).isEqualTo(3);
        assertThat(meta.get("size").asInt()).isEqualTo(2);
        assertThat(meta.get("number").asInt()).isEqualTo(0);
        assertThat(pageMetadata(page2).get("number").asInt()).isEqualTo(2);
    }

    @Test
    void pageBeyondLastIsEmpty() throws Exception {
        User admin = createUser(UserRole.ADMIN);
        UUID taskId = createTask(admin, null);

        mockMvc.perform(get(eventsOf(taskId) + "?page=5&size=10").with(asAdmin(admin)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content", hasSize(0)));
    }

    @Test
    void eventsAreScopedToTheirTask() throws Exception {
        User admin = createUser(UserRole.ADMIN);
        UUID taskA = createTask(admin, null);
        UUID taskB = createTask(admin, null);

        changeStatus(asAdmin(admin), taskA, "DONE");

        assertThat(types(eventsPage(admin, taskB, "").get("content"))).containsExactly("CREATED");
    }

    @Test
    void anyAuthenticatedUserCanReadEvents() throws Exception {
        User admin = createUser(UserRole.ADMIN);
        UUID taskId = createTask(admin, null);

        mockMvc.perform(get(eventsOf(taskId)).with(asUser(createUser(UserRole.USER))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content", hasSize(1)));
    }

    @Test
    void unknownTaskReturns404() throws Exception {
        mockMvc.perform(get(eventsOf(UUID.randomUUID())).with(asUser(createUser(UserRole.USER))))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.title").value("Task not found"));
    }

    @Test
    void withoutTokenReturns401() throws Exception {
        User admin = createUser(UserRole.ADMIN);
        UUID taskId = createTask(admin, null);

        mockMvc.perform(get(eventsOf(taskId)))
                .andExpect(status().isUnauthorized());
    }

    // ---------- helpers ----------

    private String eventsOf(UUID taskId) {
        return TASKS + "/" + taskId + "/events";
    }

    private JsonNode eventsPage(User viewer, UUID taskId, String query) throws Exception {
        return json(mockMvc.perform(get(eventsOf(taskId) + query).with(asAdmin(viewer)))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString());
    }

    private JsonNode latestEvent(User viewer, UUID taskId) throws Exception {
        return eventsPage(viewer, taskId, "").get("content").get(0);
    }

    /** Page metadata, whether Spring Data serializes the page directly or via PagedModel. */
    private JsonNode pageMetadata(JsonNode page) {
        return page.has("page") ? page.get("page") : page;
    }

    private List<String> types(JsonNode content) {
        List<String> types = new ArrayList<>();
        content.forEach(e -> types.add(e.get("type").asString()));
        return types;
    }

    private void changeStatus(RequestPostProcessor auth, UUID taskId,
                              String status) throws Exception {
        mockMvc.perform(patch(TASKS + "/" + taskId + "/status").with(auth)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"status\": \"%s\"}".formatted(status)))
                .andExpect(status().isOk());
    }

    private ResultActions assign(User admin, UUID taskId, UUID userId)
            throws Exception {
        return mockMvc.perform(patch(TASKS + "/" + taskId + "/assign").with(asAdmin(admin))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"userId\": \"%s\"}".formatted(userId)))
                .andExpect(status().isOk());
    }
}
