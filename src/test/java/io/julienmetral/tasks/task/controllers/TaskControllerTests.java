package io.julienmetral.tasks.task.controllers;

import io.julienmetral.tasks.TestcontainersConfiguration;
import io.julienmetral.tasks.identity.entities.User;
import io.julienmetral.tasks.identity.entities.UserRole;
import io.julienmetral.tasks.identity.repositories.UserRepository;
import io.julienmetral.tasks.task.entities.TaskEventType;
import io.julienmetral.tasks.task.repositories.TaskEventRepository;
import org.junit.jupiter.api.Test;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.data.domain.Pageable;
import org.springframework.http.MediaType;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

import java.time.Instant;
import java.util.EnumSet;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@Import(TestcontainersConfiguration.class)
@SpringBootTest
@AutoConfigureMockMvc
class TaskControllerTests {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private PasswordEncoder passwordEncoder;

    @Autowired
    private TaskEventRepository taskEventRepository;

    @Test
    void userCanCreateUnassignedTaskAndCreatedEventIsRecorded() throws Exception {
        User user = createUser(UserRole.USER);
        String reference = uniqueReference();

        String location = mockMvc.perform(
                        post("/api/v1/tasks")
                                .with(as(user, UserRole.USER))
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("""
                                        {"reference": "%s", "title": "  Write tests  "}
                                        """.formatted(reference))
                )
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.reference").value(reference))
                .andExpect(jsonPath("$.title").value("Write tests"))
                .andExpect(jsonPath("$.priority").value("MEDIUM"))
                .andExpect(jsonPath("$.createdBy.id").value(user.getId().toString()))
                .andReturn()
                .getResponse()
                .getHeader("Location");

        UUID taskId = UUID.fromString(
                location.substring(location.lastIndexOf('/') + 1)
        );

        var events = taskEventRepository
                .findAllByTaskIdOrderByOccurredAtDesc(taskId, Pageable.unpaged())
                .getContent();

        assertThat(events).hasSize(1);
        assertThat(events.getFirst().getType()).isEqualTo(TaskEventType.CREATED);
        assertThat(events.getFirst().getId()).isNotNull();
    }

    @Test
    void userCannotAssignTaskOnCreation() throws Exception {
        User user = createUser(UserRole.USER);

        mockMvc.perform(
                        post("/api/v1/tasks")
                                .with(as(user, UserRole.USER))
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("""
                                        {"reference": "%s", "title": "Task", "assignedTo": "%s"}
                                        """.formatted(uniqueReference(), user.getId()))
                )
                .andExpect(status().isForbidden());
    }

    @Test
    void adminCanAssignTaskOnCreation() throws Exception {
        User admin = createUser(UserRole.ADMIN);
        User assignee = createUser(UserRole.USER);

        mockMvc.perform(
                        post("/api/v1/tasks")
                                .with(as(admin, UserRole.ADMIN))
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("""
                                        {"reference": "%s", "title": "Task", "assignedTo": "%s"}
                                        """.formatted(uniqueReference(), assignee.getId()))
                )
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.assignedTo.id").value(assignee.getId().toString()));
    }

    private User createUser(UserRole role) {
        User user = new User();

        user.setEmail(UUID.randomUUID() + "@example.com");
        user.setPasswordHash(passwordEncoder.encode("password"));
        // Only enabled users with a verified email can work on tasks
        user.setEmailVerifiedAt(Instant.now());
        user.setDisplayName("Test " + role);
        user.setRoles(EnumSet.of(UserRole.USER, role));

        return userRepository.saveAndFlush(user);
    }

    private RequestPostProcessor as(User user, UserRole role) {
        return jwt()
                .jwt(token -> token.claim("uid", user.getId().toString()))
                .authorities(new SimpleGrantedAuthority("ROLE_" + role.name()));
    }

    private String uniqueReference() {
        return "T-" + UUID.randomUUID().toString().substring(0, 8);
    }
}
