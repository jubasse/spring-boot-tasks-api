package io.julienmetral.tasks.task.controllers;

import io.julienmetral.tasks.identity.entities.User;
import io.julienmetral.tasks.identity.entities.UserRole;
import io.julienmetral.tasks.task.entities.TaskEventType;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The assignee rule against the stored assignment: ADMIN, the assigned USER and another USER. Anonymous callers and
 * the admin-only endpoints, which need no data, are covered by {@link TaskControllerWebMvcTests}.
 */
class TaskAuthorizationControllerTests extends AbstractTaskApiTests {

    private User admin;
    private User assignee;
    private User otherUser;
    private UUID taskId;

    @BeforeEach
    void setUp() throws Exception {
        admin = createUser(UserRole.ADMIN);
        assignee = createUser(UserRole.USER);
        otherUser = createUser(UserRole.USER);
        taskId = createTask(admin, assignee);
    }

    @Test
    void anyAuthenticatedUserCanReadATask() throws Exception {
        mockMvc.perform(get(TASKS + "/" + taskId).with(asUser(otherUser)))
                .andExpect(status().isOk());
    }

    @Test
    void assigneeCanUpdate() throws Exception {
        update(asUser(assignee))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.title").value("By assignee"));
    }

    @Test
    void adminCanUpdateTaskAssignedToSomeoneElse() throws Exception {
        update(asAdmin(admin))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.title").value("By assignee"));
    }

    @Test
    void nonAssignedUserCannotUpdate() throws Exception {
        update(asUser(otherUser)).andExpect(status().isForbidden());

        assertTaskUntouched();
    }

    @Test
    void assigneeCanChangeStatus() throws Exception {
        changeStatus(asUser(assignee))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("IN_PROGRESS"));
    }

    @Test
    void adminCanChangeStatus() throws Exception {
        changeStatus(asAdmin(admin))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("IN_PROGRESS"));
    }

    @Test
    void nonAssignedUserCannotChangeStatus() throws Exception {
        changeStatus(asUser(otherUser)).andExpect(status().isForbidden());

        assertTaskUntouched();
    }

    @Test
    void assigneeCanCancel() throws Exception {
        cancel(asUser(assignee))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("CANCELLED"));
    }

    @Test
    void adminCanCancel() throws Exception {
        cancel(asAdmin(admin))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("CANCELLED"));
    }

    @Test
    void nonAssignedUserCannotCancel() throws Exception {
        cancel(asUser(otherUser)).andExpect(status().isForbidden());

        assertTaskUntouched();
    }

    @Test
    void creatorWhoIsNotAssignedCannotUpdate() throws Exception {
        String created = mockMvc.perform(post(TASKS).with(asUser(otherUser))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"reference\": \"%s\", \"title\": \"Mine\"}".formatted(uniqueReference())))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        UUID ownTaskId = UUID.fromString(json(created).get("id").asString());

        mockMvc.perform(patch(TASKS + "/" + ownTaskId).with(asUser(otherUser))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"title\": \"x\"}"))
                .andExpect(status().isForbidden());
    }

    @Test
    void previousAssigneeLosesAccessAfterReassignment() throws Exception {
        mockMvc.perform(patch(TASKS + "/" + taskId + "/assign").with(asAdmin(admin))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"userId\": \"%s\"}".formatted(otherUser.getId())))
                .andExpect(status().isOk());

        update(asUser(assignee)).andExpect(status().isForbidden());
        update(asUser(otherUser)).andExpect(status().isOk());
    }

    @Test
    void userOnUnknownTaskIsForbiddenNotNotFound() throws Exception {
        mockMvc.perform(patch(TASKS + "/" + UUID.randomUUID()).with(asUser(assignee))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"title\": \"x\"}"))
                .andExpect(status().isForbidden());
    }

    private ResultActions update(RequestPostProcessor auth) throws Exception {
        return mockMvc.perform(patch(TASKS + "/" + taskId).with(auth)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"title\": \"By assignee\"}"));
    }

    private ResultActions changeStatus(RequestPostProcessor auth) throws Exception {
        return mockMvc.perform(patch(TASKS + "/" + taskId + "/status").with(auth)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"status\": \"IN_PROGRESS\"}"));
    }

    private ResultActions cancel(RequestPostProcessor auth) throws Exception {
        return mockMvc.perform(post(TASKS + "/" + taskId + "/cancel").with(auth)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"reason\": \"Not needed\"}"));
    }

    private void assertTaskUntouched() throws Exception {
        mockMvc.perform(get(TASKS + "/" + taskId).with(asAdmin(admin)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.title").value("Task"))
                .andExpect(jsonPath("$.status").value("TO_DO"))
                .andExpect(jsonPath("$.version").value(0));

        assertThat(events(taskId)).extracting(e -> e.getType()).containsExactly(TaskEventType.CREATED);
    }
}
