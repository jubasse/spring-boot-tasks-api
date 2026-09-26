package io.julienmetral.tasks.task.controllers;

import io.julienmetral.tasks.identity.repositories.UserRepository;
import io.julienmetral.tasks.media.exceptions.AntivirusUnavailableException;
import io.julienmetral.tasks.media.exceptions.EmptyMediaException;
import io.julienmetral.tasks.media.exceptions.InfectedMediaException;
import io.julienmetral.tasks.media.exceptions.MediaTooLargeException;
import io.julienmetral.tasks.media.exceptions.StorageUnavailableException;
import io.julienmetral.tasks.media.exceptions.UnsupportedMediaTypeException;
import io.julienmetral.tasks.media.model.Media;
import io.julienmetral.tasks.media.model.MediaUsage;
import io.julienmetral.tasks.support.WebLayerTest;
import io.julienmetral.tasks.task.entities.TaskAttachment;
import io.julienmetral.tasks.task.exceptions.TaskAttachmentNotFoundException;
import io.julienmetral.tasks.task.exceptions.TaskNotFoundException;
import io.julienmetral.tasks.task.security.TaskAttachmentAuthorization;
import io.julienmetral.tasks.task.security.TaskAuthorization;
import io.julienmetral.tasks.task.services.TaskAttachmentService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockMultipartHttpServletRequestBuilder;
import org.springframework.util.unit.DataSize;
import org.springframework.web.multipart.MaxUploadSizeExceededException;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.UUID;

import static io.julienmetral.tasks.support.WebCallers.admin;
import static io.julienmetral.tasks.support.WebCallers.everyAccountIsActive;
import static io.julienmetral.tasks.support.WebCallers.user;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebLayerTest
class TaskAttachmentControllerWebMvcTests {

    private static final byte[] PDF_BYTES = "%PDF-1.4\n".getBytes(StandardCharsets.US_ASCII);

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private TaskAttachmentService attachmentService;

    @Autowired
    private TaskAuthorization taskAuthorization;

    @Autowired
    private TaskAttachmentAuthorization taskAttachmentAuthorization;

    @Autowired
    private UserRepository userRepository;

    private final UUID taskId = UUID.randomUUID();

    private final UUID attachmentId = UUID.randomUUID();

    @BeforeEach
    void callersAreActive() {
        everyAccountIsActive(userRepository);
    }

    @Test
    void anonymousCallerIsUnauthorized() throws Exception {
        mockMvc.perform(upload())
                .andExpect(status().isUnauthorized());
        mockMvc.perform(get(attachments()))
                .andExpect(status().isUnauthorized());
        mockMvc.perform(get(attachment()))
                .andExpect(status().isUnauthorized());
        mockMvc.perform(delete(attachment()))
                .andExpect(status().isUnauthorized());

        verifyNoInteractions(attachmentService);
    }

    @Nested
    class Uploading {

        @Test
        void missingFilePartIsRejected() throws Exception {
            mockMvc.perform(multipart(attachments()).with(admin(UUID.randomUUID())))
                    .andExpect(status().isBadRequest());

            verifyNoInteractions(attachmentService);
        }

        @Test
        void userWhoIsNotTheAssigneeIsForbiddenBeforeTheUpload() throws Exception {
            when(taskAuthorization.currentUserIsAssignedTo(eq(taskId), any())).thenReturn(false);

            mockMvc.perform(upload().with(user(UUID.randomUUID())))
                    .andExpect(status().isForbidden());

            verifyNoInteractions(attachmentService);
        }

        @Test
        void assigneeCheckIsAskedAboutTheTaskOfThePath() throws Exception {
            when(taskAuthorization.currentUserIsAssignedTo(eq(taskId), any())).thenReturn(true);
            when(attachmentService.add(eq(taskId), any())).thenReturn(attachment("report.pdf"));

            mockMvc.perform(upload().with(user(UUID.randomUUID())))
                    .andExpect(status().isCreated())
                    .andExpect(jsonPath("$.filename").value("report.pdf"));

            verify(taskAuthorization).currentUserIsAssignedTo(eq(taskId), any());
        }

        @Test
        void emptyFileReturnsBadRequest() throws Exception {
            expectUploadRejected(new EmptyMediaException())
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.title").value("Empty file"));
        }

        @Test
        void tooLargeFileReturnsContentTooLargeWithTheLimit() throws Exception {
            expectUploadRejected(new MediaTooLargeException(DataSize.ofMegabytes(25)))
                    .andExpect(status().isContentTooLarge())
                    .andExpect(jsonPath("$.title").value("File too large"))
                    .andExpect(jsonPath("$.detail").value("The file exceeds the maximum size of 25 MB"));
        }

        @Test
        void requestAboveTheMultipartLimitReturnsContentTooLarge() throws Exception {
            expectUploadRejected(new MaxUploadSizeExceededException(26L * 1024 * 1024))
                    .andExpect(status().isContentTooLarge())
                    .andExpect(jsonPath("$.detail").value("The request exceeds the maximum upload size"));
        }

        @Test
        void unsupportedTypeReturnsUnsupportedMediaType() throws Exception {
            expectUploadRejected(new UnsupportedMediaTypeException("text/html", MediaUsage.TASK_ATTACHMENT))
                    .andExpect(status().isUnsupportedMediaType())
                    .andExpect(jsonPath("$.title").value("Unsupported file type"));
        }

        @Test
        void infectedFileReturnsUnprocessableNamingTheThreat() throws Exception {
            expectUploadRejected(new InfectedMediaException("Eicar-Test-Signature"))
                    .andExpect(status().isUnprocessableContent())
                    .andExpect(jsonPath("$.title").value("File rejected by the antivirus"))
                    .andExpect(jsonPath("$.detail")
                            .value("The file was rejected by the antivirus: Eicar-Test-Signature"));
        }

        @Test
        void unreachableAntivirusReturnsServiceUnavailableWithoutTheCause() throws Exception {
            expectUploadRejected(new AntivirusUnavailableException("ERROR INSTREAM size limit exceeded"))
                    .andExpect(status().isServiceUnavailable())
                    .andExpect(jsonPath("$.detail").value("The antivirus is temporarily unavailable, try again later"));
        }

        @Test
        void unreachableStorageReturnsServiceUnavailable() throws Exception {
            expectUploadRejected(new StorageUnavailableException(new IOException("Connection refused")))
                    .andExpect(status().isServiceUnavailable())
                    .andExpect(jsonPath("$.title").value("Storage unavailable"));
        }

        @Test
        void uploadToUnknownTaskReturnsNotFoundProblem() throws Exception {
            expectUploadRejected(new TaskNotFoundException(taskId))
                    .andExpect(status().isNotFound())
                    .andExpect(jsonPath("$.title").value("Task not found"));
        }

        private ResultActions expectUploadRejected(RuntimeException rejection) throws Exception {
            when(attachmentService.add(eq(taskId), any())).thenThrow(rejection);

            return mockMvc.perform(upload().with(admin(UUID.randomUUID())));
        }
    }

    @Nested
    class Reading {

        @Test
        void unknownAttachmentReturnsNotFoundProblem() throws Exception {
            when(attachmentService.find(taskId, attachmentId))
                    .thenThrow(new TaskAttachmentNotFoundException(attachmentId));

            mockMvc.perform(get(attachment()).with(user(UUID.randomUUID())))
                    .andExpect(status().isNotFound())
                    .andExpect(jsonPath("$.status").value(404))
                    .andExpect(jsonPath("$.title").value("Attachment not found"));
        }

        @Test
        void malformedAttachmentIdReturnsBadRequest() throws Exception {
            mockMvc.perform(get(attachments() + "/not-a-uuid").with(user(UUID.randomUUID())))
                    .andExpect(status().isBadRequest());

            verifyNoInteractions(attachmentService);
        }
    }

    @Nested
    class Removing {

        @Test
        void userWhoDidNotUploadIsForbiddenBeforeTheRemoval() throws Exception {
            when(taskAttachmentAuthorization.currentUserUploaded(eq(attachmentId), any())).thenReturn(false);

            mockMvc.perform(delete(attachment()).with(user(UUID.randomUUID())))
                    .andExpect(status().isForbidden());

            verifyNoInteractions(attachmentService);
        }

        @Test
        void uploaderCheckIsAskedAboutTheAttachmentOfThePath() throws Exception {
            when(taskAttachmentAuthorization.currentUserUploaded(eq(attachmentId), any())).thenReturn(true);

            mockMvc.perform(delete(attachment()).with(user(UUID.randomUUID())))
                    .andExpect(status().isNoContent());

            verify(attachmentService).remove(taskId, attachmentId);
        }

        @Test
        void adminRemovesWithoutTheUploaderCheck() throws Exception {
            mockMvc.perform(delete(attachment()).with(admin(UUID.randomUUID())))
                    .andExpect(status().isNoContent());

            verifyNoInteractions(taskAttachmentAuthorization);
        }

        @Test
        void removingThroughAnotherTaskReturnsNotFoundProblem() throws Exception {
            doThrow(new TaskAttachmentNotFoundException(attachmentId))
                    .when(attachmentService).remove(taskId, attachmentId);

            mockMvc.perform(delete(attachment()).with(admin(UUID.randomUUID())))
                    .andExpect(status().isNotFound())
                    .andExpect(jsonPath("$.title").value("Attachment not found"));
        }
    }

    private String attachments() {
        return "/api/v1/tasks/" + taskId + "/attachments";
    }

    private String attachment() {
        return attachments() + "/" + attachmentId;
    }

    private MockMultipartHttpServletRequestBuilder upload() {
        return multipart(attachments()).file(new MockMultipartFile("file", "report.pdf", "application/pdf", PDF_BYTES));
    }

    private static TaskAttachment attachment(String filename) {
        Media media = new Media();
        media.setOriginalFilename(filename);
        media.setContentType("application/pdf");

        TaskAttachment attachment = new TaskAttachment();
        attachment.setMedia(media);
        return attachment;
    }
}
