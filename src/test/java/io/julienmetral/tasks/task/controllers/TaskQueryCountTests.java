package io.julienmetral.tasks.task.controllers;

import com.jayway.jsonpath.JsonPath;
import io.julienmetral.tasks.identity.entities.User;
import io.julienmetral.tasks.identity.entities.UserRole;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

import static io.julienmetral.tasks.support.ProfilePhotos.givePhoto;
import static io.julienmetral.tasks.support.SqlStatementCounter.statementsDuring;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class TaskQueryCountTests extends AbstractTaskCommentApiTests {

    // Measured with open-in-view off, the same for the short and the long list, each including the reload of the
    // caller by ActiveUserAuthorizationManager. Comments take 7: the photos of the mentioned users and the media of
    // the files are two batch queries of their own.
    private static final int TASK_PAGE_STATEMENTS = 3;
    private static final int TASK_STATEMENTS = 2;
    private static final int EVENT_PAGE_STATEMENTS = 4;
    private static final int COMMENT_PAGE_STATEMENTS = 7;
    private static final int ATTACHMENT_LIST_STATEMENTS = 3;

    // Longer than every list below: Spring Data skips the count query for a first page that is not full, so a size
    // between the short and the long list would add a statement to the long one only
    private static final String PAGE_SIZE = "50";

    private record Response(String body, List<String> statements) {
    }

    @Test
    void listingTasksRunsTheSameNumberOfQueriesWhateverTheNumberOfTasksAndUsers() throws Exception {
        User reader = createUser(UserRole.USER);

        Response fewTasks = listNewestTasks(reader, tasksWithDistinctUsers(2));
        Response manyTasks = listNewestTasks(reader, tasksWithDistinctUsers(20));

        assertThat(photoUrls(manyTasks, "$.content[*].assignedTo.avatarUrl")).hasSize(10);
        assertThat(photoUrls(manyTasks, "$.content[*].createdBy.avatarUrl")).hasSize(10);
        assertThat(manyTasks.statements())
                .hasSameSizeAs(fewTasks.statements())
                .hasSizeLessThanOrEqualTo(TASK_PAGE_STATEMENTS);
    }

    @Test
    void readingATaskRunsTheSameNumberOfQueriesWithOrWithoutUsersToShow() throws Exception {
        User reader = createUser(UserRole.USER);
        User creator = createUser(UserRole.ADMIN);
        User assignee = createUser(UserRole.USER);
        givePhoto(jdbcTemplate, creator.getId());
        givePhoto(jdbcTemplate, assignee.getId());
        UUID assignedTask = createTask(creator, assignee);
        UUID unassignedTask = createTask(createUser(UserRole.ADMIN), null);

        Response assigned = read(reader, TASKS + "/" + assignedTask);
        Response unassigned = read(reader, TASKS + "/" + unassignedTask);

        assertThat(photoUrls(assigned, "$..avatarUrl")).hasSize(2);
        assertThat(assigned.statements())
                .hasSameSizeAs(unassigned.statements())
                .hasSizeLessThanOrEqualTo(TASK_STATEMENTS);
    }

    @Test
    void listingEventsRunsTheSameNumberOfQueriesWhateverTheNumberOfActors() throws Exception {
        User reader = createUser(UserRole.USER);

        UUID fewEventsTask = taskUpdatedByDistinctAdmins(1);
        UUID manyEventsTask = taskUpdatedByDistinctAdmins(14);

        Response fewEvents = read(reader, TASKS + "/" + fewEventsTask + "/events?size=" + PAGE_SIZE);
        Response manyEvents = read(reader, TASKS + "/" + manyEventsTask + "/events?size=" + PAGE_SIZE);

        assertThat(values(fewEvents, "$.content[*].actor.id")).hasSize(2);
        assertThat(values(manyEvents, "$.content[*].actor.id")).hasSize(15).doesNotHaveDuplicates();
        assertThat(photoUrls(manyEvents, "$.content[*].actor.avatarUrl")).hasSize(8);
        assertThat(manyEvents.statements())
                .hasSameSizeAs(fewEvents.statements())
                .hasSizeLessThanOrEqualTo(EVENT_PAGE_STATEMENTS);
    }

    @Test
    void listingCommentsRunsTheSameNumberOfQueriesWhateverTheNumberOfAuthorsMentionsAndFiles() throws Exception {
        User reader = createUser(UserRole.USER);

        UUID fewCommentsTask = taskWithCommentsByDistinctAuthors(2);
        UUID manyCommentsTask = taskWithCommentsByDistinctAuthors(10);

        Response fewComments = read(reader, commentsOf(fewCommentsTask) + "?size=" + PAGE_SIZE);
        Response manyComments = read(reader, commentsOf(manyCommentsTask) + "?size=" + PAGE_SIZE);

        assertThat(values(manyComments, "$.content[*].author.id")).hasSize(10).doesNotHaveDuplicates();
        assertThat(values(manyComments, "$.content[*].mentions[*].id")).hasSize(10).doesNotHaveDuplicates();
        assertThat(values(manyComments, "$.content[*].attachments[*].id")).hasSize(10);
        assertThat(photoUrls(manyComments, "$.content[*].author.avatarUrl")).hasSize(5);
        assertThat(photoUrls(manyComments, "$.content[*].mentions[*].avatarUrl")).hasSize(5);
        assertThat(photoUrls(manyComments, "$.content[*].attachments[*].uploadedBy.avatarUrl")).hasSize(5);
        assertThat(manyComments.statements())
                .hasSameSizeAs(fewComments.statements())
                .hasSizeLessThanOrEqualTo(COMMENT_PAGE_STATEMENTS);
    }

    @Test
    void listingAttachmentsRunsTheSameNumberOfQueriesWhateverTheNumberOfUploaders() throws Exception {
        User reader = createUser(UserRole.USER);

        Response fewFiles = read(reader, attachmentsOf(taskWithFilesByDistinctUploaders(2)));
        Response manyFiles = read(reader, attachmentsOf(taskWithFilesByDistinctUploaders(8)));

        assertThat(values(manyFiles, "$[*].uploadedBy.id")).hasSize(8).doesNotHaveDuplicates();
        assertThat(photoUrls(manyFiles, "$[*].uploadedBy.avatarUrl")).hasSize(4);
        assertThat(manyFiles.statements())
                .hasSameSizeAs(fewFiles.statements())
                .hasSizeLessThanOrEqualTo(ATTACHMENT_LIST_STATEMENTS);
    }

    private Response read(User reader, String uri) throws Exception {
        AtomicReference<String> body = new AtomicReference<>();

        List<String> statements = statementsDuring(() -> body.set(mockMvc.perform(get(uri).with(asUser(reader)))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString()));

        return new Response(body.get(), statements);
    }

    // The list is shared with every other test class: the page is sized to the tasks just created, which are the
    // newest, and the test checks it holds exactly them before trusting the count
    private Response listNewestTasks(User reader, List<UUID> newestFirst) throws Exception {
        Response page = read(reader, TASKS + "?size=" + newestFirst.size());

        assertThat(values(page, "$.content[*].id"))
                .containsExactlyElementsOf(newestFirst.stream().map(UUID::toString).toList());

        return page;
    }

    // Newest first; every other task has a creator and an assignee with a photo
    private List<UUID> tasksWithDistinctUsers(int count) throws Exception {
        List<UUID> newestFirst = new ArrayList<>();

        for (int i = 0; i < count; i++) {
            User creator = createUser(UserRole.ADMIN);
            User assignee = createUser(UserRole.USER);

            if (i % 2 == 0) {
                givePhoto(jdbcTemplate, creator.getId());
                givePhoto(jdbcTemplate, assignee.getId());
            }

            newestFirst.addFirst(createTask(creator, assignee));
        }

        return newestFirst;
    }

    private UUID taskUpdatedByDistinctAdmins(int updates) throws Exception {
        User creator = createUser(UserRole.ADMIN);
        givePhoto(jdbcTemplate, creator.getId());
        UUID taskId = createTask(creator, null);

        for (int i = 0; i < updates; i++) {
            User editor = createUser(UserRole.ADMIN);

            if (i % 2 == 1) {
                givePhoto(jdbcTemplate, editor.getId());
            }

            mockMvc.perform(patch(TASKS + "/" + taskId)
                            .with(asAdmin(editor))
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"description\": \"Revision " + i + "\"}"))
                    .andExpect(status().isOk());
        }

        return taskId;
    }

    // Each comment also mentions a user of its own and carries one file
    private UUID taskWithCommentsByDistinctAuthors(int count) throws Exception {
        UUID taskId = createTask(createUser(UserRole.ADMIN), null);

        for (int i = 0; i < count; i++) {
            User author = createUser(UserRole.USER);
            User mentioned = createUser(UserRole.USER);

            if (i % 2 == 0) {
                givePhoto(jdbcTemplate, author.getId());
                givePhoto(jdbcTemplate, mentioned.getId());
            }

            addComment(asUser(author), taskId, "Please check " + mention(mentioned), pdfFile("notes-" + i + ".pdf"));
        }

        return taskId;
    }

    private UUID taskWithFilesByDistinctUploaders(int count) throws Exception {
        UUID taskId = createTask(createUser(UserRole.ADMIN), null);

        for (int i = 0; i < count; i++) {
            User uploader = createUser(UserRole.ADMIN);

            if (i % 2 == 0) {
                givePhoto(jdbcTemplate, uploader.getId());
            }

            uploadPdf(asAdmin(uploader), taskId, "file-" + i + ".pdf");
        }

        return taskId;
    }

    private static List<String> values(Response response, String jsonPath) {
        return JsonPath.read(response.body(), jsonPath);
    }

    private static List<String> photoUrls(Response response, String avatarUrls) {
        return values(response, avatarUrls)
                .stream()
                .filter(url -> !url.contains("/identicons/"))
                .toList();
    }
}
