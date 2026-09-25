package io.julienmetral.tasks.notification.controllers;

import io.julienmetral.tasks.TestcontainersConfiguration;
import io.julienmetral.tasks.identity.entities.User;
import io.julienmetral.tasks.identity.entities.UserRole;
import io.julienmetral.tasks.identity.repositories.UserRepository;
import io.julienmetral.tasks.support.Mailpit;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.time.Duration;
import java.time.Instant;
import java.util.EnumSet;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * End-to-end tests of task email notifications: task requests go through the API, and the emails
 * sent after commit are read back from the Mailpit container.
 */
@Import({TestcontainersConfiguration.class, Mailpit.class})
@SpringBootTest
@AutoConfigureMockMvc
class TaskNotificationApiTests {

    private static final String TASKS = "/api/v1/tasks";

    private static final String TITLE = "Write the quarterly report";

    // Mail is dispatched asynchronously after commit: "nothing sent" can only be checked after a grace period
    private static final Duration NO_MAIL_GRACE_PERIOD = Duration.ofMillis(800);

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private PasswordEncoder passwordEncoder;

    @Autowired
    private JsonMapper jsonMapper;

    @Autowired
    private Mailpit mailpit;

    @Value("${mailpit.api-url}")
    private String mailpitApiUrl;

    private record CreatedTask(UUID id, String reference) {
    }

    // ---------------------------------------------------------------- assignment

    @Test
    void creatingAssignedTaskEmailsAssigneeWithGreetingActorReferenceTitleAndSettingsFooter() throws Exception {
        User admin = createUser(UserRole.ADMIN);
        User assignee = createUser(UserRole.USER);

        CreatedTask task = createTask(admin, assignee);

        String text = awaitTextWithSubject(assignee, assignedSubject(task));

        assertThat(text)
                .contains("Hello " + assignee.getDisplayName() + ",")
                .contains(admin.getDisplayName())
                .contains(task.reference())
                .contains("\"" + TITLE + "\"")
                .contains("notification settings");
        assertThat(mailpit.countTo(assignee.getEmail())).isEqualTo(1);
    }

    @Test
    void creatingUnassignedTaskEmailsNobody() throws Exception {
        User admin = createUser(UserRole.ADMIN);

        createTask(admin, null);

        waitForAsyncDispatch();
        assertThat(mailpit.countTo(admin.getEmail())).isZero();
    }

    @Test
    void assigningTaskEmailsNewAssignee() throws Exception {
        User admin = createUser(UserRole.ADMIN);
        User assignee = createUser(UserRole.USER);
        CreatedTask task = createTask(admin, null);

        assign(admin, task, assignee).andExpect(status().isOk());

        String text = awaitTextWithSubject(assignee, assignedSubject(task));

        assertThat(text)
                .contains("Hello " + assignee.getDisplayName() + ",")
                .contains(admin.getDisplayName() + " assigned you the task " + task.reference());
    }

    @Test
    void reassigningTaskEmailsNewAssigneeAndPreviousAssignee() throws Exception {
        User admin = createUser(UserRole.ADMIN);
        User previous = createUser(UserRole.USER);
        User next = createUser(UserRole.USER);
        CreatedTask task = createTask(admin, previous);
        awaitTextWithSubject(previous, assignedSubject(task));

        assign(admin, task, next).andExpect(status().isOk());

        String nextText = awaitTextWithSubject(next, assignedSubject(task));
        String previousText = awaitTextWithSubject(previous, unassignedSubject(task));

        assertThat(nextText).contains("Hello " + next.getDisplayName() + ",");
        assertThat(previousText)
                .contains("Hello " + previous.getDisplayName() + ",")
                .contains(admin.getDisplayName())
                .contains(task.reference())
                .contains("notification settings");
        assertThat(mailpit.countTo(previous.getEmail())).isEqualTo(2);
        assertThat(mailpit.countTo(next.getEmail())).isEqualTo(1);
    }

    @Test
    void reassigningTaskToCurrentAssigneeSendsNothingNew() throws Exception {
        User admin = createUser(UserRole.ADMIN);
        User assignee = createUser(UserRole.USER);
        CreatedTask task = createTask(admin, assignee);
        awaitTextWithSubject(assignee, assignedSubject(task));

        assign(admin, task, assignee).andExpect(status().isOk());

        waitForAsyncDispatch();
        assertThat(mailpit.countTo(assignee.getEmail())).isEqualTo(1);
    }

    @Test
    void adminAssigningTaskToThemselvesOnCreationIsNotEmailed() throws Exception {
        User admin = createUser(UserRole.ADMIN);

        createTask(admin, admin);

        waitForAsyncDispatch();
        assertThat(mailpit.countTo(admin.getEmail())).isZero();
    }

    @Test
    void adminReassigningTaskToThemselvesEmailsOnlyThePreviousAssignee() throws Exception {
        User admin = createUser(UserRole.ADMIN);
        User previous = createUser(UserRole.USER);
        CreatedTask task = createTask(admin, previous);
        awaitTextWithSubject(previous, assignedSubject(task));

        assign(admin, task, admin).andExpect(status().isOk());

        awaitTextWithSubject(previous, unassignedSubject(task));
        waitForAsyncDispatch();
        assertThat(mailpit.countTo(admin.getEmail())).isZero();
    }

    @Test
    void adminReassigningTaskAwayFromThemselvesDoesNotEmailThemselves() throws Exception {
        User admin = createUser(UserRole.ADMIN);
        User next = createUser(UserRole.USER);
        CreatedTask task = createTask(admin, admin);

        assign(admin, task, next).andExpect(status().isOk());

        awaitTextWithSubject(next, assignedSubject(task));
        waitForAsyncDispatch();
        assertThat(mailpit.countTo(admin.getEmail())).isZero();
    }

    // ---------------------------------------------------------------- cancellation

    @Test
    void cancellingTaskEmailsAssigneeWithReason() throws Exception {
        User admin = createUser(UserRole.ADMIN);
        User assignee = createUser(UserRole.USER);
        CreatedTask task = createTask(admin, assignee);

        cancel(admin, task, "Customer withdrew the request").andExpect(status().isOk());

        String text = awaitTextWithSubject(assignee, cancelledSubject(task));

        assertThat(text)
                .contains("Hello " + assignee.getDisplayName() + ",")
                .contains(admin.getDisplayName() + " cancelled the task " + task.reference())
                .contains("\"" + TITLE + "\"")
                .contains("Reason: Customer withdrew the request")
                .contains("notification settings");
    }

    @Test
    void changingStatusToCancelledEmailsAssigneeWithoutReason() throws Exception {
        User admin = createUser(UserRole.ADMIN);
        User assignee = createUser(UserRole.USER);
        CreatedTask task = createTask(admin, assignee);

        changeStatus(asAdmin(admin), task, "CANCELLED").andExpect(status().isOk());

        String text = awaitTextWithSubject(assignee, cancelledSubject(task));

        assertThat(text)
                .contains("Hello " + assignee.getDisplayName() + ",")
                .contains(admin.getDisplayName() + " cancelled the task " + task.reference())
                .doesNotContain("Reason:");
    }

    @Test
    void changingStatusToAnythingButCancelledSendsNothing() throws Exception {
        User admin = createUser(UserRole.ADMIN);
        User assignee = createUser(UserRole.USER);
        CreatedTask task = createTask(admin, assignee);
        awaitTextWithSubject(assignee, assignedSubject(task));

        changeStatus(asAdmin(admin), task, "IN_PROGRESS").andExpect(status().isOk());
        changeStatus(asAdmin(admin), task, "DONE").andExpect(status().isOk());

        waitForAsyncDispatch();
        assertThat(mailpit.countTo(assignee.getEmail())).isEqualTo(1);
    }

    @Test
    void assigneeCancellingTheirOwnTaskIsNotEmailed() throws Exception {
        User admin = createUser(UserRole.ADMIN);
        User assignee = createUser(UserRole.USER);
        CreatedTask task = createTask(admin, assignee);
        awaitTextWithSubject(assignee, assignedSubject(task));

        mockMvc.perform(post(TASKS + "/" + task.id() + "/cancel")
                        .with(asUser(assignee))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"reason\": \"Not needed\"}"))
                .andExpect(status().isOk());

        waitForAsyncDispatch();
        assertThat(countWithSubject(assignee, cancelledSubject(task))).isZero();
    }

    @Test
    void assigneeChangingTheirOwnTaskToCancelledIsNotEmailed() throws Exception {
        User admin = createUser(UserRole.ADMIN);
        User assignee = createUser(UserRole.USER);
        CreatedTask task = createTask(admin, assignee);
        awaitTextWithSubject(assignee, assignedSubject(task));

        changeStatus(asUser(assignee), task, "CANCELLED").andExpect(status().isOk());

        waitForAsyncDispatch();
        assertThat(countWithSubject(assignee, cancelledSubject(task))).isZero();
    }

    @Test
    void cancellingUnassignedTaskEmailsNobody() throws Exception {
        User admin = createUser(UserRole.ADMIN);
        CreatedTask task = createTask(admin, null);

        cancel(admin, task, "Obsolete").andExpect(status().isOk());

        waitForAsyncDispatch();
        assertThat(mailpit.countTo(admin.getEmail())).isZero();
    }

    // ---------------------------------------------------------------- deletion

    @Test
    void deletingTaskEmailsAssignee() throws Exception {
        User admin = createUser(UserRole.ADMIN);
        User assignee = createUser(UserRole.USER);
        CreatedTask task = createTask(admin, assignee);

        deleteTask(admin, task).andExpect(status().isNoContent());

        String text = awaitTextWithSubject(assignee, deletedSubject(task));

        assertThat(text)
                .contains("Hello " + assignee.getDisplayName() + ",")
                .contains(admin.getDisplayName() + " deleted the task " + task.reference())
                .contains("\"" + TITLE + "\"")
                .contains("notification settings");
    }

    @Test
    void deletingTaskEmailsTheAssigneeAtDeletionTimeOnly() throws Exception {
        User admin = createUser(UserRole.ADMIN);
        User previous = createUser(UserRole.USER);
        User current = createUser(UserRole.USER);
        CreatedTask task = createTask(admin, previous);
        assign(admin, task, current).andExpect(status().isOk());
        awaitTextWithSubject(previous, unassignedSubject(task));

        deleteTask(admin, task).andExpect(status().isNoContent());

        awaitTextWithSubject(current, deletedSubject(task));
        waitForAsyncDispatch();
        assertThat(countWithSubject(previous, deletedSubject(task))).isZero();
    }

    @Test
    void adminDeletingTaskAssignedToThemselvesIsNotEmailed() throws Exception {
        User admin = createUser(UserRole.ADMIN);
        CreatedTask task = createTask(admin, admin);

        deleteTask(admin, task).andExpect(status().isNoContent());

        waitForAsyncDispatch();
        assertThat(mailpit.countTo(admin.getEmail())).isZero();
    }

    // ---------------------------------------------------------------- inactive recipients

    @Test
    void reassigningTaskAwayFromDeletedUserSucceedsAndEmailsOnlyNewAssignee() throws Exception {
        User admin = createUser(UserRole.ADMIN);
        User previous = createUser(UserRole.USER);
        User next = createUser(UserRole.USER);
        CreatedTask task = createTask(admin, previous);
        awaitTextWithSubject(previous, assignedSubject(task));
        deleteUserThroughApi(previous);

        assign(admin, task, next).andExpect(status().isOk());

        awaitTextWithSubject(next, assignedSubject(task));
        waitForAsyncDispatch();
        assertThat(countWithSubject(previous, unassignedSubject(task))).isZero();
    }

    @Test
    void reassigningTaskAwayFromDisabledUserDoesNotEmailThem() throws Exception {
        User admin = createUser(UserRole.ADMIN);
        User previous = createUser(UserRole.USER);
        User next = createUser(UserRole.USER);
        CreatedTask task = createTask(admin, previous);
        awaitTextWithSubject(previous, assignedSubject(task));
        disable(previous);

        assign(admin, task, next).andExpect(status().isOk());

        awaitTextWithSubject(next, assignedSubject(task));
        waitForAsyncDispatch();
        assertThat(countWithSubject(previous, unassignedSubject(task))).isZero();
    }

    @Test
    void reassigningTaskAwayFromUnverifiedUserDoesNotEmailThem() throws Exception {
        User admin = createUser(UserRole.ADMIN);
        User previous = createUser(UserRole.USER);
        User next = createUser(UserRole.USER);
        CreatedTask task = createTask(admin, previous);
        awaitTextWithSubject(previous, assignedSubject(task));
        unverify(previous);

        assign(admin, task, next).andExpect(status().isOk());

        awaitTextWithSubject(next, assignedSubject(task));
        waitForAsyncDispatch();
        assertThat(countWithSubject(previous, unassignedSubject(task))).isZero();
    }

    @Test
    void cancellingTaskOfDisabledAssigneeSendsNothing() throws Exception {
        User admin = createUser(UserRole.ADMIN);
        User assignee = createUser(UserRole.USER);
        CreatedTask task = createTask(admin, assignee);
        awaitTextWithSubject(assignee, assignedSubject(task));
        disable(assignee);

        cancel(admin, task, "Obsolete").andExpect(status().isOk());

        waitForAsyncDispatch();
        assertThat(countWithSubject(assignee, cancelledSubject(task))).isZero();
    }

    @Test
    void cancellingTaskOfUnverifiedAssigneeSendsNothing() throws Exception {
        User admin = createUser(UserRole.ADMIN);
        User assignee = createUser(UserRole.USER);
        CreatedTask task = createTask(admin, assignee);
        awaitTextWithSubject(assignee, assignedSubject(task));
        unverify(assignee);

        changeStatus(asAdmin(admin), task, "CANCELLED").andExpect(status().isOk());

        waitForAsyncDispatch();
        assertThat(countWithSubject(assignee, cancelledSubject(task))).isZero();
    }

    @Test
    void deletingTaskOfDeletedAssigneeSucceedsAndSendsNothing() throws Exception {
        User admin = createUser(UserRole.ADMIN);
        User assignee = createUser(UserRole.USER);
        CreatedTask task = createTask(admin, assignee);
        awaitTextWithSubject(assignee, assignedSubject(task));
        deleteUserThroughApi(assignee);

        deleteTask(admin, task).andExpect(status().isNoContent());

        waitForAsyncDispatch();
        assertThat(countWithSubject(assignee, deletedSubject(task))).isZero();
    }

    @Test
    void cancellingTaskOfDeletedAssigneeSucceedsAndSendsNothing() throws Exception {
        User admin = createUser(UserRole.ADMIN);
        User assignee = createUser(UserRole.USER);
        CreatedTask task = createTask(admin, assignee);
        awaitTextWithSubject(assignee, assignedSubject(task));
        deleteUserThroughApi(assignee);

        cancel(admin, task, "Assignee left").andExpect(status().isOk());

        waitForAsyncDispatch();
        assertThat(countWithSubject(assignee, cancelledSubject(task))).isZero();
    }

    // ---------------------------------------------------------------- failed requests

    @Test
    void assigningInactiveUserFailsAndEmailsNobody() throws Exception {
        User admin = createUser(UserRole.ADMIN);
        User current = createUser(UserRole.USER);
        User disabled = createUser(UserRole.USER);
        disable(disabled);
        CreatedTask task = createTask(admin, current);
        awaitTextWithSubject(current, assignedSubject(task));

        assign(admin, task, disabled).andExpect(status().isUnprocessableContent());

        waitForAsyncDispatch();
        assertThat(mailpit.countTo(current.getEmail())).isEqualTo(1);
        assertThat(mailpit.countTo(disabled.getEmail())).isZero();
    }

    @Test
    void creatingTaskAssignedToInactiveUserFailsAndEmailsNobody() throws Exception {
        User admin = createUser(UserRole.ADMIN);
        User unverified = createUser(UserRole.USER);
        unverify(unverified);

        mockMvc.perform(post(TASKS)
                        .with(asAdmin(admin))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(createTaskBody(uniqueReference(), unverified)))
                .andExpect(status().isUnprocessableContent());

        waitForAsyncDispatch();
        assertThat(mailpit.countTo(unverified.getEmail())).isZero();
    }

    // ---------------------------------------------------------------- notification settings

    @Test
    void assigneeWithTaskAssignedOffIsNotEmailedOnAssignment() throws Exception {
        User admin = createUser(UserRole.ADMIN);
        User assignee = createUser(UserRole.USER);
        updateSettings(assignee, false, true, true, true);

        createTask(admin, assignee);

        waitForAsyncDispatch();
        assertThat(mailpit.countTo(assignee.getEmail())).isZero();
    }

    @Test
    void previousAssigneeWithTaskUnassignedOffIsNotEmailedWhileNewAssigneeIs() throws Exception {
        User admin = createUser(UserRole.ADMIN);
        User previous = createUser(UserRole.USER);
        User next = createUser(UserRole.USER);
        CreatedTask task = createTask(admin, previous);
        awaitTextWithSubject(previous, assignedSubject(task));
        updateSettings(previous, true, false, true, true);

        assign(admin, task, next).andExpect(status().isOk());

        awaitTextWithSubject(next, assignedSubject(task));
        waitForAsyncDispatch();
        assertThat(countWithSubject(previous, unassignedSubject(task))).isZero();
    }

    @Test
    void assigneeWithTaskCancelledOffIsNotEmailedOnCancellation() throws Exception {
        User admin = createUser(UserRole.ADMIN);
        User assignee = createUser(UserRole.USER);
        CreatedTask task = createTask(admin, assignee);
        awaitTextWithSubject(assignee, assignedSubject(task));
        updateSettings(assignee, true, true, false, true);

        cancel(admin, task, "Obsolete").andExpect(status().isOk());

        waitForAsyncDispatch();
        assertThat(countWithSubject(assignee, cancelledSubject(task))).isZero();
    }

    @Test
    void assigneeWithTaskDeletedOffIsNotEmailedOnDeletion() throws Exception {
        User admin = createUser(UserRole.ADMIN);
        User assignee = createUser(UserRole.USER);
        CreatedTask task = createTask(admin, assignee);
        awaitTextWithSubject(assignee, assignedSubject(task));
        updateSettings(assignee, true, true, true, false);

        deleteTask(admin, task).andExpect(status().isNoContent());

        waitForAsyncDispatch();
        assertThat(countWithSubject(assignee, deletedSubject(task))).isZero();
    }

    @Test
    void turningOneSwitchOffKeepsTheOtherEmails() throws Exception {
        User admin = createUser(UserRole.ADMIN);
        User assignee = createUser(UserRole.USER);
        updateSettings(assignee, false, false, true, false);
        CreatedTask task = createTask(admin, assignee);

        cancel(admin, task, "Obsolete").andExpect(status().isOk());

        awaitTextWithSubject(assignee, cancelledSubject(task));
        assertThat(countWithSubject(assignee, assignedSubject(task))).isZero();
    }

    @Test
    void turningSwitchBackOnRestoresTheEmail() throws Exception {
        User admin = createUser(UserRole.ADMIN);
        User assignee = createUser(UserRole.USER);
        updateSettings(assignee, false, true, true, true);
        updateSettings(assignee, true, true, true, true);

        CreatedTask task = createTask(admin, assignee);

        awaitTextWithSubject(assignee, assignedSubject(task));
    }

    // ---------------------------------------------------------------- fixtures

    private User createUser(UserRole role) {
        User user = new User();

        user.setEmail(UUID.randomUUID() + "@example.com");
        user.setPasswordHash(passwordEncoder.encode("password"));
        // Only enabled users with a verified email can work on tasks and receive notifications
        user.setEmailVerifiedAt(Instant.now());
        user.setDisplayName("Notified " + role + " " + UUID.randomUUID().toString().substring(0, 6));
        user.setRoles(EnumSet.of(UserRole.USER, role));

        return userRepository.saveAndFlush(user);
    }

    private void disable(User user) {
        User reloaded = userRepository.findById(user.getId()).orElseThrow();
        reloaded.setEnabled(false);
        userRepository.saveAndFlush(reloaded);
    }

    private void unverify(User user) {
        User reloaded = userRepository.findById(user.getId()).orElseThrow();
        reloaded.setEmailVerifiedAt(null);
        userRepository.saveAndFlush(reloaded);
    }

    private void deleteUserThroughApi(User user) throws Exception {
        User admin = createUser(UserRole.ADMIN);

        mockMvc.perform(delete("/api/v1/users/" + user.getId()).with(asAdmin(admin)))
                .andExpect(status().isNoContent());
    }

    private RequestPostProcessor as(User user, UserRole role) {
        return jwt()
                .jwt(token -> token.claim("uid", user.getId().toString()))
                .authorities(new SimpleGrantedAuthority("ROLE_" + role.name()));
    }

    private RequestPostProcessor asAdmin(User admin) {
        return as(admin, UserRole.ADMIN);
    }

    private RequestPostProcessor asUser(User user) {
        return as(user, UserRole.USER);
    }

    private String uniqueReference() {
        return "N-" + UUID.randomUUID().toString().substring(0, 8);
    }

    private String createTaskBody(String reference, User assignee) {
        String assigned = assignee == null ? "null" : "\"" + assignee.getId() + "\"";

        return """
                {"reference": "%s", "title": "%s", "description": "Initial description",
                 "priority": "LOW", "dueAt": "2030-01-01T10:00:00Z", "assignedTo": %s}
                """.formatted(reference, TITLE, assigned);
    }

    private CreatedTask createTask(User admin, User assignee) throws Exception {
        String reference = uniqueReference();

        String body = mockMvc.perform(post(TASKS)
                        .with(asAdmin(admin))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(createTaskBody(reference, assignee)))
                .andExpect(status().isCreated())
                .andReturn()
                .getResponse()
                .getContentAsString();

        return new CreatedTask(UUID.fromString(jsonMapper.readTree(body).get("id").asString()), reference);
    }

    private ResultActions assign(User admin, CreatedTask task, User assignee) throws Exception {
        return mockMvc.perform(patch(TASKS + "/" + task.id() + "/assign")
                .with(asAdmin(admin))
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"userId\": \"" + assignee.getId() + "\"}"));
    }

    private ResultActions cancel(User admin, CreatedTask task, String reason) throws Exception {
        return mockMvc.perform(post(TASKS + "/" + task.id() + "/cancel")
                .with(asAdmin(admin))
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"reason\": \"" + reason + "\"}"));
    }

    private ResultActions changeStatus(RequestPostProcessor actor, CreatedTask task, String status) throws Exception {
        return mockMvc.perform(patch(TASKS + "/" + task.id() + "/status")
                .with(actor)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"status\": \"" + status + "\"}"));
    }

    private ResultActions deleteTask(User admin, CreatedTask task) throws Exception {
        return mockMvc.perform(delete(TASKS + "/" + task.id()).with(asAdmin(admin)));
    }

    private void updateSettings(
            User user, boolean assigned, boolean unassigned, boolean cancelled, boolean deleted
    ) throws Exception {
        mockMvc.perform(put("/api/v1/users/{id}/notification-settings", user.getId())
                        .with(asUser(user))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"taskAssigned": %s, "taskUnassigned": %s, "taskCancelled": %s, "taskDeleted": %s}
                                """.formatted(assigned, unassigned, cancelled, deleted)))
                .andExpect(status().isOk());
    }

    // ---------------------------------------------------------------- mail

    private static String assignedSubject(CreatedTask task) {
        return "Task " + task.reference() + " was assigned to you";
    }

    private static String unassignedSubject(CreatedTask task) {
        return "Task " + task.reference() + " is no longer assigned to you";
    }

    private static String cancelledSubject(CreatedTask task) {
        return "Task " + task.reference() + " was cancelled";
    }

    private static String deletedSubject(CreatedTask task) {
        return "Task " + task.reference() + " was deleted";
    }

    private int countWithSubject(User recipient, String subject) {
        return searchBySubject(recipient, subject).path("messages_count").asInt();
    }

    /** Plain-text body of the latest email with this subject, waiting up to 5 seconds for the async dispatch. */
    private String awaitTextWithSubject(User recipient, String subject) throws InterruptedException {
        Instant deadline = Instant.now().plusSeconds(5);

        while (true) {
            JsonNode messages = searchBySubject(recipient, subject).path("messages");

            if (!messages.isEmpty()) {
                // Mailpit's substring match would also accept a longer subject
                assertThat(messages.get(0).path("Subject").asString()).isEqualTo(subject);

                String id = messages.get(0).path("ID").asString();

                return readMailpit("/api/v1/message/{id}", id).path("Text").asString();
            }

            if (Instant.now().isAfter(deadline)) {
                throw new AssertionError("No email \"" + subject + "\" received by " + recipient.getEmail());
            }

            Thread.sleep(100);
        }
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

    private static void waitForAsyncDispatch() throws InterruptedException {
        Thread.sleep(NO_MAIL_GRACE_PERIOD);
    }
}
