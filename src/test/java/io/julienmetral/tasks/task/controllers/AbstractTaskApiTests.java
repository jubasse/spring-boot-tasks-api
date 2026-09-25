package io.julienmetral.tasks.task.controllers;

import io.julienmetral.tasks.TestcontainersConfiguration;
import io.julienmetral.tasks.identity.entities.User;
import io.julienmetral.tasks.identity.entities.UserRole;
import io.julienmetral.tasks.identity.repositories.UserRepository;
import io.julienmetral.tasks.task.entities.TaskEvent;
import io.julienmetral.tasks.task.repositories.TaskEventRepository;
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
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.time.Instant;
import java.util.EnumSet;
import java.util.List;
import java.util.UUID;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Shared fixtures for the task HTTP API integration tests: users created directly in the
 * database, a JWT post-processor carrying the {@code uid} claim, and a helper to create tasks
 * through the API.
 */
@Import(TestcontainersConfiguration.class)
@SpringBootTest
@AutoConfigureMockMvc
abstract class AbstractTaskApiTests {

    static final String TASKS = "/api/v1/tasks";

    @Autowired
    protected MockMvc mockMvc;

    @Autowired
    protected UserRepository userRepository;

    @Autowired
    protected PasswordEncoder passwordEncoder;

    @Autowired
    protected TaskEventRepository taskEventRepository;

    @Autowired
    protected JsonMapper jsonMapper;

    protected User createUser(UserRole role) {
        User user = new User();

        user.setEmail(UUID.randomUUID() + "@example.com");
        user.setPasswordHash(passwordEncoder.encode("password"));
        // Only enabled users with a verified email can work on tasks
        user.setEmailVerifiedAt(Instant.now());
        user.setDisplayName("Test " + role + " " + UUID.randomUUID().toString().substring(0, 4));
        user.setRoles(EnumSet.of(UserRole.USER, role));

        return userRepository.saveAndFlush(user);
    }

    protected RequestPostProcessor as(User user, UserRole role) {
        return jwt()
                .jwt(token -> token.claim("uid", user.getId().toString()))
                .authorities(new SimpleGrantedAuthority("ROLE_" + role.name()));
    }

    protected RequestPostProcessor asAdmin(User admin) {
        return as(admin, UserRole.ADMIN);
    }

    protected RequestPostProcessor asUser(User user) {
        return as(user, UserRole.USER);
    }

    protected String uniqueReference() {
        return "T-" + UUID.randomUUID().toString().substring(0, 8);
    }

    /** Creates a task through the API as the given admin, optionally assigned. */
    protected UUID createTask(User admin, User assignee) throws Exception {
        String assigned = assignee == null ? "null" : "\"" + assignee.getId() + "\"";

        String body = mockMvc.perform(
                        post(TASKS)
                                .with(asAdmin(admin))
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("""
                                        {"reference": "%s", "title": "Task", "description": "Initial description",
                                         "priority": "LOW", "dueAt": "2030-01-01T10:00:00Z", "assignedTo": %s}
                                        """.formatted(uniqueReference(), assigned))
                )
                .andExpect(status().isCreated())
                .andReturn()
                .getResponse()
                .getContentAsString();

        return UUID.fromString(json(body).get("id").asString());
    }

    protected JsonNode json(String body) {
        return jsonMapper.readTree(body);
    }

    /** Events of a task, newest first, read straight from the repository. */
    protected List<TaskEvent> events(UUID taskId) {
        return taskEventRepository
                .findAllByTaskIdOrderByOccurredAtDesc(taskId, Pageable.unpaged())
                .getContent();
    }
}
