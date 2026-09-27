package io.julienmetral.tasks.shared.exceptions;

import io.julienmetral.tasks.identity.services.UserService;
import io.julienmetral.tasks.support.WebLayerTest;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import java.util.UUID;

import static io.julienmetral.tasks.support.Problems.untypedProblem;
import static io.julienmetral.tasks.support.Problems.withoutJavaTypeNames;
import static io.julienmetral.tasks.support.WebCallers.user;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;

@WebLayerTest
class ApiExceptionHandlerWebMvcTests {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private UserService userService;

    @Test
    void unknownPathForAnAuthenticatedCallerIsAnUntypedNotFoundWithoutResourceDetails() throws Exception {
        mockMvc.perform(get("/api/v1/does-not-exist").with(user(UUID.randomUUID())))
                .andExpect(untypedProblem(404, "Not Found"))
                .andExpect(jsonPath("$.detail").value("No endpoint matches this path"))
                .andExpect(jsonPath("$.instance").value("/api/v1/does-not-exist"))
                .andExpect(withoutJavaTypeNames());
    }

    @Test
    void jsonSentToAMultipartEndpointIsAnUntypedUnsupportedMediaType() throws Exception {
        UUID id = UUID.randomUUID();

        mockMvc.perform(put("/api/v1/users/{id}/avatar", id)
                        .with(user(id))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(untypedProblem(415, "Unsupported Media Type"))
                .andExpect(withoutJavaTypeNames());
    }

    @Test
    @Disabled("bug: no handler catches unexpected exceptions, so a 500 is the servlet error page, not a problem")
    void unexpectedFailureIsAnUntypedInternalServerErrorWithoutItsMessage() throws Exception {
        UUID id = UUID.randomUUID();
        when(userService.findById(id)).thenThrow(new IllegalStateException("connection pool exhausted"));

        mockMvc.perform(get("/api/v1/users/{id}", id).with(user(id)))
                .andExpect(untypedProblem(500, "Internal Server Error"))
                .andExpect(content().string(not(containsString("connection pool"))));
    }

    @Test
    void unsupportedMethodIsAnUntypedMethodNotAllowed() throws Exception {
        UUID id = UUID.randomUUID();

        mockMvc.perform(post("/api/v1/users/{id}/avatar", id).with(user(id)))
                .andExpect(untypedProblem(405, "Method Not Allowed"))
                .andExpect(withoutJavaTypeNames());
    }
}
