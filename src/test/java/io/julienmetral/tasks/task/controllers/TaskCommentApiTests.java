package io.julienmetral.tasks.task.controllers;

import io.julienmetral.tasks.identity.entities.User;
import io.julienmetral.tasks.identity.entities.UserRole;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import tools.jackson.databind.JsonNode;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.stream.StreamSupport;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.notNullValue;
import static org.hamcrest.Matchers.nullValue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Posting, listing, editing and deleting task comments, their files and mentions, and the history they leave. */
class TaskCommentApiTests extends AbstractTaskCommentApiTests {

    private static final int MAX_BODY_LENGTH = 10_000;

    @Nested
    class Posting {

        @Test
        void jsonCommentReturnsCreatedWithLocationAuthorAndNoAttachments() throws Exception {
            User admin = createUser(UserRole.ADMIN);
            User author = createUser(UserRole.USER);
            UUID taskId = createTask(admin, null);
            Instant before = Instant.now();

            MockHttpServletResponse response = postComment(asUser(author), taskId, "Looks good to me")
                    .andExpect(status().isCreated())
                    .andExpect(jsonPath("$.body").value("Looks good to me"))
                    .andExpect(jsonPath("$.author.id").value(author.getId().toString()))
                    .andExpect(jsonPath("$.author.displayName").value(author.getDisplayName()))
                    .andExpect(jsonPath("$.author.status").value("ACTIVE"))
                    .andExpect(jsonPath("$.mentions", hasSize(0)))
                    .andExpect(jsonPath("$.attachments", hasSize(0)))
                    .andExpect(jsonPath("$.editedAt").value(nullValue()))
                    .andReturn()
                    .getResponse();

            JsonNode body = json(response.getContentAsString());
            UUID commentId = UUID.fromString(body.get("id").asString());

            assertThat(response.getHeader("Location")).isEqualTo(comment(taskId, commentId));
            assertThat(Instant.parse(body.get("createdAt").asString())).isBetween(before, Instant.now());
            assertThat(bodyOf(commentId)).isEqualTo("Looks good to me");
        }

        @Test
        void multipartCommentWithoutFilesIsCreated() throws Exception {
            User admin = createUser(UserRole.ADMIN);
            UUID taskId = createTask(admin, null);

            postComment(asAdmin(admin), taskId, "Only text")
                    .andExpect(status().isCreated());
            UUID commentId = addComment(asAdmin(admin), taskId, "Multipart text", new MockMultipartFile[0]);

            findComment(asAdmin(admin), taskId, commentId)
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.body").value("Multipart text"))
                    .andExpect(jsonPath("$.attachments", hasSize(0)));
        }

        @Test
        void multipartCommentStoresItsFilesAsTaskAttachmentsLinkedToTheComment() throws Exception {
            User admin = createUser(UserRole.ADMIN);
            User author = createUser(UserRole.USER);
            UUID taskId = createTask(admin, null);
            byte[] text = uniqueText();

            MockHttpServletResponse response = postComment(asUser(author), taskId, "Minutes attached",
                    pdfFile("minutes.pdf"), new MockMultipartFile("files", "notes.txt", "text/plain", text))
                    .andExpect(status().isCreated())
                    .andExpect(jsonPath("$.body").value("Minutes attached"))
                    .andExpect(jsonPath("$.attachments", hasSize(2)))
                    .andExpect(jsonPath("$.attachments[0].filename").value("minutes.pdf"))
                    .andExpect(jsonPath("$.attachments[0].contentType").value("application/pdf"))
                    .andExpect(jsonPath("$.attachments[0].uploadedBy.id").value(author.getId().toString()))
                    .andExpect(jsonPath("$.attachments[0].downloadUrl").isNotEmpty())
                    .andExpect(jsonPath("$.attachments[1].filename").value("notes.txt"))
                    .andExpect(jsonPath("$.attachments[1].sizeBytes").value(text.length))
                    .andReturn()
                    .getResponse();

            JsonNode body = json(response.getContentAsString());
            UUID commentId = UUID.fromString(body.get("id").asString());

            assertThat(response.getHeader("Location")).isEqualTo(comment(taskId, commentId));
            for (JsonNode attachment : body.get("attachments")) {
                assertThat(attachment.get("commentId").asString()).isEqualTo(commentId.toString());
            }
            assertThat(attachmentIdsOfComment(commentId)).hasSize(2);

            UUID textAttachmentId = UUID.fromString(body.get("attachments").get(1).get("id").asString());
            assertThat(objectBytes(storageKeyOf(textAttachmentId))).isEqualTo(text);
            assertThat(download(body.get("attachments").get(1).get("downloadUrl").asString()).body())
                    .isEqualTo(text);
        }

        @Test
        void commentFilesAppearInTheTaskAttachmentListWithTheirCommentId() throws Exception {
            User admin = createUser(UserRole.ADMIN);
            UUID taskId = createTask(admin, null);
            UUID direct = uploadPdf(asAdmin(admin), taskId, "direct.pdf");
            UUID commentId = addComment(asAdmin(admin), taskId, "See file", pdfFile("from-comment.pdf"));

            list(asAdmin(admin), taskId)
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$", hasSize(2)))
                    .andExpect(jsonPath("$[0].id").value(direct.toString()))
                    .andExpect(jsonPath("$[0].commentId").value(nullValue()))
                    .andExpect(jsonPath("$[1].filename").value("from-comment.pdf"))
                    .andExpect(jsonPath("$[1].commentId").value(commentId.toString()));
        }

        @Test
        void activeUserWhoIsNotTheAssigneeCanPostFilesWithAComment() throws Exception {
            User admin = createUser(UserRole.ADMIN);
            User assignee = createUser(UserRole.USER);
            User bystander = createUser(UserRole.USER);
            UUID taskId = createTask(admin, assignee);

            postComment(asUser(bystander), taskId, "Here is the log", pdfFile("log.pdf"))
                    .andExpect(status().isCreated())
                    .andExpect(jsonPath("$.attachments", hasSize(1)));

            assertThat(mediaCountUploadedBy(bystander)).isOne();
        }

        @Test
        void fiveFilesAreAccepted() throws Exception {
            User admin = createUser(UserRole.ADMIN);
            UUID taskId = createTask(admin, null);

            postComment(asAdmin(admin), taskId, "Five files", pdfFile("1.pdf"), pdfFile("2.pdf"), pdfFile("3.pdf"),
                    pdfFile("4.pdf"), pdfFile("5.pdf"))
                    .andExpect(status().isCreated())
                    .andExpect(jsonPath("$.attachments", hasSize(5)));

            assertThat(attachmentCount(taskId)).isEqualTo(5);
        }

        @Test
        void sixFilesAreRejectedAndNothingIsStored() throws Exception {
            User admin = createUser(UserRole.ADMIN);
            UUID taskId = createTask(admin, null);
            Set<String> keysBefore = storedAttachmentKeys();

            postComment(asAdmin(admin), taskId, "Six files", pdfFile("1.pdf"), pdfFile("2.pdf"), pdfFile("3.pdf"),
                    pdfFile("4.pdf"), pdfFile("5.pdf"), pdfFile("6.pdf"))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.title").value("Too many files"));

            assertNothingPosted(admin, taskId, keysBefore);
        }

        @Test
        void blankBodyIsRejected() throws Exception {
            User admin = createUser(UserRole.ADMIN);
            UUID taskId = createTask(admin, null);

            postComment(asAdmin(admin), taskId, "   ")
                    .andExpect(status().isBadRequest());
            postComment(asAdmin(admin), taskId, " \n ", pdfFile("report.pdf"))
                    .andExpect(status().isBadRequest());

            assertThat(commentCount(taskId)).isZero();
            assertThat(mediaCountUploadedBy(admin)).isZero();
        }

        @Test
        void missingBodyIsRejected() throws Exception {
            User admin = createUser(UserRole.ADMIN);
            UUID taskId = createTask(admin, null);

            mockMvc.perform(post(commentsOf(taskId))
                            .with(asAdmin(admin))
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{}"))
                    .andExpect(status().isBadRequest());
            postComment(asAdmin(admin), taskId, null, pdfFile("report.pdf"))
                    .andExpect(status().isBadRequest());

            assertThat(commentCount(taskId)).isZero();
            assertThat(mediaCountUploadedBy(admin)).isZero();
        }

        @Test
        void bodyAtTheLengthLimitIsAccepted() throws Exception {
            User admin = createUser(UserRole.ADMIN);
            UUID taskId = createTask(admin, null);

            postComment(asAdmin(admin), taskId, "a".repeat(MAX_BODY_LENGTH))
                    .andExpect(status().isCreated());
        }

        @Test
        void bodyOverTheLengthLimitIsRejected() throws Exception {
            User admin = createUser(UserRole.ADMIN);
            UUID taskId = createTask(admin, null);
            String tooLong = "a".repeat(MAX_BODY_LENGTH + 1);

            postComment(asAdmin(admin), taskId, tooLong)
                    .andExpect(status().isBadRequest());
            postComment(asAdmin(admin), taskId, tooLong, pdfFile("report.pdf"))
                    .andExpect(status().isBadRequest());

            assertThat(commentCount(taskId)).isZero();
            assertThat(mediaCountUploadedBy(admin)).isZero();
        }

        @Test
        void infectedFileRejectsTheWholeCommentAndRemovesTheFilesAlreadyStored() throws Exception {
            User admin = createUser(UserRole.ADMIN);
            User mentioned = createUser(UserRole.USER);
            UUID taskId = createTask(admin, null);
            Set<String> keysBefore = storedAttachmentKeys();

            // The clean PDF comes first, so it is already in the bucket when the antivirus rejects the second file
            postComment(asAdmin(admin), taskId, "Ping " + mention(mentioned),
                    pdfFile("clean.pdf"), file("notes.txt", EICAR))
                    .andExpect(status().isUnprocessableContent())
                    .andExpect(jsonPath("$.title").value("File rejected by the antivirus"));

            assertNothingPosted(admin, taskId, keysBefore);
            assertThat(jdbcTemplate.queryForObject(
                    "select count(*) from task_comment_mentions where user_id = ?", Integer.class, mentioned.getId()
            )).isZero();
        }

        @Test
        void unsupportedFileTypeRejectsTheWholeComment() throws Exception {
            User admin = createUser(UserRole.ADMIN);
            UUID taskId = createTask(admin, null);
            Set<String> keysBefore = storedAttachmentKeys();
            byte[] html = ("<!DOCTYPE html><html><head><title>" + UUID.randomUUID()
                    + "</title></head><body></body></html>").getBytes(StandardCharsets.UTF_8);

            postComment(asAdmin(admin), taskId, "Page attached", pdfFile("clean.pdf"), file("page.html", html))
                    .andExpect(status().isUnsupportedMediaType())
                    .andExpect(jsonPath("$.title").value("Unsupported file type"));

            assertNothingPosted(admin, taskId, keysBefore);
        }

        @Test
        void emptyFileRejectsTheWholeComment() throws Exception {
            User admin = createUser(UserRole.ADMIN);
            UUID taskId = createTask(admin, null);
            Set<String> keysBefore = storedAttachmentKeys();

            postComment(asAdmin(admin), taskId, "Empty file", file("empty.pdf", new byte[0]))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.title").value("Empty file"));

            assertNothingPosted(admin, taskId, keysBefore);
        }

        @Test
        void commentOnUnknownTaskReturnsNotFound() throws Exception {
            User admin = createUser(UserRole.ADMIN);
            UUID unknown = UUID.randomUUID();

            postComment(asAdmin(admin), unknown, "Hello")
                    .andExpect(status().isNotFound())
                    .andExpect(jsonPath("$.title").value("Task not found"));
            postComment(asAdmin(admin), unknown, "Hello", pdfFile("report.pdf"))
                    .andExpect(status().isNotFound());

            assertThat(mediaCountUploadedBy(admin)).isZero();
        }

        @Test
        void commentsOfSoftDeletedTaskAreNotFound() throws Exception {
            User admin = createUser(UserRole.ADMIN);
            UUID taskId = createTask(admin, null);
            UUID commentId = addComment(asAdmin(admin), taskId, "Before deletion");
            deleteTask(admin, taskId).andExpect(status().isNoContent());

            postComment(asAdmin(admin), taskId, "Too late")
                    .andExpect(status().isNotFound())
                    .andExpect(jsonPath("$.title").value("Task not found"));
            postComment(asAdmin(admin), taskId, "Too late", pdfFile("late.pdf"))
                    .andExpect(status().isNotFound());
            listComments(asAdmin(admin), taskId, "")
                    .andExpect(status().isNotFound());
            findComment(asAdmin(admin), taskId, commentId)
                    .andExpect(status().isNotFound());
            editComment(asAdmin(admin), taskId, commentId, "Edited")
                    .andExpect(status().isNotFound());
            deleteComment(asAdmin(admin), taskId, commentId)
                    .andExpect(status().isNotFound());

            assertThat(bodyOf(commentId)).isEqualTo("Before deletion");
            assertThat(mediaCountUploadedBy(admin)).isZero();
        }

        private void assertNothingPosted(User author, UUID taskId, Set<String> keysBefore) throws Exception {
            assertThat(commentCount(taskId)).isZero();
            assertThat(attachmentCount(taskId)).isZero();
            assertThat(mediaCountUploadedBy(author)).isZero();
            assertThat(storedAttachmentKeys()).isSubsetOf(keysBefore);
            assertThat(eventsThroughApi(author, taskId))
                    .noneMatch(event -> event.get("type").asString().startsWith("COMMENT_")
                            || event.get("type").asString().equals("ATTACHMENT_ADDED"));
        }
    }

    @Nested
    class Mentions {

        @Test
        void mentionOfActiveUserIsStoredAndReturned() throws Exception {
            User admin = createUser(UserRole.ADMIN);
            User mentioned = createUser(UserRole.USER);
            UUID taskId = createTask(admin, null);
            String body = "Can you check, " + mention(mentioned) + "?";

            UUID commentId = idOf(postComment(asAdmin(admin), taskId, body)
                    .andExpect(status().isCreated())
                    .andExpect(jsonPath("$.body").value(body))
                    .andExpect(jsonPath("$.mentions", hasSize(1)))
                    .andExpect(jsonPath("$.mentions[0].id").value(mentioned.getId().toString()))
                    .andExpect(jsonPath("$.mentions[0].displayName").value(mentioned.getDisplayName()))
                    .andExpect(jsonPath("$.mentions[0].status").value("ACTIVE")));

            assertThat(mentionRowsOf(commentId)).containsExactly(mentioned.getId());
        }

        @Test
        void mentionsAreReturnedSortedByDisplayName() throws Exception {
            User admin = createUser(UserRole.ADMIN);
            User zoe = createNamedUser("Zoe " + UUID.randomUUID());
            User anna = createNamedUser("Anna " + UUID.randomUUID());
            UUID taskId = createTask(admin, null);

            postComment(asAdmin(admin), taskId, mention(zoe) + " and " + mention(anna))
                    .andExpect(status().isCreated())
                    .andExpect(jsonPath("$.mentions", hasSize(2)))
                    .andExpect(jsonPath("$.mentions[0].id").value(anna.getId().toString()))
                    .andExpect(jsonPath("$.mentions[1].id").value(zoe.getId().toString()));
        }

        @Test
        void multipartCommentMentionsAreStoredToo() throws Exception {
            User admin = createUser(UserRole.ADMIN);
            User mentioned = createUser(UserRole.USER);
            UUID taskId = createTask(admin, null);

            UUID commentId = idOf(postComment(asAdmin(admin), taskId, "For " + mention(mentioned),
                    pdfFile("report.pdf"))
                    .andExpect(status().isCreated())
                    .andExpect(jsonPath("$.mentions[0].id").value(mentioned.getId().toString())));

            assertThat(mentionRowsOf(commentId)).containsExactly(mentioned.getId());
        }

        @Test
        void duplicateMentionTokensAreStoredOnce() throws Exception {
            User admin = createUser(UserRole.ADMIN);
            User mentioned = createUser(UserRole.USER);
            UUID taskId = createTask(admin, null);
            String body = mention(mentioned) + " and again " + mention(mentioned);

            UUID commentId = idOf(postComment(asAdmin(admin), taskId, body)
                    .andExpect(status().isCreated())
                    .andExpect(jsonPath("$.body").value(body))
                    .andExpect(jsonPath("$.mentions", hasSize(1))));

            assertThat(mentionRowsOf(commentId)).containsExactly(mentioned.getId());
        }

        @Test
        void authorCanMentionThemselves() throws Exception {
            User admin = createUser(UserRole.ADMIN);
            User author = createUser(UserRole.USER);
            UUID taskId = createTask(admin, null);

            UUID commentId = idOf(postComment(asUser(author), taskId, "Note to " + mention(author))
                    .andExpect(status().isCreated())
                    .andExpect(jsonPath("$.mentions", hasSize(1)))
                    .andExpect(jsonPath("$.mentions[0].id").value(author.getId().toString())));

            assertThat(mentionRowsOf(commentId)).containsExactly(author.getId());
        }

        @Test
        void textThatOnlyLooksLikeAMentionIsNotOne() throws Exception {
            User admin = createUser(UserRole.ADMIN);
            UUID taskId = createTask(admin, null);

            postComment(asAdmin(admin), taskId, "Write <@not-a-uuid> or @" + admin.getId() + " as text")
                    .andExpect(status().isCreated())
                    .andExpect(jsonPath("$.mentions", hasSize(0)));
        }

        @Test
        void mentionOfUnknownUserIsRejected() throws Exception {
            User admin = createUser(UserRole.ADMIN);
            UUID taskId = createTask(admin, null);
            UUID unknown = UUID.randomUUID();

            postComment(asAdmin(admin), taskId, "Hello <@" + unknown + ">")
                    .andExpect(status().isUnprocessableContent())
                    .andExpect(jsonPath("$.title").value("User cannot be mentioned"))
                    .andExpect(jsonPath("$.detail").value(containsString(unknown.toString())));

            assertThat(commentCount(taskId)).isZero();
        }

        @Test
        void mentionOfUnverifiedUserIsRejected() throws Exception {
            assertMentionRejected(createUnverifiedUser(UserRole.USER), "UNVERIFIED");
        }

        @Test
        void mentionOfDisabledUserIsRejected() throws Exception {
            assertMentionRejected(createDisabledUser(UserRole.USER), "DISABLED");
        }

        @Test
        void mentionOfDeletedUserIsRejected() throws Exception {
            assertMentionRejected(createDeletedUser(UserRole.USER), "DELETED");
        }

        @Test
        void oneInactiveMentionRejectsTheCommentWithItsFiles() throws Exception {
            User admin = createUser(UserRole.ADMIN);
            User active = createUser(UserRole.USER);
            User disabled = createDisabledUser(UserRole.USER);
            UUID taskId = createTask(admin, null);

            postComment(asAdmin(admin), taskId, mention(active) + " " + mention(disabled), pdfFile("report.pdf"))
                    .andExpect(status().isUnprocessableContent())
                    .andExpect(jsonPath("$.title").value("User cannot be mentioned"));

            assertThat(commentCount(taskId)).isZero();
            assertThat(attachmentCount(taskId)).isZero();
            assertThat(mediaCountUploadedBy(admin)).isZero();
        }

        private void assertMentionRejected(User inactive, String status) throws Exception {
            User admin = createUser(UserRole.ADMIN);
            UUID taskId = createTask(admin, null);

            postComment(asAdmin(admin), taskId, "Hello " + mention(inactive))
                    .andExpect(status().isUnprocessableContent())
                    .andExpect(jsonPath("$.title").value("User cannot be mentioned"))
                    .andExpect(jsonPath("$.detail").value(containsString(status)));

            assertThat(commentCount(taskId)).isZero();
        }
    }

    @Nested
    class Reading {

        @Test
        void listIsPaginatedAndOldestFirst() throws Exception {
            User admin = createUser(UserRole.ADMIN);
            User reader = createUser(UserRole.USER);
            UUID taskId = createTask(admin, null);
            UUID first = addComment(asAdmin(admin), taskId, "First");
            UUID second = addComment(asUser(reader), taskId, "Second");
            UUID third = addComment(asAdmin(admin), taskId, "Third");

            listComments(asUser(reader), taskId, "?size=2")
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.content", hasSize(2)))
                    .andExpect(jsonPath("$.content[0].id").value(first.toString()))
                    .andExpect(jsonPath("$.content[1].id").value(second.toString()))
                    .andExpect(jsonPath("$.content[1].author.id").value(reader.getId().toString()))
                    .andExpect(jsonPath("$.totalElements").value(3))
                    .andExpect(jsonPath("$.totalPages").value(2));

            listComments(asUser(reader), taskId, "?size=2&page=1")
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.content", hasSize(1)))
                    .andExpect(jsonPath("$.content[0].id").value(third.toString()));
        }

        @Test
        void listCanBeSortedNewestFirst() throws Exception {
            User admin = createUser(UserRole.ADMIN);
            UUID taskId = createTask(admin, null);
            UUID first = addComment(asAdmin(admin), taskId, "First");
            UUID second = addComment(asAdmin(admin), taskId, "Second");

            listComments(asAdmin(admin), taskId, "?sort=createdAt,desc")
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.content[0].id").value(second.toString()))
                    .andExpect(jsonPath("$.content[1].id").value(first.toString()));
        }

        @Test
        void listContainsOnlyTheCommentsOfTheTask() throws Exception {
            User admin = createUser(UserRole.ADMIN);
            UUID taskId = createTask(admin, null);
            UUID otherTaskId = createTask(admin, null);
            UUID own = addComment(asAdmin(admin), taskId, "Own");
            addComment(asAdmin(admin), otherTaskId, "Other");

            listComments(asAdmin(admin), taskId, "")
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.content", hasSize(1)))
                    .andExpect(jsonPath("$.content[0].id").value(own.toString()))
                    .andExpect(jsonPath("$.totalElements").value(1));
        }

        @Test
        void listOfTaskWithoutCommentsIsEmpty() throws Exception {
            User admin = createUser(UserRole.ADMIN);
            UUID taskId = createTask(admin, null);

            listComments(asAdmin(admin), taskId, "")
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.content", hasSize(0)))
                    .andExpect(jsonPath("$.totalElements").value(0));
        }

        @Test
        void listIncludesMentionsAndAttachments() throws Exception {
            User admin = createUser(UserRole.ADMIN);
            User mentioned = createUser(UserRole.USER);
            UUID taskId = createTask(admin, null);
            UUID commentId = addComment(asAdmin(admin), taskId, "See " + mention(mentioned), pdfFile("plan.pdf"));

            listComments(asAdmin(admin), taskId, "")
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.content[0].mentions[0].id").value(mentioned.getId().toString()))
                    .andExpect(jsonPath("$.content[0].attachments[0].filename").value("plan.pdf"))
                    .andExpect(jsonPath("$.content[0].attachments[0].commentId").value(commentId.toString()));
        }

        @Test
        void listOfUnknownTaskReturnsNotFound() throws Exception {
            User admin = createUser(UserRole.ADMIN);

            listComments(asAdmin(admin), UUID.randomUUID(), "")
                    .andExpect(status().isNotFound())
                    .andExpect(jsonPath("$.title").value("Task not found"));
        }

        @Test
        void findReturnsTheCommentWithMentionsAndAttachments() throws Exception {
            User admin = createUser(UserRole.ADMIN);
            User author = createUser(UserRole.USER);
            User mentioned = createUser(UserRole.USER);
            UUID taskId = createTask(admin, null);
            String body = "Report for " + mention(mentioned);
            UUID commentId = addComment(asUser(author), taskId, body, pdfFile("report.pdf"));

            String found = findComment(asUser(mentioned), taskId, commentId)
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.id").value(commentId.toString()))
                    .andExpect(jsonPath("$.body").value(body))
                    .andExpect(jsonPath("$.author.id").value(author.getId().toString()))
                    .andExpect(jsonPath("$.mentions[0].id").value(mentioned.getId().toString()))
                    .andExpect(jsonPath("$.attachments", hasSize(1)))
                    .andExpect(jsonPath("$.attachments[0].commentId").value(commentId.toString()))
                    .andExpect(jsonPath("$.createdAt").value(notNullValue()))
                    .andExpect(jsonPath("$.editedAt").value(nullValue()))
                    .andReturn()
                    .getResponse()
                    .getContentAsString();

            assertThat(download(json(found).get("attachments").get(0).get("downloadUrl").asString()).body())
                    .startsWith("%PDF".getBytes(StandardCharsets.US_ASCII));
        }

        @Test
        void authorWhoWasDeletedSinceStillShowsWithDeletedStatus() throws Exception {
            User admin = createUser(UserRole.ADMIN);
            User author = createUser(UserRole.USER);
            UUID taskId = createTask(admin, null);
            UUID commentId = addComment(asUser(author), taskId, "Bye");
            deleteThroughApi(author);

            findComment(asAdmin(admin), taskId, commentId)
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.author.id").value(author.getId().toString()))
                    .andExpect(jsonPath("$.author.displayName").value(author.getDisplayName()))
                    .andExpect(jsonPath("$.author.status").value("DELETED"));
        }

        @Test
        void findUnknownCommentReturnsNotFound() throws Exception {
            User admin = createUser(UserRole.ADMIN);
            UUID taskId = createTask(admin, null);

            findComment(asAdmin(admin), taskId, UUID.randomUUID())
                    .andExpect(status().isNotFound())
                    .andExpect(jsonPath("$.title").value("Comment not found"));
        }

        @Test
        void findCommentThroughAnotherTaskReturnsNotFound() throws Exception {
            User admin = createUser(UserRole.ADMIN);
            UUID taskId = createTask(admin, null);
            UUID otherTaskId = createTask(admin, null);
            UUID commentId = addComment(asAdmin(admin), taskId, "Mine");

            findComment(asAdmin(admin), otherTaskId, commentId)
                    .andExpect(status().isNotFound())
                    .andExpect(jsonPath("$.title").value("Comment not found"));
        }
    }

    @Nested
    class Editing {

        @Test
        void authorEditsTheBodyAndEditedAtIsSet() throws Exception {
            User admin = createUser(UserRole.ADMIN);
            User author = createUser(UserRole.USER);
            UUID taskId = createTask(admin, null);
            UUID commentId = addComment(asUser(author), taskId, "Frist");
            String createdAt = json(findComment(asUser(author), taskId, commentId)
                    .andReturn().getResponse().getContentAsString()).get("createdAt").asString();
            Instant before = Instant.now();

            String edited = editComment(asUser(author), taskId, commentId, "First")
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.id").value(commentId.toString()))
                    .andExpect(jsonPath("$.body").value("First"))
                    .andExpect(jsonPath("$.author.id").value(author.getId().toString()))
                    .andReturn()
                    .getResponse()
                    .getContentAsString();

            assertThat(Instant.parse(json(edited).get("editedAt").asString())).isBetween(before, Instant.now());
            assertThat(json(edited).get("createdAt").asString()).isEqualTo(createdAt);
            assertThat(bodyOf(commentId)).isEqualTo("First");
            findComment(asUser(author), taskId, commentId)
                    .andExpect(jsonPath("$.body").value("First"))
                    .andExpect(jsonPath("$.editedAt").value(notNullValue()));
        }

        @Test
        void editWithUnchangedBodyIsNotMarkedAsEdited() throws Exception {
            User admin = createUser(UserRole.ADMIN);
            UUID taskId = createTask(admin, null);
            UUID commentId = addComment(asAdmin(admin), taskId, "Same");

            editComment(asAdmin(admin), taskId, commentId, "Same")
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.editedAt").value(nullValue()));

            assertThat(eventsThroughApi(admin, taskId))
                    .noneMatch(event -> event.get("type").asString().equals("COMMENT_EDITED"));
        }

        @Test
        void anotherUserCannotEdit() throws Exception {
            User admin = createUser(UserRole.ADMIN);
            User author = createUser(UserRole.USER);
            User other = createUser(UserRole.USER);
            UUID taskId = createTask(admin, author);
            UUID commentId = addComment(asUser(author), taskId, "Original");

            editComment(asUser(other), taskId, commentId, "Hijacked")
                    .andExpect(status().isForbidden());

            assertThat(bodyOf(commentId)).isEqualTo("Original");
        }

        @Test
        void adminCannotEditSomeoneElsesComment() throws Exception {
            User admin = createUser(UserRole.ADMIN);
            User author = createUser(UserRole.USER);
            UUID taskId = createTask(admin, null);
            UUID commentId = addComment(asUser(author), taskId, "Original");

            editComment(asAdmin(admin), taskId, commentId, "Rewritten by admin")
                    .andExpect(status().isForbidden());

            assertThat(bodyOf(commentId)).isEqualTo("Original");
        }

        @Test
        void adminCanEditTheirOwnComment() throws Exception {
            User admin = createUser(UserRole.ADMIN);
            UUID taskId = createTask(admin, null);
            UUID commentId = addComment(asAdmin(admin), taskId, "Original");

            editComment(asAdmin(admin), taskId, commentId, "Updated")
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.body").value("Updated"));
        }

        @Test
        void editReplacesTheMentions() throws Exception {
            User admin = createUser(UserRole.ADMIN);
            User before = createUser(UserRole.USER);
            User after = createUser(UserRole.USER);
            UUID taskId = createTask(admin, null);
            UUID commentId = addComment(asAdmin(admin), taskId, "For " + mention(before));

            editComment(asAdmin(admin), taskId, commentId, "For " + mention(after))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.mentions", hasSize(1)))
                    .andExpect(jsonPath("$.mentions[0].id").value(after.getId().toString()));

            assertThat(mentionRowsOf(commentId)).containsExactly(after.getId());
        }

        @Test
        void editRemovingAllMentionsClearsThem() throws Exception {
            User admin = createUser(UserRole.ADMIN);
            User mentioned = createUser(UserRole.USER);
            UUID taskId = createTask(admin, null);
            UUID commentId = addComment(asAdmin(admin), taskId, "For " + mention(mentioned));

            editComment(asAdmin(admin), taskId, commentId, "For nobody")
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.mentions", hasSize(0)));

            assertThat(mentionRowsOf(commentId)).isEmpty();
        }

        @Test
        void keptMentionOfUserDisabledSinceDoesNotFail() throws Exception {
            User admin = createUser(UserRole.ADMIN);
            User mentioned = createUser(UserRole.USER);
            UUID taskId = createTask(admin, null);
            UUID commentId = addComment(asAdmin(admin), taskId, "For " + mention(mentioned));
            disableThroughApi(mentioned);

            editComment(asAdmin(admin), taskId, commentId, "Still for " + mention(mentioned))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.body").value("Still for " + mention(mentioned)))
                    .andExpect(jsonPath("$.mentions", hasSize(1)))
                    .andExpect(jsonPath("$.mentions[0].id").value(mentioned.getId().toString()))
                    .andExpect(jsonPath("$.mentions[0].status").value("DISABLED"));

            assertThat(mentionRowsOf(commentId)).containsExactly(mentioned.getId());
        }

        @Test
        void keptMentionOfUserDeletedSinceDoesNotFail() throws Exception {
            User admin = createUser(UserRole.ADMIN);
            User mentioned = createUser(UserRole.USER);
            UUID taskId = createTask(admin, null);
            UUID commentId = addComment(asAdmin(admin), taskId, "For " + mention(mentioned));
            deleteThroughApi(mentioned);

            editComment(asAdmin(admin), taskId, commentId, "Still for " + mention(mentioned))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.mentions[0].status").value("DELETED"));
        }

        @Test
        void newMentionOfInactiveUserIsRejectedAndTheCommentIsUnchanged() throws Exception {
            User admin = createUser(UserRole.ADMIN);
            User kept = createUser(UserRole.USER);
            User unverified = createUnverifiedUser(UserRole.USER);
            UUID taskId = createTask(admin, null);
            UUID commentId = addComment(asAdmin(admin), taskId, "For " + mention(kept));

            editComment(asAdmin(admin), taskId, commentId, mention(kept) + " " + mention(unverified))
                    .andExpect(status().isUnprocessableContent())
                    .andExpect(jsonPath("$.title").value("User cannot be mentioned"))
                    .andExpect(jsonPath("$.detail").value(containsString("UNVERIFIED")));

            assertThat(bodyOf(commentId)).isEqualTo("For " + mention(kept));
            assertThat(mentionRowsOf(commentId)).containsExactly(kept.getId());
            findComment(asAdmin(admin), taskId, commentId)
                    .andExpect(jsonPath("$.editedAt").value(nullValue()));
        }

        @Test
        void newMentionOfUnknownUserIsRejected() throws Exception {
            User admin = createUser(UserRole.ADMIN);
            UUID taskId = createTask(admin, null);
            UUID commentId = addComment(asAdmin(admin), taskId, "Original");

            editComment(asAdmin(admin), taskId, commentId, "Hello <@" + UUID.randomUUID() + ">")
                    .andExpect(status().isUnprocessableContent())
                    .andExpect(jsonPath("$.title").value("User cannot be mentioned"));

            assertThat(bodyOf(commentId)).isEqualTo("Original");
        }

        @Test
        void blankOrTooLongBodyIsRejected() throws Exception {
            User admin = createUser(UserRole.ADMIN);
            UUID taskId = createTask(admin, null);
            UUID commentId = addComment(asAdmin(admin), taskId, "Original");

            editComment(asAdmin(admin), taskId, commentId, " ")
                    .andExpect(status().isBadRequest());
            editComment(asAdmin(admin), taskId, commentId, "a".repeat(MAX_BODY_LENGTH + 1))
                    .andExpect(status().isBadRequest());

            assertThat(bodyOf(commentId)).isEqualTo("Original");
        }

        @Test
        void editThroughAnotherTaskReturnsNotFound() throws Exception {
            User admin = createUser(UserRole.ADMIN);
            UUID taskId = createTask(admin, null);
            UUID otherTaskId = createTask(admin, null);
            UUID commentId = addComment(asAdmin(admin), taskId, "Original");

            editComment(asAdmin(admin), otherTaskId, commentId, "Moved")
                    .andExpect(status().isNotFound())
                    .andExpect(jsonPath("$.title").value("Comment not found"));

            assertThat(bodyOf(commentId)).isEqualTo("Original");
        }

        @Test
        void editKeepsTheFilesOfTheComment() throws Exception {
            User admin = createUser(UserRole.ADMIN);
            UUID taskId = createTask(admin, null);
            UUID commentId = addComment(asAdmin(admin), taskId, "Original", pdfFile("report.pdf"));

            editComment(asAdmin(admin), taskId, commentId, "Updated")
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.attachments", hasSize(1)))
                    .andExpect(jsonPath("$.attachments[0].filename").value("report.pdf"));
        }
    }

    @Nested
    class Deleting {

        @Test
        void authorDeletesTheirComment() throws Exception {
            User admin = createUser(UserRole.ADMIN);
            User author = createUser(UserRole.USER);
            UUID taskId = createTask(admin, null);
            UUID commentId = addComment(asUser(author), taskId, "Oops");

            deleteComment(asUser(author), taskId, commentId)
                    .andExpect(status().isNoContent());

            assertThat(commentExists(commentId)).isFalse();
            findComment(asUser(author), taskId, commentId)
                    .andExpect(status().isNotFound());
        }

        @Test
        void adminDeletesSomeoneElsesComment() throws Exception {
            User admin = createUser(UserRole.ADMIN);
            User author = createUser(UserRole.USER);
            UUID taskId = createTask(admin, null);
            UUID commentId = addComment(asUser(author), taskId, "Spam");

            deleteComment(asAdmin(admin), taskId, commentId)
                    .andExpect(status().isNoContent());

            assertThat(commentExists(commentId)).isFalse();
        }

        @Test
        void anotherUserCannotDelete() throws Exception {
            User admin = createUser(UserRole.ADMIN);
            User author = createUser(UserRole.USER);
            User assignee = createUser(UserRole.USER);
            UUID taskId = createTask(admin, assignee);
            UUID commentId = addComment(asUser(author), taskId, "Keep me", pdfFile("report.pdf"));

            deleteComment(asUser(assignee), taskId, commentId)
                    .andExpect(status().isForbidden());

            assertThat(commentExists(commentId)).isTrue();
            assertThat(attachmentIdsOfComment(commentId)).hasSize(1);
        }

        @Test
        void deletingRemovesTheFilesFromTheTaskAndFromStorage() throws Exception {
            User admin = createUser(UserRole.ADMIN);
            User author = createUser(UserRole.USER);
            UUID taskId = createTask(admin, null);
            UUID direct = uploadPdf(asAdmin(admin), taskId, "direct.pdf");
            UUID commentId = addComment(asUser(author), taskId, "Files", pdfFile("a.pdf"), pdfFile("b.pdf"));
            List<String> keys = new ArrayList<>();
            for (UUID attachmentId : attachmentIdsOfComment(commentId)) {
                keys.add(storageKeyOf(attachmentId));
            }

            deleteComment(asUser(author), taskId, commentId)
                    .andExpect(status().isNoContent());

            list(asAdmin(admin), taskId)
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$", hasSize(1)))
                    .andExpect(jsonPath("$[0].id").value(direct.toString()));
            assertThat(attachmentIdsOfComment(commentId)).isEmpty();
            assertThat(mediaCountUploadedBy(author)).isZero();
            assertThat(keys).hasSize(2);
            for (String key : keys) {
                assertThat(mediaExistsWithKey(key)).isFalse();
                assertObjectMissing(key);
            }
            assertThat(objectBytes(storageKeyOf(direct))).isNotEmpty();
        }

        @Test
        void deletingRemovesTheMentionRows() throws Exception {
            User admin = createUser(UserRole.ADMIN);
            User mentioned = createUser(UserRole.USER);
            UUID taskId = createTask(admin, null);
            UUID commentId = addComment(asAdmin(admin), taskId, "For " + mention(mentioned));

            deleteComment(asAdmin(admin), taskId, commentId)
                    .andExpect(status().isNoContent());

            assertThat(mentionRowsOf(commentId)).isEmpty();
        }

        @Test
        void deletingKeepsTheOtherComments() throws Exception {
            User admin = createUser(UserRole.ADMIN);
            UUID taskId = createTask(admin, null);
            UUID removed = addComment(asAdmin(admin), taskId, "Removed");
            UUID kept = addComment(asAdmin(admin), taskId, "Kept");

            deleteComment(asAdmin(admin), taskId, removed)
                    .andExpect(status().isNoContent());

            listComments(asAdmin(admin), taskId, "")
                    .andExpect(jsonPath("$.content", hasSize(1)))
                    .andExpect(jsonPath("$.content[0].id").value(kept.toString()));
        }

        @Test
        void deletingACommentWhoseFileWasAlreadyRemovedWorks() throws Exception {
            User admin = createUser(UserRole.ADMIN);
            UUID taskId = createTask(admin, null);
            UUID commentId = addComment(asAdmin(admin), taskId, "File", pdfFile("a.pdf"));
            UUID attachmentId = attachmentIdsOfComment(commentId).iterator().next();
            remove(asAdmin(admin), taskId, attachmentId).andExpect(status().isNoContent());

            findComment(asAdmin(admin), taskId, commentId)
                    .andExpect(jsonPath("$.attachments", hasSize(0)));
            deleteComment(asAdmin(admin), taskId, commentId)
                    .andExpect(status().isNoContent());

            assertThat(commentExists(commentId)).isFalse();
        }

        @Test
        void deleteUnknownCommentAsAdminReturnsNotFound() throws Exception {
            User admin = createUser(UserRole.ADMIN);
            UUID taskId = createTask(admin, null);

            deleteComment(asAdmin(admin), taskId, UUID.randomUUID())
                    .andExpect(status().isNotFound())
                    .andExpect(jsonPath("$.title").value("Comment not found"));
        }

        @Test
        void deleteThroughAnotherTaskReturnsNotFoundAndKeepsTheComment() throws Exception {
            User admin = createUser(UserRole.ADMIN);
            UUID taskId = createTask(admin, null);
            UUID otherTaskId = createTask(admin, null);
            UUID commentId = addComment(asAdmin(admin), taskId, "Mine", pdfFile("a.pdf"));

            deleteComment(asAdmin(admin), otherTaskId, commentId)
                    .andExpect(status().isNotFound());

            assertThat(commentExists(commentId)).isTrue();
            assertThat(attachmentIdsOfComment(commentId)).hasSize(1);
        }
    }

    @Nested
    class History {

        @Test
        void postingRecordsCommentAddedAndOneAttachmentAddedPerFile() throws Exception {
            User admin = createUser(UserRole.ADMIN);
            User author = createUser(UserRole.USER);
            UUID taskId = createTask(admin, null);

            UUID commentId = addComment(asUser(author), taskId, "Files", pdfFile("a.pdf"), pdfFile("b.pdf"));

            List<JsonNode> added = eventsOfType(admin, taskId, "COMMENT_ADDED");
            assertThat(added).hasSize(1);
            assertThat(added.getFirst().get("actor").get("id").asString()).isEqualTo(author.getId().toString());
            assertThat(added.getFirst().get("payload").get("commentId").asString()).isEqualTo(commentId.toString());

            List<JsonNode> files = eventsOfType(admin, taskId, "ATTACHMENT_ADDED");
            assertThat(files).hasSize(2);
            assertThat(files)
                    .extracting(event -> UUID.fromString(event.get("payload").get("attachmentId").asString()))
                    .containsExactlyInAnyOrderElementsOf(attachmentIdsOfComment(commentId));
            assertThat(files)
                    .extracting(event -> event.get("payload").get("filename").asString())
                    .containsExactlyInAnyOrder("a.pdf", "b.pdf");
        }

        @Test
        void editingRecordsCommentEdited() throws Exception {
            User admin = createUser(UserRole.ADMIN);
            User author = createUser(UserRole.USER);
            UUID taskId = createTask(admin, null);
            UUID commentId = addComment(asUser(author), taskId, "Draft");

            editComment(asUser(author), taskId, commentId, "Final").andExpect(status().isOk());

            JsonNode latest = eventsThroughApi(admin, taskId).get(0);
            assertThat(latest.get("type").asString()).isEqualTo("COMMENT_EDITED");
            assertThat(latest.get("actor").get("id").asString()).isEqualTo(author.getId().toString());
            assertThat(latest.get("payload").get("commentId").asString()).isEqualTo(commentId.toString());
        }

        @Test
        void deletingRecordsCommentDeletedAndOneAttachmentRemovedPerFile() throws Exception {
            User admin = createUser(UserRole.ADMIN);
            User author = createUser(UserRole.USER);
            UUID taskId = createTask(admin, null);
            UUID commentId = addComment(asUser(author), taskId, "Files", pdfFile("a.pdf"), pdfFile("b.pdf"));
            Set<UUID> attachmentIds = attachmentIdsOfComment(commentId);

            deleteComment(asAdmin(admin), taskId, commentId).andExpect(status().isNoContent());

            List<JsonNode> deleted = eventsOfType(admin, taskId, "COMMENT_DELETED");
            assertThat(deleted).hasSize(1);
            assertThat(deleted.getFirst().get("actor").get("id").asString()).isEqualTo(admin.getId().toString());
            assertThat(deleted.getFirst().get("payload").get("commentId").asString())
                    .isEqualTo(commentId.toString());

            List<JsonNode> removed = eventsOfType(admin, taskId, "ATTACHMENT_REMOVED");
            assertThat(removed)
                    .extracting(event -> UUID.fromString(event.get("payload").get("attachmentId").asString()))
                    .containsExactlyInAnyOrderElementsOf(attachmentIds);
            assertThat(removed)
                    .allSatisfy(event -> assertThat(event.get("actor").get("id").asString())
                            .isEqualTo(admin.getId().toString()));
        }

        @Test
        void rejectedEditRecordsNothing() throws Exception {
            User admin = createUser(UserRole.ADMIN);
            User author = createUser(UserRole.USER);
            UUID taskId = createTask(admin, null);
            UUID commentId = addComment(asUser(author), taskId, "Draft");

            editComment(asAdmin(admin), taskId, commentId, "Not yours").andExpect(status().isForbidden());
            editComment(asUser(author), taskId, commentId, "<@" + UUID.randomUUID() + ">")
                    .andExpect(status().isUnprocessableContent());

            assertThat(eventsOfType(admin, taskId, "COMMENT_EDITED")).isEmpty();
        }

        private List<JsonNode> eventsOfType(User reader, UUID taskId, String type) throws Exception {
            return StreamSupport.stream(eventsThroughApi(reader, taskId).spliterator(), false)
                    .filter(event -> event.get("type").asString().equals(type))
                    .toList();
        }
    }

    @Nested
    class InactiveCallers {

        @Test
        void unverifiedUserIsForbiddenOnEveryCommentEndpoint() throws Exception {
            User admin = createUser(UserRole.ADMIN);
            User author = createUser(UserRole.USER);
            UUID taskId = createTask(admin, null);
            UUID commentId = addComment(asUser(author), taskId, "Written while active");
            unverify(author);

            assertForbiddenOnEveryCommentEndpoint(asUser(author), taskId, commentId);
        }

        @Test
        void disabledUserIsForbiddenOnEveryCommentEndpoint() throws Exception {
            User admin = createUser(UserRole.ADMIN);
            User author = createUser(UserRole.USER);
            UUID taskId = createTask(admin, null);
            UUID commentId = addComment(asUser(author), taskId, "Written while active");
            disableThroughApi(author);

            assertForbiddenOnEveryCommentEndpoint(asUser(author), taskId, commentId);
        }

        @Test
        void disabledAdminIsForbiddenOnEveryCommentEndpoint() throws Exception {
            User admin = createUser(UserRole.ADMIN);
            User disabledAdmin = createDisabledUser(UserRole.ADMIN);
            UUID taskId = createTask(admin, null);
            UUID commentId = addComment(asAdmin(admin), taskId, "Written by an active admin");

            assertForbiddenOnEveryCommentEndpoint(asAdmin(disabledAdmin), taskId, commentId);
        }

        @Test
        void deletedUserIsForbiddenOnEveryCommentEndpoint() throws Exception {
            User admin = createUser(UserRole.ADMIN);
            User author = createUser(UserRole.USER);
            UUID taskId = createTask(admin, null);
            UUID commentId = addComment(asUser(author), taskId, "Written while active");
            deleteThroughApi(author);

            assertForbiddenOnEveryCommentEndpoint(asUser(author), taskId, commentId);
        }

        private void assertForbiddenOnEveryCommentEndpoint(RequestPostProcessor caller, UUID taskId, UUID commentId)
                throws Exception {
            postComment(caller, taskId, "Denied").andExpect(status().isForbidden());
            postComment(caller, taskId, "Denied", pdfFile("denied.pdf")).andExpect(status().isForbidden());
            listComments(caller, taskId, "").andExpect(status().isForbidden());
            findComment(caller, taskId, commentId).andExpect(status().isForbidden());
            editComment(caller, taskId, commentId, "Denied").andExpect(status().isForbidden());
            deleteComment(caller, taskId, commentId).andExpect(status().isForbidden());

            assertThat(commentCount(taskId)).isOne();
            assertThat(bodyOf(commentId)).isNotEqualTo("Denied");
            assertThat(attachmentCount(taskId)).isZero();
        }
    }

    private User createNamedUser(String displayName) {
        return updateUser(createUser(UserRole.USER), user -> user.setDisplayName(displayName));
    }
}
