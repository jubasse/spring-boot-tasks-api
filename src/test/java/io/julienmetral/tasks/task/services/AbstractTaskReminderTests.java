package io.julienmetral.tasks.task.services;

import io.julienmetral.tasks.TestcontainersConfiguration;
import io.julienmetral.tasks.config.TaskReminderProperties;
import io.julienmetral.tasks.identity.entities.User;
import io.julienmetral.tasks.identity.entities.UserRole;
import io.julienmetral.tasks.identity.repositories.UserRepository;
import io.julienmetral.tasks.support.Mailpit;
import io.julienmetral.tasks.task.entities.TaskReminderKind;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.EnumSet;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The reminder run looks at every task in the database, so the clock is pinned far from the real time: no task of
 * another test class, whose due dates are near the present, can fall inside the due-soon or overdue windows. Tests
 * therefore assert on their own tasks and users only, never on global counts.
 */
@Import({TestcontainersConfiguration.class, Mailpit.class, AbstractTaskReminderTests.ReminderClock.class})
@SpringBootTest
@AutoConfigureMockMvc
abstract class AbstractTaskReminderTests {

    static final Instant NOW = Instant.parse("2100-01-01T00:00:00Z");

    static final String TITLE = "Prepare the board meeting";

    @TestConfiguration(proxyBeanMethods = false)
    static class ReminderClock {

        @Bean
        @Primary
        SettableClock settableClock() {
            return new SettableClock(NOW);
        }
    }

    static final class SettableClock extends Clock {

        private volatile Instant instant;

        SettableClock(Instant instant) {
            this.instant = instant;
        }

        void set(Instant instant) {
            this.instant = instant;
        }

        @Override
        public Instant instant() {
            return instant;
        }

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return Clock.fixed(instant, zone);
        }
    }

    record CreatedTask(UUID id, String reference) {
    }

    record ReminderRow(TaskReminderKind kind, Instant dueAt, UUID recipientId, Instant sentAt) {
    }

    @Autowired
    TaskReminderService reminderService;

    @Autowired
    TaskReminderProperties properties;

    @Autowired
    SettableClock clock;

    @Autowired
    MockMvc mockMvc;

    @Autowired
    UserRepository userRepository;

    @Autowired
    PasswordEncoder passwordEncoder;

    @Autowired
    JdbcTemplate jdbcTemplate;

    @Autowired
    JsonMapper jsonMapper;

    @Value("${mailpit.api-url}")
    String mailpitApiUrl;

    @BeforeEach
    void resetClock() {
        clock.set(NOW);
    }

    Instant dueSoonEnd() {
        return NOW.plus(properties.dueSoonLeadTime());
    }

    Instant overdueStart() {
        return NOW.minus(properties.overdueLookback());
    }

    User createUser(UserRole role) {
        User user = new User();

        user.setEmail(UUID.randomUUID() + "@example.com");
        user.setPasswordHash(passwordEncoder.encode("password"));
        user.setEmailVerifiedAt(Instant.now());
        user.setDisplayName("Reminded " + role + " " + UUID.randomUUID().toString().substring(0, 6));
        user.setRoles(EnumSet.of(UserRole.USER, role));

        return userRepository.saveAndFlush(user);
    }

    User createAdmin() {
        return createUser(UserRole.ADMIN);
    }

    User createAssignee() {
        return createUser(UserRole.USER);
    }

    CreatedTask createTask(User admin, User assignee, Instant dueAt) throws Exception {
        String reference = "REM-" + UUID.randomUUID().toString().substring(0, 8);
        String assigned = assignee == null ? "null" : "\"" + assignee.getId() + "\"";
        String due = dueAt == null ? "null" : "\"" + dueAt + "\"";

        String body = mockMvc.perform(post("/api/v1/tasks")
                        .with(asAdmin(admin))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"reference": "%s", "title": "%s", "priority": "HIGH", "dueAt": %s, "assignedTo": %s}
                                """.formatted(reference, TITLE, due, assigned)))
                .andExpect(status().isCreated())
                .andReturn()
                .getResponse()
                .getContentAsString();

        return new CreatedTask(UUID.fromString(jsonMapper.readTree(body).get("id").asString()), reference);
    }

    void moveDueDate(User admin, CreatedTask task, Instant dueAt) throws Exception {
        mockMvc.perform(patch("/api/v1/tasks/{id}", task.id())
                        .with(asAdmin(admin))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"dueAt\": \"" + dueAt + "\"}"))
                .andExpect(status().isOk());
    }

    void assign(User admin, CreatedTask task, User assignee) throws Exception {
        mockMvc.perform(patch("/api/v1/tasks/{id}/assign", task.id())
                        .with(asAdmin(admin))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"userId\": \"" + assignee.getId() + "\"}"))
                .andExpect(status().isOk());
    }

    void changeStatus(User admin, CreatedTask task, String status) throws Exception {
        mockMvc.perform(patch("/api/v1/tasks/{id}/status", task.id())
                        .with(asAdmin(admin))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"status\": \"" + status + "\"}"))
                .andExpect(status().isOk());
    }

    void cancel(User admin, CreatedTask task) throws Exception {
        mockMvc.perform(post("/api/v1/tasks/{id}/cancel", task.id())
                        .with(asAdmin(admin))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"reason\": \"No longer needed\"}"))
                .andExpect(status().isOk());
    }

    void archive(User admin, CreatedTask task) throws Exception {
        mockMvc.perform(post("/api/v1/tasks/{id}/archive", task.id()).with(asAdmin(admin)))
                .andExpect(status().isOk());
    }

    void unarchive(User admin, CreatedTask task) throws Exception {
        mockMvc.perform(post("/api/v1/tasks/{id}/unarchive", task.id()).with(asAdmin(admin)))
                .andExpect(status().isOk());
    }

    void deleteTask(User admin, CreatedTask task) throws Exception {
        mockMvc.perform(delete("/api/v1/tasks/{id}", task.id()).with(asAdmin(admin)))
                .andExpect(status().isNoContent());
    }

    void disable(User user) {
        User reloaded = userRepository.findById(user.getId()).orElseThrow();
        reloaded.setEnabled(false);
        userRepository.saveAndFlush(reloaded);
    }

    void enable(User user) {
        User reloaded = userRepository.findById(user.getId()).orElseThrow();
        reloaded.setEnabled(true);
        userRepository.saveAndFlush(reloaded);
    }

    void unverify(User user) {
        User reloaded = userRepository.findById(user.getId()).orElseThrow();
        reloaded.setEmailVerifiedAt(null);
        userRepository.saveAndFlush(reloaded);
    }

    void deleteUser(User admin, User user) throws Exception {
        mockMvc.perform(delete("/api/v1/users/{id}", user.getId()).with(asAdmin(admin)))
                .andExpect(status().isNoContent());
    }

    void updateReminderSettings(User user, boolean dueSoon, boolean overdue) throws Exception {
        mockMvc.perform(put("/api/v1/users/{id}/notification-settings", user.getId())
                        .with(as(user, UserRole.USER))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"taskAssigned": true, "taskUnassigned": true, "taskCancelled": true,
                                 "taskDeleted": true, "taskCommented": true, "taskMentioned": true,
                                 "taskDueSoon": %s, "taskOverdue": %s}
                                """.formatted(dueSoon, overdue)))
                .andExpect(status().isOk());
    }

    List<ReminderRow> remindersOf(CreatedTask task) {
        return jdbcTemplate.query(
                "select kind, due_at, recipient_id, sent_at from task_reminders where task_id = ? order by id",
                (row, index) -> new ReminderRow(
                        TaskReminderKind.valueOf(row.getString("kind")),
                        row.getTimestamp("due_at").toInstant(),
                        row.getObject("recipient_id", UUID.class),
                        row.getTimestamp("sent_at").toInstant()
                ),
                task.id()
        );
    }

    RequestPostProcessor as(User user, UserRole role) {
        return jwt()
                .jwt(token -> token.claim("uid", user.getId().toString()))
                .authorities(new SimpleGrantedAuthority("ROLE_" + role.name()));
    }

    RequestPostProcessor asAdmin(User admin) {
        return as(admin, UserRole.ADMIN);
    }

    static String dueSoonSubject(CreatedTask task) {
        return "Task " + task.reference() + " is due soon";
    }

    static String overdueSubject(CreatedTask task) {
        return "Task " + task.reference() + " is overdue";
    }

    int countWithSubject(User recipient, String subject) {
        return searchBySubject(recipient, subject).path("messages_count").asInt();
    }

    /** Plain-text body of the latest email with this subject, waiting up to 5 seconds for the async dispatch. */
    String awaitTextWithSubject(User recipient, String subject) throws InterruptedException {
        Instant deadline = Instant.now().plusSeconds(5);

        while (true) {
            JsonNode messages = searchBySubject(recipient, subject).path("messages");

            if (!messages.isEmpty()) {
                // Mailpit's substring match would also accept a longer subject
                assertThat(messages.get(0).path("Subject").asString()).isEqualTo(subject);

                return readMailpit("/api/v1/message/{id}", messages.get(0).path("ID").asString())
                        .path("Text")
                        .asString();
            }

            if (Instant.now().isAfter(deadline)) {
                throw new AssertionError("No email \"" + subject + "\" received by " + recipient.getEmail());
            }

            Thread.sleep(100);
        }
    }

    // Mail is dispatched asynchronously after commit: "nothing sent" can only be checked after a grace period
    static void waitForAsyncDispatch() throws InterruptedException {
        Thread.sleep(Duration.ofMillis(800));
    }

    private JsonNode searchBySubject(User recipient, String subject) {
        return readMailpit(
                "/api/v1/search?query={query}",
                "to:\"" + recipient.getEmail() + "\" subject:\"" + subject + "\""
        );
    }

    private JsonNode readMailpit(String uri, Object... variables) {
        String body = RestClient.create(mailpitApiUrl)
                .get()
                .uri(uri, variables)
                .retrieve()
                .body(String.class);

        return jsonMapper.readTree(body);
    }
}
