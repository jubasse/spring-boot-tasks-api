package io.julienmetral.tasks.task.controllers;

import io.julienmetral.tasks.identity.repositories.UserRepository;
import io.julienmetral.tasks.support.WebLayerTest;
import io.julienmetral.tasks.task.exceptions.TaskNotFoundException;
import io.julienmetral.tasks.task.services.TaskEventService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.web.servlet.MockMvc;

import java.util.UUID;

import static io.julienmetral.tasks.support.Problems.invalidParameter;
import static io.julienmetral.tasks.support.Problems.untypedProblem;
import static io.julienmetral.tasks.support.WebCallers.everyAccountIsActive;
import static io.julienmetral.tasks.support.WebCallers.user;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebLayerTest
class TaskEventControllerWebMvcTests {

    private static final String EVENTS = "/api/v1/tasks/{taskId}/events";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private TaskEventService taskEventService;

    @Autowired
    private UserRepository userRepository;

    @BeforeEach
    void callersAreActive() {
        everyAccountIsActive(userRepository);
    }

    @Test
    void withoutTokenReturns401() throws Exception {
        mockMvc.perform(get(EVENTS, UUID.randomUUID()))
                .andExpect(status().isUnauthorized());

        verifyNoInteractions(taskEventService);
    }

    @Test
    void malformedTaskIdNamesThePathParameter() throws Exception {
        mockMvc.perform(get(EVENTS, "not-a-uuid").with(user(UUID.randomUUID())))
                .andExpect(invalidParameter("taskId", "must be a UUID"));

        verifyNoInteractions(taskEventService);
    }

    @Test
    void unknownTaskReturnsNotFoundProblem() throws Exception {
        UUID unknown = UUID.randomUUID();
        when(taskEventService.findAllByTaskId(eq(unknown), any())).thenThrow(new TaskNotFoundException(unknown));

        mockMvc.perform(get(EVENTS, unknown).with(user(UUID.randomUUID())))
                .andExpect(untypedProblem(404, "Not Found"))
                .andExpect(jsonPath("$.detail").value("Task not found with id: " + unknown));
    }
}
