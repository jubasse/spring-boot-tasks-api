package io.julienmetral.tasks.notification.controllers;

import io.julienmetral.tasks.identity.entities.User;
import io.julienmetral.tasks.identity.exceptions.UserNotFoundException;
import io.julienmetral.tasks.notification.entities.NotificationSettings;
import io.julienmetral.tasks.notification.services.NotificationSettingsService;
import io.julienmetral.tasks.support.WebLayerTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

import static io.julienmetral.tasks.support.WebCallers.admin;
import static io.julienmetral.tasks.support.WebCallers.user;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebLayerTest
class NotificationSettingsControllerWebMvcTests {

    private static final String SETTINGS = "/api/v1/users/{id}/notification-settings";

    private static final List<String> FLAGS = List.of(
            "taskAssigned", "taskUnassigned", "taskCancelled", "taskDeleted",
            "taskCommented", "taskMentioned", "taskDueSoon", "taskOverdue"
    );

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private NotificationSettingsService settingsService;

    @ParameterizedTest
    @ValueSource(strings = {
            "taskAssigned", "taskUnassigned", "taskCancelled", "taskDeleted",
            "taskCommented", "taskMentioned", "taskDueSoon", "taskOverdue"
    })
    void putWithoutAFlagReturnsBadRequest(String missing) throws Exception {
        Map<String, Object> body = allFlags(false);
        body.remove(missing);

        expectRejected(json(body));
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "taskAssigned", "taskUnassigned", "taskCancelled", "taskDeleted",
            "taskCommented", "taskMentioned", "taskDueSoon", "taskOverdue"
    })
    void putWithANullFlagReturnsBadRequest(String nulled) throws Exception {
        Map<String, Object> body = allFlags(true);
        body.put(nulled, null);

        expectRejected(json(body));
    }

    @Test
    void putWithTheSixSwitchesOfTheFormerBodyReturnsBadRequest() throws Exception {
        expectRejected("""
                {"taskAssigned": false, "taskUnassigned": false, "taskCancelled": false, "taskDeleted": false,
                 "taskCommented": false, "taskMentioned": false}
                """);
    }

    @Test
    void putWithOnlyTheFourTaskSwitchesReturnsBadRequest() throws Exception {
        expectRejected("""
                {"taskAssigned": false, "taskUnassigned": false, "taskCancelled": false, "taskDeleted": false}
                """);
    }

    @Test
    void putWithEmptyBodyObjectReturnsBadRequest() throws Exception {
        expectRejected("{}");
    }

    @Test
    void putWithMalformedJsonReturnsBadRequest() throws Exception {
        expectRejected("{\"taskAssigned\": ");
    }

    @Test
    void putWithAllEightFlagsReachesTheService() throws Exception {
        UUID id = UUID.randomUUID();
        when(settingsService.update(eq(id), any())).thenReturn(NotificationSettings.defaults(account(id)));

        putSettings(id, user(id), json(allFlags(false)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(8));
    }

    @Test
    void getWithInvalidUuidReturnsBadRequest() throws Exception {
        mockMvc.perform(get(SETTINGS, "not-a-uuid").with(admin(UUID.randomUUID())))
                .andExpect(status().isBadRequest());
    }

    @Test
    void putWithInvalidUuidReturnsBadRequest() throws Exception {
        mockMvc.perform(put(SETTINGS, "not-a-uuid")
                        .with(admin(UUID.randomUUID()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(allFlags(true))))
                .andExpect(status().isBadRequest());
    }

    @Test
    void getWithoutTokenReturnsUnauthorized() throws Exception {
        mockMvc.perform(get(SETTINGS, UUID.randomUUID()))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void putWithoutTokenReturnsUnauthorized() throws Exception {
        mockMvc.perform(put(SETTINGS, UUID.randomUUID())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(allFlags(true))))
                .andExpect(status().isUnauthorized());

        verifyNoInteractions(settingsService);
    }

    @Test
    void userCannotReadAnotherUsersSettings() throws Exception {
        mockMvc.perform(get(SETTINGS, UUID.randomUUID()).with(user(UUID.randomUUID())))
                .andExpect(status().isForbidden());

        verifyNoInteractions(settingsService);
    }

    @Test
    void userCannotUpdateAnotherUsersSettings() throws Exception {
        putSettings(UUID.randomUUID(), user(UUID.randomUUID()), json(allFlags(false)))
                .andExpect(status().isForbidden());

        verifyNoInteractions(settingsService);
    }

    @Test
    void adminPassesTheCheckToReadAnotherUsersSettings() throws Exception {
        UUID other = UUID.randomUUID();
        when(settingsService.get(other)).thenReturn(NotificationSettings.defaults(account(other)));

        mockMvc.perform(get(SETTINGS, other).with(admin(UUID.randomUUID())))
                .andExpect(status().isOk());
    }

    @Test
    void settingsOfAUserTheServiceCannotFindReturnNotFoundProblem() throws Exception {
        UUID unknown = UUID.randomUUID();
        when(settingsService.get(unknown)).thenThrow(new UserNotFoundException(unknown));

        mockMvc.perform(get(SETTINGS, unknown).with(admin(UUID.randomUUID())))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.status").value(404))
                .andExpect(jsonPath("$.title").value("User not found"));
    }

    private void expectRejected(String body) throws Exception {
        UUID id = UUID.randomUUID();

        putSettings(id, user(id), body).andExpect(status().isBadRequest());

        verifyNoInteractions(settingsService);
    }

    private ResultActions putSettings(UUID id, RequestPostProcessor caller, String body) throws Exception {
        return mockMvc.perform(put(SETTINGS, id).with(caller).contentType(MediaType.APPLICATION_JSON).content(body));
    }

    private static Map<String, Object> allFlags(boolean value) {
        Map<String, Object> flags = new LinkedHashMap<>();
        FLAGS.forEach(flag -> flags.put(flag, value));
        return flags;
    }

    private static String json(Map<String, Object> flags) {
        return flags.entrySet()
                .stream()
                .map(flag -> "\"%s\": %s".formatted(flag.getKey(), flag.getValue()))
                .collect(Collectors.joining(", ", "{", "}"));
    }

    private static User account(UUID id) {
        User user = new User();
        user.setId(id);
        return user;
    }
}
