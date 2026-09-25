package io.julienmetral.tasks.task.controllers;

import io.julienmetral.tasks.identity.entities.User;
import io.julienmetral.tasks.identity.entities.UserRole;
import io.julienmetral.tasks.task.entities.TaskEventType;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.ResultActions;
import tools.jackson.databind.JsonNode;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;
import static org.hamcrest.Matchers.nullValue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Behaviour of the task endpoints for authorized callers: reads, creation errors, and the
 * state each mutation leaves the task in.
 */
class TaskLifecycleControllerTests extends AbstractTaskApiTests {

    // ---------- GET /{id} ----------

    @Test
    void findByIdReturnsTask() throws Exception {
        User admin = createUser(UserRole.ADMIN);
        User assignee = createUser(UserRole.USER);
        UUID taskId = createTask(admin, assignee);

        mockMvc.perform(get(TASKS + "/" + taskId).with(asUser(createUser(UserRole.USER))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(taskId.toString()))
                .andExpect(jsonPath("$.title").value("Task"))
                .andExpect(jsonPath("$.description").value("Initial description"))
                .andExpect(jsonPath("$.status").value("TO_DO"))
                .andExpect(jsonPath("$.priority").value("LOW"))
                .andExpect(jsonPath("$.dueAt").value("2030-01-01T10:00:00Z"))
                .andExpect(jsonPath("$.version").value(0))
                .andExpect(jsonPath("$.assignedTo.id").value(assignee.getId().toString()))
                .andExpect(jsonPath("$.assignedTo.displayName").value(assignee.getDisplayName()))
                .andExpect(jsonPath("$.createdBy.id").value(admin.getId().toString()))
                .andExpect(jsonPath("$.completedAt").value(nullValue()))
                .andExpect(jsonPath("$.archivedAt").value(nullValue()))
                .andExpect(jsonPath("$.cancelledAt").value(nullValue()));
    }

    @Test
    void findByIdReturns404ForUnknownTask() throws Exception {
        mockMvc.perform(get(TASKS + "/" + UUID.randomUUID()).with(asUser(createUser(UserRole.USER))))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.title").value("Task not found"))
                .andExpect(jsonPath("$.status").value(404));
    }

    @Test
    void findByIdReturns400ForMalformedId() throws Exception {
        mockMvc.perform(get(TASKS + "/not-a-uuid").with(asUser(createUser(UserRole.USER))))
                .andExpect(status().isBadRequest());
    }

    // ---------- POST create ----------

    @Test
    void createWithDuplicateReferenceReturns409() throws Exception {
        User user = createUser(UserRole.USER);
        String reference = uniqueReference();
        String body = """
                {"reference": "%s", "title": "Task"}
                """.formatted(reference);

        mockMvc.perform(post(TASKS).with(asUser(user)).contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isCreated());

        mockMvc.perform(post(TASKS).with(asUser(user)).contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.title").value("Task reference already exists"));
    }

    @Test
    void createWithDuplicateReferenceAfterTrimmingReturns409() throws Exception {
        User user = createUser(UserRole.USER);
        String reference = uniqueReference();

        mockMvc.perform(post(TASKS).with(asUser(user)).contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"reference": "%s", "title": "Task"}
                                """.formatted(reference)))
                .andExpect(status().isCreated());

        mockMvc.perform(post(TASKS).with(asUser(user)).contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"reference": "  %s  ", "title": "Task"}
                                """.formatted(reference)))
                .andExpect(status().isConflict());
    }

    @Test
    void createWithBlankReferenceReturns400() throws Exception {
        mockMvc.perform(post(TASKS).with(asUser(createUser(UserRole.USER))).contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"reference": "   ", "title": "Task"}
                                """))
                .andExpect(status().isBadRequest());
    }

    @Test
    void createWithMissingReferenceReturns400() throws Exception {
        mockMvc.perform(post(TASKS).with(asUser(createUser(UserRole.USER))).contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"title": "Task"}
                                """))
                .andExpect(status().isBadRequest());
    }

    @Test
    void createWithBlankTitleReturns400() throws Exception {
        mockMvc.perform(post(TASKS).with(asUser(createUser(UserRole.USER))).contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"reference": "%s", "title": "  "}
                                """.formatted(uniqueReference())))
                .andExpect(status().isBadRequest());
    }

    @Test
    void createWithReferenceLongerThan30CharsReturns400() throws Exception {
        mockMvc.perform(post(TASKS).with(asUser(createUser(UserRole.USER))).contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"reference": "%s", "title": "Task"}
                                """.formatted("R".repeat(31))))
                .andExpect(status().isBadRequest());
    }

    @Test
    void createWithReferenceOfExactly30CharsIsAccepted() throws Exception {
        String reference = ("R-" + UUID.randomUUID().toString().replace("-", "")).substring(0, 30);

        mockMvc.perform(post(TASKS).with(asUser(createUser(UserRole.USER))).contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"reference": "%s", "title": "Task"}
                                """.formatted(reference)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.reference").value(reference));
    }

    @Test
    void createWithTitleLongerThan255CharsReturns400() throws Exception {
        mockMvc.perform(post(TASKS).with(asUser(createUser(UserRole.USER))).contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"reference": "%s", "title": "%s"}
                                """.formatted(uniqueReference(), "t".repeat(256))))
                .andExpect(status().isBadRequest());
    }

    @Test
    void createWithUnknownPriorityReturns400() throws Exception {
        mockMvc.perform(post(TASKS).with(asUser(createUser(UserRole.USER))).contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"reference": "%s", "title": "Task", "priority": "CRITICAL"}
                                """.formatted(uniqueReference())))
                .andExpect(status().isBadRequest());
    }

    @Test
    void createSetsLocationHeaderAndDefaults() throws Exception {
        User user = createUser(UserRole.USER);

        var response = mockMvc.perform(post(TASKS).with(asUser(user)).contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"reference": "%s", "title": "Task", "description": "   "}
                                """.formatted(uniqueReference())))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("TO_DO"))
                .andExpect(jsonPath("$.priority").value("MEDIUM"))
                .andExpect(jsonPath("$.description").value(nullValue()))
                .andExpect(jsonPath("$.assignedTo").value(nullValue()))
                .andReturn()
                .getResponse();

        String id = json(response.getContentAsString()).get("id").asString();
        assertThat(response.getHeader("Location")).isEqualTo(TASKS + "/" + id);
    }

    @Test
    void adminAssigningToUnknownUserOnCreationReturns404() throws Exception {
        String reference = uniqueReference();

        mockMvc.perform(post(TASKS).with(asAdmin(createUser(UserRole.ADMIN))).contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"reference": "%s", "title": "Task", "assignedTo": "%s"}
                                """.formatted(reference, UUID.randomUUID())))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.title").value("User not found"));

        // Nothing was persisted: the same reference is still free
        mockMvc.perform(post(TASKS).with(asUser(createUser(UserRole.USER))).contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"reference": "%s", "title": "Task"}
                                """.formatted(reference)))
                .andExpect(status().isCreated());
    }

    // ---------- PATCH /{id} (update) ----------

    @Test
    void partialUpdateChangesOnlyProvidedFields() throws Exception {
        User admin = createUser(UserRole.ADMIN);
        UUID taskId = createTask(admin, null);

        mockMvc.perform(patch(TASKS + "/" + taskId).with(asAdmin(admin)).contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"title": "  New title  "}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.title").value("New title"))
                .andExpect(jsonPath("$.description").value("Initial description"))
                .andExpect(jsonPath("$.priority").value("LOW"))
                .andExpect(jsonPath("$.dueAt").value("2030-01-01T10:00:00Z"))
                .andExpect(jsonPath("$.version").value(1));

        mockMvc.perform(patch(TASKS + "/" + taskId).with(asAdmin(admin)).contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"description": "Other", "priority": "URGENT", "dueAt": "2031-06-15T08:30:00Z"}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.title").value("New title"))
                .andExpect(jsonPath("$.description").value("Other"))
                .andExpect(jsonPath("$.priority").value("URGENT"))
                .andExpect(jsonPath("$.dueAt").value("2031-06-15T08:30:00Z"))
                .andExpect(jsonPath("$.version").value(2));

        // Persisted, not just echoed back
        mockMvc.perform(get(TASKS + "/" + taskId).with(asAdmin(admin)))
                .andExpect(jsonPath("$.title").value("New title"))
                .andExpect(jsonPath("$.priority").value("URGENT"));
    }

    @Test
    void updateWithBlankTitleLeavesTitleUnchanged() throws Exception {
        User admin = createUser(UserRole.ADMIN);
        UUID taskId = createTask(admin, null);

        mockMvc.perform(patch(TASKS + "/" + taskId).with(asAdmin(admin)).contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"title": "   "}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.title").value("Task"));
    }

    @Test
    void updateWithTitleLongerThan255CharsReturns400() throws Exception {
        User admin = createUser(UserRole.ADMIN);
        UUID taskId = createTask(admin, null);

        mockMvc.perform(patch(TASKS + "/" + taskId).with(asAdmin(admin)).contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"title": "%s"}
                                """.formatted("t".repeat(256))))
                .andExpect(status().isBadRequest());
    }

    @Test
    void updateUnknownTaskAsAdminReturns404() throws Exception {
        mockMvc.perform(patch(TASKS + "/" + UUID.randomUUID()).with(asAdmin(createUser(UserRole.ADMIN)))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"title": "x"}
                                """))
                .andExpect(status().isNotFound());
    }

    // ---------- PATCH /{id}/status ----------

    @Test
    void statusDoneSetsCompletedAtAndBackToTodoClearsIt() throws Exception {
        User admin = createUser(UserRole.ADMIN);
        UUID taskId = createTask(admin, null);

        changeStatus(admin, taskId, "IN_PROGRESS")
                .andExpect(jsonPath("$.status").value("IN_PROGRESS"))
                .andExpect(jsonPath("$.completedAt").value(nullValue()));

        changeStatus(admin, taskId, "DONE")
                .andExpect(jsonPath("$.status").value("DONE"))
                .andExpect(jsonPath("$.completedAt").isNotEmpty())
                .andExpect(jsonPath("$.cancelledAt").value(nullValue()));

        changeStatus(admin, taskId, "TO_DO")
                .andExpect(jsonPath("$.status").value("TO_DO"))
                .andExpect(jsonPath("$.completedAt").value(nullValue()))
                .andExpect(jsonPath("$.cancelledAt").value(nullValue()));
    }

    @Test
    void statusCancelledSetsCancelledAtAndBackToTodoClearsIt() throws Exception {
        User admin = createUser(UserRole.ADMIN);
        UUID taskId = createTask(admin, null);

        changeStatus(admin, taskId, "DONE");

        changeStatus(admin, taskId, "CANCELLED")
                .andExpect(jsonPath("$.status").value("CANCELLED"))
                .andExpect(jsonPath("$.cancelledAt").isNotEmpty())
                .andExpect(jsonPath("$.completedAt").value(nullValue()));

        changeStatus(admin, taskId, "TO_DO")
                .andExpect(jsonPath("$.cancelledAt").value(nullValue()))
                .andExpect(jsonPath("$.completedAt").value(nullValue()));
    }

    @Test
    void statusDoneAfterCancelClearsCancellationFields() throws Exception {
        User admin = createUser(UserRole.ADMIN);
        UUID taskId = createTask(admin, null);

        cancel(admin, taskId, "No longer needed");

        changeStatus(admin, taskId, "DONE")
                .andExpect(jsonPath("$.completedAt").isNotEmpty())
                .andExpect(jsonPath("$.cancelledAt").value(nullValue()))
                .andExpect(jsonPath("$.cancelledReason").value(nullValue()));
    }

    @Test
    void sameStatusIsANoOpAndRecordsNoEvent() throws Exception {
        User admin = createUser(UserRole.ADMIN);
        UUID taskId = createTask(admin, null);

        changeStatus(admin, taskId, "DONE");
        String completedAt = json(changeStatus(admin, taskId, "DONE")
                .andReturn().getResponse().getContentAsString()).get("completedAt").asString();

        JsonNode again = json(changeStatus(admin, taskId, "DONE")
                .andExpect(jsonPath("$.version").value(1))
                .andReturn().getResponse().getContentAsString());

        assertThat(again.get("completedAt").asString()).isEqualTo(completedAt);
        assertThat(events(taskId))
                .extracting(e -> e.getType())
                .containsExactly(TaskEventType.STATUS_CHANGED, TaskEventType.CREATED);
    }

    @Test
    void statusWithInvalidValueReturns400() throws Exception {
        User admin = createUser(UserRole.ADMIN);
        UUID taskId = createTask(admin, null);

        mockMvc.perform(patch(TASKS + "/" + taskId + "/status").with(asAdmin(admin))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"status": "FINISHED"}
                                """))
                .andExpect(status().isBadRequest());

        mockMvc.perform(patch(TASKS + "/" + taskId + "/status").with(asAdmin(admin))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void statusOfUnknownTaskAsAdminReturns404() throws Exception {
        changeStatusRaw(createUser(UserRole.ADMIN), UUID.randomUUID(), "DONE")
                .andExpect(status().isNotFound());
    }

    // ---------- POST /{id}/cancel ----------

    @Test
    void cancelStoresReasonAndCancelledAt() throws Exception {
        User admin = createUser(UserRole.ADMIN);
        UUID taskId = createTask(admin, null);

        changeStatus(admin, taskId, "DONE");

        cancel(admin, taskId, "Out of scope")
                .andExpect(jsonPath("$.status").value("CANCELLED"))
                .andExpect(jsonPath("$.cancelledReason").value("Out of scope"))
                .andExpect(jsonPath("$.cancelledAt").isNotEmpty())
                .andExpect(jsonPath("$.completedAt").value(nullValue()));

        mockMvc.perform(get(TASKS + "/" + taskId).with(asAdmin(admin)))
                .andExpect(jsonPath("$.status").value("CANCELLED"))
                .andExpect(jsonPath("$.cancelledAt").isNotEmpty());
    }

    @Test
    void cancelWithBlankOrTooLongReasonReturns400() throws Exception {
        User admin = createUser(UserRole.ADMIN);
        UUID taskId = createTask(admin, null);

        mockMvc.perform(post(TASKS + "/" + taskId + "/cancel").with(asAdmin(admin))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"reason": "  "}
                                """))
                .andExpect(status().isBadRequest());

        mockMvc.perform(post(TASKS + "/" + taskId + "/cancel").with(asAdmin(admin))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"reason": "%s"}
                                """.formatted("r".repeat(501))))
                .andExpect(status().isBadRequest());

        mockMvc.perform(get(TASKS + "/" + taskId).with(asAdmin(admin)))
                .andExpect(jsonPath("$.status").value("TO_DO"));
    }

    @Test
    void cancelUnknownTaskAsAdminReturns404() throws Exception {
        mockMvc.perform(post(TASKS + "/" + UUID.randomUUID() + "/cancel").with(asAdmin(createUser(UserRole.ADMIN)))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"reason": "x"}
                                """))
                .andExpect(status().isNotFound());
    }

    // ---------- PATCH /{id}/assign ----------

    @Test
    void adminCanAssignAndReassign() throws Exception {
        User admin = createUser(UserRole.ADMIN);
        User first = createUser(UserRole.USER);
        User second = createUser(UserRole.USER);
        UUID taskId = createTask(admin, null);

        assign(admin, taskId, first.getId())
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.assignedTo.id").value(first.getId().toString()));

        assign(admin, taskId, second.getId())
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.assignedTo.id").value(second.getId().toString()));

        mockMvc.perform(get(TASKS + "/" + taskId).with(asAdmin(admin)))
                .andExpect(jsonPath("$.assignedTo.id").value(second.getId().toString()));
    }

    @Test
    void assignToUnknownUserReturns404() throws Exception {
        User admin = createUser(UserRole.ADMIN);
        UUID taskId = createTask(admin, null);

        assign(admin, taskId, UUID.randomUUID())
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.title").value("User not found"));
    }

    @Test
    void assignUnknownTaskReturns404() throws Exception {
        User admin = createUser(UserRole.ADMIN);

        assign(admin, UUID.randomUUID(), admin.getId())
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.title").value("Task not found"));
    }

    @Test
    void assignWithoutUserIdReturns400() throws Exception {
        User admin = createUser(UserRole.ADMIN);
        UUID taskId = createTask(admin, null);

        mockMvc.perform(patch(TASKS + "/" + taskId + "/assign").with(asAdmin(admin))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isBadRequest());
    }

    // ---------- archive / unarchive ----------

    @Test
    void archiveAndUnarchiveAreIdempotent() throws Exception {
        User admin = createUser(UserRole.ADMIN);
        UUID taskId = createTask(admin, null);

        String archivedAt = json(mockMvc.perform(post(TASKS + "/" + taskId + "/archive").with(asAdmin(admin)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.archivedAt").isNotEmpty())
                .andReturn().getResponse().getContentAsString()).get("archivedAt").asString();

        String archivedAgainAt = json(mockMvc.perform(post(TASKS + "/" + taskId + "/archive").with(asAdmin(admin)))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString()).get("archivedAt").asString();

        // Same instant: the first response carries nanoseconds, PostgreSQL rounds them to microseconds
        assertThat(Instant.parse(archivedAgainAt))
                .isCloseTo(Instant.parse(archivedAt), within(1, ChronoUnit.MICROS));

        mockMvc.perform(post(TASKS + "/" + taskId + "/unarchive").with(asAdmin(admin)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.archivedAt").value(nullValue()));

        mockMvc.perform(post(TASKS + "/" + taskId + "/unarchive").with(asAdmin(admin)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.archivedAt").value(nullValue()));

        assertThat(events(taskId))
                .extracting(e -> e.getType())
                .containsExactly(TaskEventType.UNARCHIVED, TaskEventType.ARCHIVED, TaskEventType.CREATED);
    }

    @Test
    void unarchiveOfNeverArchivedTaskRecordsNoEvent() throws Exception {
        User admin = createUser(UserRole.ADMIN);
        UUID taskId = createTask(admin, null);

        mockMvc.perform(post(TASKS + "/" + taskId + "/unarchive").with(asAdmin(admin)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.archivedAt").value(nullValue()));

        assertThat(events(taskId)).extracting(e -> e.getType()).containsExactly(TaskEventType.CREATED);
    }

    @Test
    void archiveUnknownTaskReturns404() throws Exception {
        User admin = createUser(UserRole.ADMIN);

        mockMvc.perform(post(TASKS + "/" + UUID.randomUUID() + "/archive").with(asAdmin(admin)))
                .andExpect(status().isNotFound());
        mockMvc.perform(post(TASKS + "/" + UUID.randomUUID() + "/unarchive").with(asAdmin(admin)))
                .andExpect(status().isNotFound());
    }

    // ---------- DELETE /{id} ----------

    @Test
    void deleteIsSoftAndHidesTheTask() throws Exception {
        User admin = createUser(UserRole.ADMIN);
        UUID taskId = createTask(admin, null);

        mockMvc.perform(delete(TASKS + "/" + taskId).with(asAdmin(admin)))
                .andExpect(status().isNoContent());

        mockMvc.perform(get(TASKS + "/" + taskId).with(asAdmin(admin)))
                .andExpect(status().isNotFound());
        mockMvc.perform(get(TASKS + "/" + taskId + "/events").with(asAdmin(admin)))
                .andExpect(status().isNotFound());
        mockMvc.perform(delete(TASKS + "/" + taskId).with(asAdmin(admin)))
                .andExpect(status().isNotFound());
        changeStatusRaw(admin, taskId, "DONE")
                .andExpect(status().isNotFound());
    }

    @Test
    void deleteUnknownTaskReturns404() throws Exception {
        mockMvc.perform(delete(TASKS + "/" + UUID.randomUUID()).with(asAdmin(createUser(UserRole.ADMIN))))
                .andExpect(status().isNotFound());
    }

    @Test
    void referenceOfSoftDeletedTaskCannotBeReused() throws Exception {
        User admin = createUser(UserRole.ADMIN);
        String reference = uniqueReference();
        String body = """
                {"reference": "%s", "title": "Task"}
                """.formatted(reference);

        String created = mockMvc.perform(post(TASKS).with(asAdmin(admin))
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();

        mockMvc.perform(delete(TASKS + "/" + json(created).get("id").asString()).with(asAdmin(admin)))
                .andExpect(status().isNoContent());

        // tasks_referenceUQ still covers the soft-deleted row: the reference stays taken, with a clean 409
        mockMvc.perform(post(TASKS).with(asAdmin(admin)).contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.title").value("Task reference already exists"));
    }

    // ---------- helpers ----------

    private ResultActions changeStatusRaw(User admin, UUID taskId, String status)
            throws Exception {
        return mockMvc.perform(patch(TASKS + "/" + taskId + "/status").with(asAdmin(admin))
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {"status": "%s"}
                        """.formatted(status)));
    }

    private ResultActions changeStatus(User admin, UUID taskId, String status)
            throws Exception {
        return changeStatusRaw(admin, taskId, status).andExpect(status().isOk());
    }

    private ResultActions cancel(User admin, UUID taskId, String reason)
            throws Exception {
        return mockMvc.perform(post(TASKS + "/" + taskId + "/cancel").with(asAdmin(admin))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"reason": "%s"}
                                """.formatted(reason)))
                .andExpect(status().isOk());
    }

    private ResultActions assign(User admin, UUID taskId, UUID userId)
            throws Exception {
        return mockMvc.perform(patch(TASKS + "/" + taskId + "/assign").with(asAdmin(admin))
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {"userId": "%s"}
                        """.formatted(userId)));
    }
}
