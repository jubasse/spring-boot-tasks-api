package io.julienmetral.tasks.task.controllers;

import io.julienmetral.tasks.identity.entities.User;
import io.julienmetral.tasks.identity.entities.UserRole;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import tools.jackson.databind.JsonNode;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * {@code GET /api/v1/tasks}: pagination, default sort, and the {@code status}, {@code assigneeId}
 * and {@code archived} filters. The database is shared with other test classes, so every
 * assertion is scoped to an assignee created by the test, or checks containment.
 */
class TaskListApiTests extends AbstractUserStateTaskApiTests {

    @Test
    void listIsPaginatedAndSortedByCreationDateNewestFirst() throws Exception {
        User admin = createUser(UserRole.ADMIN);
        User assignee = createUser(UserRole.USER);
        UUID first = createTask(admin, assignee);
        UUID second = createTask(admin, assignee);
        UUID third = createTask(admin, assignee);

        JsonNode page = list(admin, get(TASKS).param("assigneeId", assignee.getId().toString()));

        assertThat(ids(page)).containsExactly(third, second, first);
        assertThat(totalElements(page)).isEqualTo(3);
        assertThat(page.get("size").asInt()).isEqualTo(10);
        assertThat(page.get("number").asInt()).isZero();

        JsonNode firstPage = list(admin, get(TASKS)
                .param("assigneeId", assignee.getId().toString())
                .param("size", "2"));
        assertThat(ids(firstPage)).containsExactly(third, second);
        assertThat(totalElements(firstPage)).isEqualTo(3);

        JsonNode secondPage = list(admin, get(TASKS)
                .param("assigneeId", assignee.getId().toString())
                .param("size", "2")
                .param("page", "1"));
        assertThat(ids(secondPage)).containsExactly(first);
        assertThat(secondPage.get("totalPages").asInt()).isEqualTo(2);
    }

    @Test
    void explicitSortOverridesTheDefault() throws Exception {
        User admin = createUser(UserRole.ADMIN);
        User assignee = createUser(UserRole.USER);
        UUID first = createTask(admin, assignee);
        UUID second = createTask(admin, assignee);

        JsonNode page = list(admin, get(TASKS)
                .param("assigneeId", assignee.getId().toString())
                .param("sort", "createdAt,asc"));

        assertThat(ids(page)).containsExactly(first, second);
    }

    @Test
    void listReturnsFullTaskResponses() throws Exception {
        User admin = createUser(UserRole.ADMIN);
        User assignee = createUser(UserRole.USER);
        UUID taskId = createTask(admin, assignee);

        JsonNode task = list(admin, get(TASKS).param("assigneeId", assignee.getId().toString()))
                .get("content").get(0);

        assertThat(task.get("id").asString()).isEqualTo(taskId.toString());
        assertThat(task.get("title").asString()).isEqualTo("Task");
        assertThat(task.get("status").asString()).isEqualTo("TO_DO");
        assertThat(task.get("assignedTo").get("id").asString()).isEqualTo(assignee.getId().toString());
        assertThat(task.get("assignedTo").get("displayName").asString()).isEqualTo(assignee.getDisplayName());
        assertThat(task.get("assignedTo").get("status").asString()).isEqualTo("ACTIVE");
        assertThat(task.get("createdBy").get("id").asString()).isEqualTo(admin.getId().toString());
        assertThat(task.get("createdBy").get("status").asString()).isEqualTo("ACTIVE");
    }

    @Test
    void filterByAssigneeExcludesOtherTasks() throws Exception {
        User admin = createUser(UserRole.ADMIN);
        User assignee = createUser(UserRole.USER);
        User other = createUser(UserRole.USER);
        UUID mine = createTask(admin, assignee);
        UUID theirs = createTask(admin, other);
        UUID unassigned = createTask(admin, null);

        JsonNode page = list(admin, get(TASKS).param("assigneeId", assignee.getId().toString()));

        assertThat(ids(page)).containsExactly(mine);
        assertThat(ids(page)).doesNotContain(theirs, unassigned);
    }

    @Test
    void filterByUnknownAssigneeReturnsAnEmptyPage() throws Exception {
        User admin = createUser(UserRole.ADMIN);

        JsonNode page = list(admin, get(TASKS).param("assigneeId", UUID.randomUUID().toString()));

        assertThat(ids(page)).isEmpty();
        assertThat(totalElements(page)).isZero();
    }

    @Test
    void filterByStatus() throws Exception {
        User admin = createUser(UserRole.ADMIN);
        User assignee = createUser(UserRole.USER);
        UUID todo = createTask(admin, assignee);
        UUID inProgress = createTask(admin, assignee);
        changeStatus(admin, inProgress, "IN_PROGRESS");

        String assigneeId = assignee.getId().toString();

        assertThat(ids(list(admin, get(TASKS).param("assigneeId", assigneeId).param("status", "IN_PROGRESS"))))
                .containsExactly(inProgress);
        assertThat(ids(list(admin, get(TASKS).param("assigneeId", assigneeId).param("status", "TO_DO"))))
                .containsExactly(todo);
        assertThat(ids(list(admin, get(TASKS).param("assigneeId", assigneeId).param("status", "DONE"))))
                .isEmpty();
    }

    @Test
    void statusFilterWorksWithoutAssigneeFilter() throws Exception {
        User admin = createUser(UserRole.ADMIN);
        UUID inProgress = createTask(admin, null);
        UUID todo = createTask(admin, null);
        changeStatus(admin, inProgress, "IN_PROGRESS");

        JsonNode page = list(admin, get(TASKS).param("status", "IN_PROGRESS").param("size", "1000"));

        assertThat(ids(page)).contains(inProgress).doesNotContain(todo);
        for (JsonNode task : page.get("content")) {
            assertThat(task.get("status").asString()).isEqualTo("IN_PROGRESS");
        }
    }

    @Test
    void archivedTasksAreHiddenByDefaultAndListedWithArchivedTrue() throws Exception {
        User admin = createUser(UserRole.ADMIN);
        User assignee = createUser(UserRole.USER);
        UUID active = createTask(admin, assignee);
        UUID archived = createTask(admin, assignee);

        mockMvc.perform(post(TASKS + "/" + archived + "/archive").with(asAdmin(admin)))
                .andExpect(status().isOk());

        String assigneeId = assignee.getId().toString();

        assertThat(ids(list(admin, get(TASKS).param("assigneeId", assigneeId))))
                .containsExactly(active);
        assertThat(ids(list(admin, get(TASKS).param("assigneeId", assigneeId).param("archived", "false"))))
                .containsExactly(active);
        assertThat(ids(list(admin, get(TASKS).param("assigneeId", assigneeId).param("archived", "true"))))
                .containsExactly(archived);
    }

    @Test
    void unarchivedTaskComesBackInTheDefaultList() throws Exception {
        User admin = createUser(UserRole.ADMIN);
        User assignee = createUser(UserRole.USER);
        UUID taskId = createTask(admin, assignee);

        mockMvc.perform(post(TASKS + "/" + taskId + "/archive").with(asAdmin(admin))).andExpect(status().isOk());
        mockMvc.perform(post(TASKS + "/" + taskId + "/unarchive").with(asAdmin(admin))).andExpect(status().isOk());

        assertThat(ids(list(admin, get(TASKS).param("assigneeId", assignee.getId().toString()))))
                .containsExactly(taskId);
    }

    @Test
    void archivedTrueOnlyListsArchivedTasks() throws Exception {
        User admin = createUser(UserRole.ADMIN);
        UUID archived = createTask(admin, null);
        mockMvc.perform(post(TASKS + "/" + archived + "/archive").with(asAdmin(admin))).andExpect(status().isOk());

        JsonNode page = list(admin, get(TASKS).param("archived", "true").param("size", "1000"));

        assertThat(ids(page)).contains(archived);
        for (JsonNode task : page.get("content")) {
            assertThat(task.get("archivedAt").isNull()).isFalse();
        }
    }

    @Test
    void filtersCombine() throws Exception {
        User admin = createUser(UserRole.ADMIN);
        User assignee = createUser(UserRole.USER);
        UUID archivedDone = createTask(admin, assignee);
        UUID archivedTodo = createTask(admin, assignee);
        UUID activeDone = createTask(admin, assignee);
        changeStatus(admin, archivedDone, "DONE");
        changeStatus(admin, activeDone, "DONE");
        mockMvc.perform(post(TASKS + "/" + archivedDone + "/archive").with(asAdmin(admin))).andExpect(status().isOk());
        mockMvc.perform(post(TASKS + "/" + archivedTodo + "/archive").with(asAdmin(admin))).andExpect(status().isOk());

        JsonNode page = list(admin, get(TASKS)
                .param("assigneeId", assignee.getId().toString())
                .param("status", "DONE")
                .param("archived", "true"));

        assertThat(ids(page)).containsExactly(archivedDone);
    }

    @Test
    void softDeletedTasksAreNotListed() throws Exception {
        User admin = createUser(UserRole.ADMIN);
        User assignee = createUser(UserRole.USER);
        UUID kept = createTask(admin, assignee);
        UUID deleted = createTask(admin, assignee);

        mockMvc.perform(delete(TASKS + "/" + deleted).with(asAdmin(admin))).andExpect(status().isNoContent());

        assertThat(ids(list(admin, get(TASKS).param("assigneeId", assignee.getId().toString()))))
                .containsExactly(kept);
    }

    @Test
    void filterByDeletedAssigneeStillFindsTheirTasks() throws Exception {
        User admin = createUser(UserRole.ADMIN);
        User assignee = createUser(UserRole.USER);
        UUID taskId = createTask(admin, assignee);

        deleteThroughApi(assignee);

        JsonNode page = list(admin, get(TASKS).param("assigneeId", assignee.getId().toString()));

        assertThat(ids(page)).containsExactly(taskId);
        JsonNode assignedTo = page.get("content").get(0).get("assignedTo");
        assertThat(assignedTo.get("id").asString()).isEqualTo(assignee.getId().toString());
        assertThat(assignedTo.get("displayName").asString()).isEqualTo(assignee.getDisplayName());
        assertThat(assignedTo.get("status").asString()).isEqualTo("DELETED");
    }

    @Test
    void unfilteredListContainsNewTasks() throws Exception {
        User admin = createUser(UserRole.ADMIN);
        UUID taskId = createTask(admin, null);

        // Newest first, so a task just created is on the first page
        JsonNode page = list(admin, get(TASKS).param("size", "50"));

        assertThat(ids(page)).contains(taskId);
    }

    @Test
    void invalidStatusIsBadRequest() throws Exception {
        User admin = createUser(UserRole.ADMIN);

        mockMvc.perform(get(TASKS).param("status", "NOT_A_STATUS").with(asAdmin(admin)))
                .andExpect(status().isBadRequest());
    }

    @Test
    void invalidAssigneeIdIsBadRequest() throws Exception {
        User admin = createUser(UserRole.ADMIN);

        mockMvc.perform(get(TASKS).param("assigneeId", "not-a-uuid").with(asAdmin(admin)))
                .andExpect(status().isBadRequest());
    }

    @Test
    void invalidArchivedFlagIsBadRequest() throws Exception {
        User admin = createUser(UserRole.ADMIN);

        mockMvc.perform(get(TASKS).param("archived", "maybe").with(asAdmin(admin)))
                .andExpect(status().isBadRequest());
    }

    private JsonNode list(User reader, MockHttpServletRequestBuilder request) throws Exception {
        String body = mockMvc.perform(request.with(asAdmin(reader)))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString();

        return json(body);
    }

    private void changeStatus(User admin, UUID taskId, String status) throws Exception {
        mockMvc.perform(patch(TASKS + "/" + taskId + "/status").with(asAdmin(admin))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"status\": \"" + status + "\"}"))
                .andExpect(status().isOk());
    }

    private static List<UUID> ids(JsonNode page) {
        List<UUID> ids = new ArrayList<>();
        for (JsonNode task : page.get("content")) {
            ids.add(UUID.fromString(task.get("id").asString()));
        }
        return ids;
    }

    private static long totalElements(JsonNode page) {
        return page.get("totalElements").asLong();
    }
}
