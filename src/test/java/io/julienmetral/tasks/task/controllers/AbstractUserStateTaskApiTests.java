package io.julienmetral.tasks.task.controllers;

import io.julienmetral.tasks.identity.entities.User;
import io.julienmetral.tasks.identity.entities.UserRole;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.ResultActions;

import java.util.UUID;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Fixtures to move users out of the ACTIVE state (unverified, disabled, soft-deleted) and to
 * read tasks and their history through the API.
 */
abstract class AbstractUserStateTaskApiTests extends AbstractTaskApiTests {

    protected User createUnverifiedUser(UserRole role) {
        User user = createUser(role);
        user.setEmailVerifiedAt(null);
        return userRepository.saveAndFlush(user);
    }

    protected User createDisabledUser(UserRole role) {
        User user = createUser(role);
        user.setEnabled(false);
        return userRepository.saveAndFlush(user);
    }

    /** Creates an ACTIVE user, then soft-deletes it through the API. */
    protected User createDeletedUser(UserRole role) throws Exception {
        User user = createUser(role);
        deleteThroughApi(user);
        return user;
    }

    protected void deleteThroughApi(User user) throws Exception {
        User admin = createUser(UserRole.ADMIN);

        mockMvc.perform(delete("/api/v1/users/" + user.getId()).with(asAdmin(admin)))
                .andExpect(status().isNoContent());
    }

    protected void disableThroughApi(User user) throws Exception {
        User admin = createUser(UserRole.ADMIN);

        mockMvc.perform(post("/api/v1/users/" + user.getId() + "/disable").with(asAdmin(admin)))
                .andExpect(status().isNoContent());
    }

    /** Clears the verification date of an existing user, as if it had never verified its email. */
    protected void unverify(User user) {
        User reloaded = userRepository.findById(user.getId()).orElseThrow();
        reloaded.setEmailVerifiedAt(null);
        userRepository.saveAndFlush(reloaded);
    }

    protected ResultActions getTask(User reader, UUID taskId) throws Exception {
        return mockMvc.perform(get(TASKS + "/" + taskId).with(asAdmin(reader)));
    }

    protected ResultActions getEvents(User reader, UUID taskId) throws Exception {
        return mockMvc.perform(get(TASKS + "/" + taskId + "/events").with(asAdmin(reader)));
    }

    protected ResultActions assign(User admin, UUID taskId, UUID userId) throws Exception {
        return mockMvc.perform(patch(TASKS + "/" + taskId + "/assign")
                .with(asAdmin(admin))
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"userId\": \"" + userId + "\"}"));
    }
}
