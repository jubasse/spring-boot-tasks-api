package io.julienmetral.tasks.task.controllers;

import io.julienmetral.tasks.identity.entities.UserStatus;
import io.julienmetral.tasks.identity.repositories.UserRepository;
import io.julienmetral.tasks.support.WebLayerTest;
import io.julienmetral.tasks.task.entities.TaskComment;
import io.julienmetral.tasks.task.exceptions.InvalidMentionException;
import io.julienmetral.tasks.task.exceptions.TaskCommentNotFoundException;
import io.julienmetral.tasks.task.exceptions.TaskNotFoundException;
import io.julienmetral.tasks.task.exceptions.TooManyCommentAttachmentsException;
import io.julienmetral.tasks.task.security.TaskCommentAuthorization;
import io.julienmetral.tasks.task.services.TaskCommentService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockMultipartHttpServletRequestBuilder;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import org.springframework.web.multipart.MultipartFile;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.UUID;

import static io.julienmetral.tasks.support.Problems.invalidBodyValue;
import static io.julienmetral.tasks.support.Problems.invalidParameter;
import static io.julienmetral.tasks.support.Problems.typedProblem;
import static io.julienmetral.tasks.support.Problems.untypedProblem;
import static io.julienmetral.tasks.support.WebCallers.admin;
import static io.julienmetral.tasks.support.WebCallers.everyAccountIsActive;
import static io.julienmetral.tasks.support.WebCallers.user;
import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebLayerTest
class TaskCommentControllerWebMvcTests {

    private static final int MAX_BODY_LENGTH = 10_000;

    private static final byte[] PDF_BYTES = "%PDF-1.4\n".getBytes(StandardCharsets.US_ASCII);

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private TaskCommentService commentService;

    @Autowired
    private TaskCommentAuthorization taskCommentAuthorization;

    @Autowired
    private UserRepository userRepository;

    private final UUID taskId = UUID.randomUUID();

    private final UUID commentId = UUID.randomUUID();

    @BeforeEach
    void callersAreActive() {
        everyAccountIsActive(userRepository);
    }

    @Test
    void everyCommentEndpointRequiresAuthentication() throws Exception {
        mockMvc.perform(post(comments()).contentType(MediaType.APPLICATION_JSON).content(bodyJson("Hello")))
                .andExpect(status().isUnauthorized());
        mockMvc.perform(multipartComment("Hello", pdf("report.pdf")))
                .andExpect(status().isUnauthorized());
        mockMvc.perform(get(comments()))
                .andExpect(status().isUnauthorized());
        mockMvc.perform(get(comment()))
                .andExpect(status().isUnauthorized());
        mockMvc.perform(patch(comment()).contentType(MediaType.APPLICATION_JSON).content(bodyJson("Edited")))
                .andExpect(status().isUnauthorized());
        mockMvc.perform(delete(comment()))
                .andExpect(status().isUnauthorized());

        verifyNoInteractions(commentService);
    }

    @Nested
    class Posting {

        @Test
        void blankBodyIsRejected() throws Exception {
            postJson(bodyJson("   ")).andExpect(status().isBadRequest());
            postMultipart(" \n ", pdf("report.pdf")).andExpect(status().isBadRequest());

            verifyNoInteractions(commentService);
        }

        @Test
        void blankJsonBodyPointsToTheBodyField() throws Exception {
            postJsonInEnglish(bodyJson("   "))
                    .andExpect(invalidBodyValue("#/body", "must not be blank"));

            verifyNoInteractions(commentService);
        }

        @Test
        void blankMultipartBodyNamesTheBodyParameter() throws Exception {
            mockMvc.perform(multipartComment(" ", pdf("report.pdf"))
                            .with(user(UUID.randomUUID()))
                            .header(HttpHeaders.ACCEPT_LANGUAGE, "en"))
                    .andExpect(invalidParameter("body", "must not be blank"));

            verifyNoInteractions(commentService);
        }

        @Test
        void missingBodyIsRejected() throws Exception {
            postJson("{}").andExpect(status().isBadRequest());
            postMultipart(null, pdf("report.pdf")).andExpect(status().isBadRequest());

            verifyNoInteractions(commentService);
        }

        @Test
        void bodyOverTheLengthLimitIsRejected() throws Exception {
            String tooLong = "a".repeat(MAX_BODY_LENGTH + 1);

            postJson(bodyJson(tooLong)).andExpect(status().isBadRequest());
            postMultipart(tooLong, pdf("report.pdf")).andExpect(status().isBadRequest());

            verifyNoInteractions(commentService);
        }

        @Test
        void malformedJsonIsRejectedWithAnUntypedProblem() throws Exception {
            postJson("{\"body\": ")
                    .andExpect(untypedProblem(400, "Bad Request"))
                    .andExpect(jsonPath("$.detail").value("Failed to read request"));

            verifyNoInteractions(commentService);
        }

        @Test
        void jsonCommentReachesTheServiceWithoutFiles() throws Exception {
            when(commentService.add(taskId, "Hello", List.of())).thenReturn(comment("Hello"));

            postJson(bodyJson("Hello"))
                    .andExpect(status().isCreated())
                    .andExpect(jsonPath("$.body").value("Hello"));
        }

        @Test
        void multipartCommentPassesItsFilesToTheService() throws Exception {
            when(commentService.add(eq(taskId), eq("With files"), anyList())).thenReturn(comment("With files"));

            postMultipart("With files", pdf("a.pdf"), pdf("b.pdf"))
                    .andExpect(status().isCreated());

            @SuppressWarnings("unchecked")
            ArgumentCaptor<List<MultipartFile>> files = ArgumentCaptor.forClass(List.class);
            verify(commentService).add(eq(taskId), eq("With files"), files.capture());
            assertThat(files.getValue())
                    .extracting(MultipartFile::getOriginalFilename)
                    .containsExactly("a.pdf", "b.pdf");
        }

        @Test
        void tooManyFilesIsAValidationErrorOnTheFilesParameter() throws Exception {
            when(commentService.add(eq(taskId), anyString(), anyList()))
                    .thenThrow(new TooManyCommentAttachmentsException(5));

            postMultipart("Six files",
                    pdf("1.pdf"), pdf("2.pdf"), pdf("3.pdf"), pdf("4.pdf"), pdf("5.pdf"), pdf("6.pdf"))
                    .andExpect(invalidParameter("files", "A comment can have at most 5 files"));
        }

        @Test
        void commentOnUnknownTaskReturnsNotFoundProblem() throws Exception {
            when(commentService.add(eq(taskId), anyString(), anyList())).thenThrow(new TaskNotFoundException(taskId));

            postJson(bodyJson("Hello"))
                    .andExpect(untypedProblem(404, "Not Found"))
                    .andExpect(jsonPath("$.detail").value("Task not found with id: " + taskId));
        }

        @Test
        void mentionOfInactiveUserReturnsInvalidMentionProblem() throws Exception {
            UUID mentioned = UUID.randomUUID();
            when(commentService.add(eq(taskId), anyString(), anyList()))
                    .thenThrow(InvalidMentionException.inactiveUser(mentioned, UserStatus.DISABLED));

            postJson(bodyJson("Hi <@" + mentioned + ">"))
                    .andExpect(typedProblem(422, "invalid-mention", "Invalid mention"))
                    .andExpect(jsonPath("$.detail").value(containsString(mentioned.toString())));
        }

        private ResultActions postJson(String body) throws Exception {
            return mockMvc.perform(post(comments())
                    .with(user(UUID.randomUUID()))
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(body));
        }

        private ResultActions postJsonInEnglish(String body) throws Exception {
            return mockMvc.perform(post(comments())
                    .with(user(UUID.randomUUID()))
                    .header(HttpHeaders.ACCEPT_LANGUAGE, "en")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(body));
        }

        private ResultActions postMultipart(String body, MockMultipartFile... files) throws Exception {
            return mockMvc.perform(multipartComment(body, files).with(user(UUID.randomUUID())));
        }
    }

    @Nested
    class Reading {

        @Test
        void listDefaultsToOldestFirst() throws Exception {
            when(commentService.findAll(eq(taskId), any())).thenReturn(Page.empty());

            mockMvc.perform(get(comments()).with(user(UUID.randomUUID())))
                    .andExpect(status().isOk());

            ArgumentCaptor<Pageable> pageable = ArgumentCaptor.forClass(Pageable.class);
            verify(commentService).findAll(eq(taskId), pageable.capture());
            assertThat(pageable.getValue().getSort()).isEqualTo(Sort.by(Sort.Direction.ASC, "createdAt"));
        }

        @Test
        void unknownCommentReturnsNotFoundProblem() throws Exception {
            when(commentService.find(taskId, commentId)).thenThrow(new TaskCommentNotFoundException(commentId));

            mockMvc.perform(get(comment()).with(user(UUID.randomUUID())))
                    .andExpect(untypedProblem(404, "Not Found"))
                    .andExpect(jsonPath("$.detail").value("Comment not found with id: " + commentId));
        }

        @Test
        void malformedCommentIdNamesThePathParameter() throws Exception {
            mockMvc.perform(get(comments() + "/not-a-uuid").with(user(UUID.randomUUID())))
                    .andExpect(invalidParameter("commentId", "must be a UUID"));

            verifyNoInteractions(commentService);
        }
    }

    @Nested
    class Editing {

        @Test
        void blankOrTooLongBodyIsRejected() throws Exception {
            when(taskCommentAuthorization.currentUserWrote(eq(commentId), any())).thenReturn(true);

            edit(user(UUID.randomUUID()), " ").andExpect(status().isBadRequest());
            edit(user(UUID.randomUUID()), "a".repeat(MAX_BODY_LENGTH + 1)).andExpect(status().isBadRequest());

            verifyNoInteractions(commentService);
        }

        @Test
        void adminWhoDidNotWriteTheCommentCannotEditIt() throws Exception {
            when(taskCommentAuthorization.currentUserWrote(eq(commentId), any())).thenReturn(false);

            edit(admin(UUID.randomUUID()), "Not yours").andExpect(status().isForbidden());

            verifyNoInteractions(commentService);
        }

        @Test
        void authorCheckIsAskedAboutTheCommentOfThePath() throws Exception {
            when(taskCommentAuthorization.currentUserWrote(eq(commentId), any())).thenReturn(true);
            when(commentService.edit(taskId, commentId, "Edited")).thenReturn(comment("Edited"));

            edit(user(UUID.randomUUID()), "Edited")
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.body").value("Edited"));

            verify(taskCommentAuthorization).currentUserWrote(eq(commentId), any());
        }

        private ResultActions edit(RequestPostProcessor caller, String body) throws Exception {
            return mockMvc.perform(patch(comment())
                    .with(caller)
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(bodyJson(body)));
        }
    }

    @Nested
    class Deleting {

        @Test
        void userWhoDidNotWriteTheCommentCannotDeleteIt() throws Exception {
            when(taskCommentAuthorization.currentUserWrote(eq(commentId), any())).thenReturn(false);

            mockMvc.perform(delete(comment()).with(user(UUID.randomUUID())))
                    .andExpect(status().isForbidden());

            verifyNoInteractions(commentService);
        }

        @Test
        void adminDeletesWithoutTheAuthorCheck() throws Exception {
            mockMvc.perform(delete(comment()).with(admin(UUID.randomUUID())))
                    .andExpect(status().isNoContent());

            verify(commentService).delete(taskId, commentId);
            verifyNoInteractions(taskCommentAuthorization);
        }
    }

    private String comments() {
        return "/api/v1/tasks/" + taskId + "/comments";
    }

    private String comment() {
        return comments() + "/" + commentId;
    }

    private MockMultipartHttpServletRequestBuilder multipartComment(String body, MockMultipartFile... files) {
        MockMultipartHttpServletRequestBuilder request = multipart(comments());

        for (MockMultipartFile file : files) {
            request.file(file);
        }

        if (body != null) {
            request.param("body", body);
        }

        return request;
    }

    private static MockMultipartFile pdf(String filename) {
        return new MockMultipartFile("files", filename, "application/pdf", PDF_BYTES);
    }

    private static String bodyJson(String body) {
        return "{\"body\": \"%s\"}".formatted(body.replace("\n", "\\n"));
    }

    private static TaskComment comment(String body) {
        TaskComment comment = new TaskComment();
        comment.setBody(body);
        return comment;
    }
}
