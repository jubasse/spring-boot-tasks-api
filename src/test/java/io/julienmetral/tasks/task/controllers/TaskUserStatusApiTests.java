package io.julienmetral.tasks.task.controllers;

import io.julienmetral.tasks.identity.entities.User;
import io.julienmetral.tasks.identity.entities.UserRole;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import tools.jackson.databind.JsonNode;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.nullValue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The {@code {id, displayName, status}} previews of {@code assignedTo}, {@code createdBy} and the
 * history {@code actor}, and how they follow the referenced user's account state, including
 * soft deletion.
 */
class TaskUserStatusApiTests extends AbstractUserStateTaskApiTests {

    @Test
    void activeUsersAreShownWithTheirStatus() throws Exception {
        User admin = createUser(UserRole.ADMIN);
        User assignee = createUser(UserRole.USER);
        UUID taskId = createTask(admin, assignee);

        getTask(admin, taskId)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.assignedTo.id").value(assignee.getId().toString()))
                .andExpect(jsonPath("$.assignedTo.displayName").value(assignee.getDisplayName()))
                .andExpect(jsonPath("$.assignedTo.status").value("ACTIVE"))
                .andExpect(jsonPath("$.createdBy.id").value(admin.getId().toString()))
                .andExpect(jsonPath("$.createdBy.displayName").value(admin.getDisplayName()))
                .andExpect(jsonPath("$.createdBy.status").value("ACTIVE"));

        getEvents(admin, taskId)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content[0].actor.id").value(admin.getId().toString()))
                .andExpect(jsonPath("$.content[0].actor.displayName").value(admin.getDisplayName()))
                .andExpect(jsonPath("$.content[0].actor.status").value("ACTIVE"));
    }

    @Test
    void unassignedTaskHasNullAssignee() throws Exception {
        User admin = createUser(UserRole.ADMIN);
        UUID taskId = createTask(admin, null);

        getTask(admin, taskId)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.assignedTo").value(nullValue()));
    }

    @Test
    void deletedAssigneeKeepsTheirNameAndIsShownAsDeleted() throws Exception {
        User admin = createUser(UserRole.ADMIN);
        User assignee = createUser(UserRole.USER);
        UUID taskId = createTask(admin, assignee);

        deleteThroughApi(assignee);

        getTask(admin, taskId)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.assignedTo.id").value(assignee.getId().toString()))
                .andExpect(jsonPath("$.assignedTo.displayName").value(assignee.getDisplayName()))
                .andExpect(jsonPath("$.assignedTo.status").value("DELETED"))
                .andExpect(jsonPath("$.createdBy.status").value("ACTIVE"));
    }

    @Test
    void deletedCreatorAndActorKeepTheirNameAndAreShownAsDeleted() throws Exception {
        User creator = createUser(UserRole.ADMIN);
        User reader = createUser(UserRole.ADMIN);
        UUID taskId = createTask(creator, null);

        deleteThroughApi(creator);

        getTask(reader, taskId)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.createdBy.id").value(creator.getId().toString()))
                .andExpect(jsonPath("$.createdBy.displayName").value(creator.getDisplayName()))
                .andExpect(jsonPath("$.createdBy.status").value("DELETED"));

        getEvents(reader, taskId)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content[0].type").value("CREATED"))
                .andExpect(jsonPath("$.content[0].actor.id").value(creator.getId().toString()))
                .andExpect(jsonPath("$.content[0].actor.displayName").value(creator.getDisplayName()))
                .andExpect(jsonPath("$.content[0].actor.status").value("DELETED"));
    }

    @Test
    void taskWhoseEveryUserIsDeletedStillLoads() throws Exception {
        User creator = createUser(UserRole.ADMIN);
        User assignee = createUser(UserRole.USER);
        User reader = createUser(UserRole.ADMIN);
        UUID taskId = createTask(creator, assignee);

        deleteThroughApi(creator);
        deleteThroughApi(assignee);

        getTask(reader, taskId)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.createdBy.id").value(creator.getId().toString()))
                .andExpect(jsonPath("$.createdBy.status").value("DELETED"))
                .andExpect(jsonPath("$.assignedTo.id").value(assignee.getId().toString()))
                .andExpect(jsonPath("$.assignedTo.status").value("DELETED"));

        getEvents(reader, taskId)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content[0].actor.status").value("DELETED"));
    }

    @Test
    void mutatingTaskWithDeletedUsersSucceeds() throws Exception {
        User creator = createUser(UserRole.ADMIN);
        User assignee = createUser(UserRole.USER);
        User reader = createUser(UserRole.ADMIN);
        UUID taskId = createTask(creator, assignee);

        deleteThroughApi(creator);
        deleteThroughApi(assignee);

        mockMvc.perform(patch(TASKS + "/" + taskId).with(asAdmin(reader))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"title\": \"Renamed\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.title").value("Renamed"));

        getEvents(reader, taskId)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content[0].type").value("UPDATED"))
                .andExpect(jsonPath("$.content[0].actor.status").value("ACTIVE"))
                .andExpect(jsonPath("$.content[1].type").value("CREATED"))
                .andExpect(jsonPath("$.content[1].actor.status").value("DELETED"));
    }

    @Test
    void mutatingTaskKeepsDeletedAssignee() throws Exception {
        User admin = createUser(UserRole.ADMIN);
        User assignee = createUser(UserRole.USER);
        UUID taskId = createTask(admin, assignee);

        deleteThroughApi(assignee);

        mockMvc.perform(patch(TASKS + "/" + taskId + "/status").with(asAdmin(admin))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"status\": \"IN_PROGRESS\"}"))
                .andExpect(status().isOk());

        getTask(admin, taskId)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.assignedTo.id").value(assignee.getId().toString()))
                .andExpect(jsonPath("$.assignedTo.status").value("DELETED"));
    }

    @Test
    void mutatingTaskKeepsDeletedCreator() throws Exception {
        User creator = createUser(UserRole.ADMIN);
        User editor = createUser(UserRole.ADMIN);
        UUID taskId = createTask(creator, null);

        deleteThroughApi(creator);

        mockMvc.perform(patch(TASKS + "/" + taskId).with(asAdmin(editor))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"title\": \"Renamed\"}"))
                .andExpect(status().isOk());

        getTask(editor, taskId)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.createdBy.id").value(creator.getId().toString()))
                .andExpect(jsonPath("$.createdBy.status").value("DELETED"));
    }

    @Test
    void disabledUsersAreShownAsDisabled() throws Exception {
        User creator = createUser(UserRole.ADMIN);
        User assignee = createUser(UserRole.USER);
        User reader = createUser(UserRole.ADMIN);
        UUID taskId = createTask(creator, assignee);

        disableThroughApi(creator);
        disableThroughApi(assignee);

        getTask(reader, taskId)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.assignedTo.id").value(assignee.getId().toString()))
                .andExpect(jsonPath("$.assignedTo.displayName").value(assignee.getDisplayName()))
                .andExpect(jsonPath("$.assignedTo.status").value("DISABLED"))
                .andExpect(jsonPath("$.createdBy.displayName").value(creator.getDisplayName()))
                .andExpect(jsonPath("$.createdBy.status").value("DISABLED"));

        getEvents(reader, taskId)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content[0].actor.displayName").value(creator.getDisplayName()))
                .andExpect(jsonPath("$.content[0].actor.status").value("DISABLED"));
    }

    @Test
    void unverifiedUsersAreShownAsUnverified() throws Exception {
        User creator = createUser(UserRole.ADMIN);
        User assignee = createUser(UserRole.USER);
        User reader = createUser(UserRole.ADMIN);
        UUID taskId = createTask(creator, assignee);

        unverify(creator);
        unverify(assignee);

        getTask(reader, taskId)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.assignedTo.status").value("UNVERIFIED"))
                .andExpect(jsonPath("$.createdBy.status").value("UNVERIFIED"));

        getEvents(reader, taskId)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content[0].actor.status").value("UNVERIFIED"));
    }

    @Test
    void disabledAndUnverifiedUserIsShownAsDisabled() throws Exception {
        User admin = createUser(UserRole.ADMIN);
        User assignee = createUser(UserRole.USER);
        UUID taskId = createTask(admin, assignee);

        unverify(assignee);
        disableThroughApi(assignee);

        getTask(admin, taskId)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.assignedTo.status").value("DISABLED"));
    }

    @Test
    void reenabledUserIsShownAsActiveAgain() throws Exception {
        User admin = createUser(UserRole.ADMIN);
        User assignee = createUser(UserRole.USER);
        UUID taskId = createTask(admin, assignee);

        disableThroughApi(assignee);
        mockMvc.perform(post("/api/v1/users/" + assignee.getId() + "/enable").with(asAdmin(admin)))
                .andExpect(status().isNoContent());

        getTask(admin, taskId)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.assignedTo.status").value("ACTIVE"));
    }

    @Test
    void reassigningFromDeletedAssigneeRecordsTheirIdAsPrevious() throws Exception {
        User admin = createUser(UserRole.ADMIN);
        User deletedAssignee = createUser(UserRole.USER);
        User newAssignee = createUser(UserRole.USER);
        UUID taskId = createTask(admin, deletedAssignee);

        deleteThroughApi(deletedAssignee);

        assign(admin, taskId, newAssignee.getId())
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.assignedTo.id").value(newAssignee.getId().toString()))
                .andExpect(jsonPath("$.assignedTo.status").value("ACTIVE"));

        JsonNode latest = latestEvent(admin, taskId);
        assertThat(latest.get("type").asString()).isEqualTo("ASSIGNED");
        assertThat(latest.get("actor").get("id").asString()).isEqualTo(admin.getId().toString());
        assertThat(latest.get("payload").get("fromUserId").asString()).isEqualTo(deletedAssignee.getId().toString());
        assertThat(latest.get("payload").get("toUserId").asString()).isEqualTo(newAssignee.getId().toString());

        getTask(admin, taskId)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.assignedTo.id").value(newAssignee.getId().toString()));
    }

    @Test
    void reassigningFromDisabledAssigneeRecordsTheirIdAsPrevious() throws Exception {
        User admin = createUser(UserRole.ADMIN);
        User disabledAssignee = createUser(UserRole.USER);
        User newAssignee = createUser(UserRole.USER);
        UUID taskId = createTask(admin, disabledAssignee);

        disableThroughApi(disabledAssignee);

        assign(admin, taskId, newAssignee.getId()).andExpect(status().isOk());

        JsonNode latest = latestEvent(admin, taskId);
        assertThat(latest.get("type").asString()).isEqualTo("ASSIGNED");
        assertThat(latest.get("payload").get("fromUserId").asString()).isEqualTo(disabledAssignee.getId().toString());
        assertThat(latest.get("payload").get("toUserId").asString()).isEqualTo(newAssignee.getId().toString());
    }

    @Test
    void historyOfDeletedActorOnLaterEventsKeepsTheirNameAndIsShownAsDeleted() throws Exception {
        User creator = createUser(UserRole.ADMIN);
        User editor = createUser(UserRole.ADMIN);
        UUID taskId = createTask(creator, null);

        mockMvc.perform(patch(TASKS + "/" + taskId).with(asAdmin(editor))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"title\": \"Renamed\"}"))
                .andExpect(status().isOk());

        deleteThroughApi(editor);

        getEvents(creator, taskId)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content[0].type").value("UPDATED"))
                .andExpect(jsonPath("$.content[0].actor.id").value(editor.getId().toString()))
                .andExpect(jsonPath("$.content[0].actor.displayName").value(editor.getDisplayName()))
                .andExpect(jsonPath("$.content[0].actor.status").value("DELETED"))
                .andExpect(jsonPath("$.content[1].type").value("CREATED"))
                .andExpect(jsonPath("$.content[1].actor.status").value("ACTIVE"));
    }

    private JsonNode latestEvent(User reader, UUID taskId) throws Exception {
        String body = getEvents(reader, taskId)
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString();

        return json(body).get("content").get(0);
    }
}
