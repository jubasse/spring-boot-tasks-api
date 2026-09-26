package io.julienmetral.tasks.task.controllers;

import io.julienmetral.tasks.identity.entities.User;
import io.julienmetral.tasks.identity.entities.UserRole;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.JsonNode;

import java.time.Duration;
import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Comment and mention emails, end to end: comments go through the API, and the emails sent after commit are read back
 * from the Mailpit container.
 */
class TaskCommentNotificationApiTests extends AbstractTaskCommentApiTests {

    // Mail is dispatched asynchronously after commit: "nothing sent" can only be checked after a grace period
    private static final Duration NO_MAIL_GRACE_PERIOD = Duration.ofMillis(800);

    @Value("${mailpit.api-url}")
    private String mailpitApiUrl;

    @Test
    void assigneeReceivesTheCommentWithMentionsRenderedAsDisplayNames() throws Exception {
        User admin = createUser(UserRole.ADMIN);
        User assignee = createUser(UserRole.USER);
        User author = createUser(UserRole.USER);
        User mentioned = createUser(UserRole.USER);
        UUID taskId = createTask(admin, assignee);
        String reference = referenceOf(taskId);

        addComment(asUser(author), taskId, "Please review this with " + mention(mentioned) + " today.");

        String text = awaitTextWithSubject(assignee, commentSubject(reference));

        assertThat(text)
                .contains("Hello " + assignee.getDisplayName() + ",")
                .contains(author.getDisplayName() + " commented on the task " + reference)
                .contains("Please review this with @" + mentioned.getDisplayName() + " today.")
                .doesNotContain("<@")
                .contains("notification settings");
    }

    @Test
    void mentionedUserReceivesTheMentionWithTheRenderedExcerpt() throws Exception {
        User admin = createUser(UserRole.ADMIN);
        User author = createUser(UserRole.USER);
        User mentioned = createUser(UserRole.USER);
        User other = createUser(UserRole.USER);
        UUID taskId = createTask(admin, null);
        String reference = referenceOf(taskId);

        addComment(asUser(author), taskId, mention(mentioned) + " and " + mention(other) + ", see above.");

        String text = awaitTextWithSubject(mentioned, mentionSubject(author, reference));

        assertThat(text)
                .contains("Hello " + mentioned.getDisplayName() + ",")
                .contains(author.getDisplayName() + " mentioned you in a comment on the task " + reference)
                .contains("@" + mentioned.getDisplayName() + " and @" + other.getDisplayName() + ", see above.")
                .doesNotContain("<@");
        awaitTextWithSubject(other, mentionSubject(author, reference));
    }

    @Test
    void mentionedAssigneeReceivesOnlyTheMentionEmail() throws Exception {
        User admin = createUser(UserRole.ADMIN);
        User assignee = createUser(UserRole.USER);
        User author = createUser(UserRole.USER);
        UUID taskId = createTask(admin, assignee);
        String reference = referenceOf(taskId);

        addComment(asUser(author), taskId, "Over to you " + mention(assignee));

        awaitTextWithSubject(assignee, mentionSubject(author, reference));
        waitForAsyncDispatch();
        assertThat(countWithSubject(assignee, commentSubject(reference))).isZero();
    }

    @Test
    void assigneeCommentingOnTheirOwnTaskIsNotEmailed() throws Exception {
        User admin = createUser(UserRole.ADMIN);
        User assignee = createUser(UserRole.USER);
        User mentioned = createUser(UserRole.USER);
        UUID taskId = createTask(admin, assignee);
        String reference = referenceOf(taskId);

        addComment(asUser(assignee), taskId, "Done, " + mention(mentioned) + " and " + mention(assignee));

        awaitTextWithSubject(mentioned, mentionSubject(assignee, reference));
        waitForAsyncDispatch();
        assertThat(countWithSubject(assignee, commentSubject(reference))).isZero();
        assertThat(countWithSubject(assignee, mentionSubject(assignee, reference))).isZero();
    }

    @Test
    void authorMentioningThemselvesIsNotEmailed() throws Exception {
        User admin = createUser(UserRole.ADMIN);
        User author = createUser(UserRole.USER);
        UUID taskId = createTask(admin, null);
        String reference = referenceOf(taskId);

        addComment(asUser(author), taskId, "Reminder for " + mention(author));

        waitForAsyncDispatch();
        assertThat(countWithSubject(author, mentionSubject(author, reference))).isZero();
        assertThat(countTo(author)).isZero();
    }

    @Test
    void commentOnUnassignedTaskWithoutMentionsEmailsNobody() throws Exception {
        User admin = createUser(UserRole.ADMIN);
        User author = createUser(UserRole.USER);
        UUID taskId = createTask(admin, null);

        addComment(asUser(author), taskId, "Anyone?");

        waitForAsyncDispatch();
        assertThat(countTo(admin)).isZero();
        assertThat(countTo(author)).isZero();
    }

    @Test
    void assigneeDisabledSinceTheAssignmentIsNotEmailedAboutComments() throws Exception {
        User admin = createUser(UserRole.ADMIN);
        User assignee = createUser(UserRole.USER);
        User author = createUser(UserRole.USER);
        UUID taskId = createTask(admin, assignee);
        disableThroughApi(assignee);

        addComment(asUser(author), taskId, "Anyone there?");

        waitForAsyncDispatch();
        assertThat(countWithSubject(assignee, commentSubject(referenceOf(taskId)))).isZero();
    }

    @Test
    void assigneeWithTaskCommentedOffIsNotEmailedAboutComments() throws Exception {
        User admin = createUser(UserRole.ADMIN);
        User assignee = createUser(UserRole.USER);
        User author = createUser(UserRole.USER);
        User mentioned = createUser(UserRole.USER);
        UUID taskId = createTask(admin, assignee);
        String reference = referenceOf(taskId);
        updateSettings(assignee, false, true);

        addComment(asUser(author), taskId, "FYI " + mention(mentioned));

        awaitTextWithSubject(mentioned, mentionSubject(author, reference));
        waitForAsyncDispatch();
        assertThat(countWithSubject(assignee, commentSubject(reference))).isZero();
    }

    @Test
    void assigneeWithTaskCommentedOffStillReceivesMentions() throws Exception {
        User admin = createUser(UserRole.ADMIN);
        User assignee = createUser(UserRole.USER);
        User author = createUser(UserRole.USER);
        UUID taskId = createTask(admin, assignee);
        updateSettings(assignee, false, true);

        addComment(asUser(author), taskId, "Question for " + mention(assignee));

        awaitTextWithSubject(assignee, mentionSubject(author, referenceOf(taskId)));
    }

    @Test
    void mentionedAssigneeWithTaskMentionedOffReceivesTheCommentEmail() throws Exception {
        User admin = createUser(UserRole.ADMIN);
        User assignee = createUser(UserRole.USER);
        User author = createUser(UserRole.USER);
        UUID taskId = createTask(admin, assignee);
        updateSettings(assignee, true, false);

        addComment(asUser(author), taskId, "Question for " + mention(assignee));

        awaitTextWithSubject(assignee, commentSubject(referenceOf(taskId)));
        waitForAsyncDispatch();
        assertThat(countWithSubject(assignee, mentionSubject(author, referenceOf(taskId)))).isZero();
    }

    @Test
    void userWithTaskMentionedOffIsNotEmailedAboutMentions() throws Exception {
        User admin = createUser(UserRole.ADMIN);
        User assignee = createUser(UserRole.USER);
        User author = createUser(UserRole.USER);
        User mentioned = createUser(UserRole.USER);
        UUID taskId = createTask(admin, assignee);
        String reference = referenceOf(taskId);
        updateSettings(mentioned, true, false);

        addComment(asUser(author), taskId, "Ping " + mention(mentioned));

        awaitTextWithSubject(assignee, commentSubject(reference));
        waitForAsyncDispatch();
        assertThat(countTo(mentioned)).isZero();
    }

    @Test
    void keptMentionOfUserDisabledSinceIsNotEmailedAgainOnEdit() throws Exception {
        User admin = createUser(UserRole.ADMIN);
        User author = createUser(UserRole.USER);
        User mentioned = createUser(UserRole.USER);
        UUID taskId = createTask(admin, null);
        String reference = referenceOf(taskId);
        UUID commentId = addComment(asUser(author), taskId, "For " + mention(mentioned));
        awaitTextWithSubject(mentioned, mentionSubject(author, reference));
        disableThroughApi(mentioned);

        editComment(asUser(author), taskId, commentId, "Still for " + mention(mentioned))
                .andExpect(status().isOk());

        waitForAsyncDispatch();
        assertThat(countWithSubject(mentioned, mentionSubject(author, reference))).isOne();
    }

    @Test
    void editEmailsOnlyNewlyMentionedUsers() throws Exception {
        User admin = createUser(UserRole.ADMIN);
        User assignee = createUser(UserRole.USER);
        User author = createUser(UserRole.USER);
        User first = createUser(UserRole.USER);
        User second = createUser(UserRole.USER);
        UUID taskId = createTask(admin, assignee);
        String reference = referenceOf(taskId);
        UUID commentId = addComment(asUser(author), taskId, "For " + mention(first));
        awaitTextWithSubject(first, mentionSubject(author, reference));
        awaitTextWithSubject(assignee, commentSubject(reference));

        editComment(asUser(author), taskId, commentId, "For " + mention(first) + " and " + mention(second))
                .andExpect(status().isOk());

        String text = awaitTextWithSubject(second, mentionSubject(author, reference));
        assertThat(text).contains("For @" + first.getDisplayName() + " and @" + second.getDisplayName());
        waitForAsyncDispatch();
        assertThat(countWithSubject(first, mentionSubject(author, reference))).isOne();
        assertThat(countWithSubject(assignee, commentSubject(reference))).isOne();
    }

    @Test
    void editWithoutNewMentionsEmailsNobody() throws Exception {
        User admin = createUser(UserRole.ADMIN);
        User assignee = createUser(UserRole.USER);
        User author = createUser(UserRole.USER);
        User mentioned = createUser(UserRole.USER);
        UUID taskId = createTask(admin, assignee);
        String reference = referenceOf(taskId);
        UUID commentId = addComment(asUser(author), taskId, "For " + mention(mentioned));
        awaitTextWithSubject(mentioned, mentionSubject(author, reference));
        awaitTextWithSubject(assignee, commentSubject(reference));

        editComment(asUser(author), taskId, commentId, "Typo fixed, for " + mention(mentioned))
                .andExpect(status().isOk());

        waitForAsyncDispatch();
        assertThat(countWithSubject(mentioned, mentionSubject(author, reference))).isOne();
        assertThat(countWithSubject(assignee, commentSubject(reference))).isOne();
    }

    @Test
    void rejectedCommentEmailsNobody() throws Exception {
        User admin = createUser(UserRole.ADMIN);
        User assignee = createUser(UserRole.USER);
        User author = createUser(UserRole.USER);
        User mentioned = createUser(UserRole.USER);
        User disabled = createDisabledUser(UserRole.USER);
        UUID taskId = createTask(admin, assignee);
        String reference = referenceOf(taskId);

        postComment(asUser(author), taskId, mention(mentioned) + " " + mention(disabled))
                .andExpect(status().isUnprocessableContent());

        waitForAsyncDispatch();
        assertThat(countWithSubject(assignee, commentSubject(reference))).isZero();
        assertThat(countTo(mentioned)).isZero();
    }

    @Test
    void longCommentIsTruncatedInTheEmail() throws Exception {
        User admin = createUser(UserRole.ADMIN);
        User assignee = createUser(UserRole.USER);
        User author = createUser(UserRole.USER);
        UUID taskId = createTask(admin, assignee);
        String head = "a".repeat(1_000);

        addComment(asUser(author), taskId, head + "TAIL-THAT-IS-CUT");

        String text = awaitTextWithSubject(assignee, commentSubject(referenceOf(taskId)));

        assertThat(text)
                .contains(head + "...")
                .doesNotContain("TAIL-THAT-IS-CUT");
    }

    private void updateSettings(User user, boolean taskCommented, boolean taskMentioned) throws Exception {
        mockMvc.perform(put("/api/v1/users/{id}/notification-settings", user.getId())
                        .with(asUser(user))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"taskAssigned": true, "taskUnassigned": true, "taskCancelled": true,
                                 "taskDeleted": true, "taskCommented": %s, "taskMentioned": %s}
                                """.formatted(taskCommented, taskMentioned)))
                .andExpect(status().isOk());
    }

    private static String commentSubject(String reference) {
        return "New comment on task " + reference;
    }

    private static String mentionSubject(User author, String reference) {
        return author.getDisplayName() + " mentioned you on task " + reference;
    }

    private int countTo(User recipient) {
        return readMailpit("/api/v1/search?query={query}", "to:\"" + recipient.getEmail() + "\"")
                .path("messages_count")
                .asInt();
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

        return json(body);
    }

    private static void waitForAsyncDispatch() throws InterruptedException {
        Thread.sleep(NO_MAIL_GRACE_PERIOD);
    }
}
