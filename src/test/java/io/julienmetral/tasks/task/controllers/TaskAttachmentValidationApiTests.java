package io.julienmetral.tasks.task.controllers;

import io.julienmetral.tasks.TestcontainersConfiguration;
import io.julienmetral.tasks.identity.entities.User;
import io.julienmetral.tasks.identity.entities.UserRole;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.ResultActions;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Uploads the media service rejects: nothing is attached, stored or recorded in the history. */
class TaskAttachmentValidationApiTests extends AbstractTaskAttachmentApiTests {

    private static final int MAX_BYTES = 25 * 1024 * 1024;

    private static final byte[] PDF_SIGNATURE = "%PDF-1.4\n".getBytes(StandardCharsets.US_ASCII);

    @Test
    void emptyFileIsRejected() throws Exception {
        User admin = createUser(UserRole.ADMIN);
        UUID taskId = createTask(admin, null);

        assertRejected(admin, taskId, upload(asAdmin(admin), taskId, "empty.pdf", new byte[0]))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.title").value("Empty file"));
    }

    @Test
    void missingFilePartIsRejected() throws Exception {
        User admin = createUser(UserRole.ADMIN);
        UUID taskId = createTask(admin, null);

        mockMvc.perform(multipart(attachmentsOf(taskId)).with(asAdmin(admin)))
                .andExpect(status().isBadRequest());

        assertThat(attachmentCount(taskId)).isZero();
    }

    @Test
    void fileJustAboveTheLimitIsRejected() throws Exception {
        User admin = createUser(UserRole.ADMIN);
        UUID taskId = createTask(admin, null);

        assertRejected(admin, taskId, upload(asAdmin(admin), taskId, "large.pdf", pdfOfSize(MAX_BYTES + 1)))
                .andExpect(status().isContentTooLarge())
                .andExpect(jsonPath("$.title").value("File too large"));
    }

    @Test
    void fileAtTheLimitIsAccepted() throws Exception {
        User admin = createUser(UserRole.ADMIN);
        UUID taskId = createTask(admin, null);

        upload(asAdmin(admin), taskId, "large.pdf", pdfOfSize(MAX_BYTES))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.sizeBytes").value(MAX_BYTES))
                .andExpect(jsonPath("$.contentType").value("application/pdf"));
    }

    @Test
    void executableDisguisedAsPdfIsRejected() throws Exception {
        User admin = createUser(UserRole.ADMIN);
        UUID taskId = createTask(admin, null);

        assertRejected(admin, taskId, upload(asAdmin(admin), taskId, "invoice.pdf", "application/pdf", executable()))
                .andExpect(status().isUnsupportedMediaType())
                .andExpect(jsonPath("$.title").value("Unsupported file type"));
    }

    @Test
    void htmlIsRejected() throws Exception {
        User admin = createUser(UserRole.ADMIN);
        UUID taskId = createTask(admin, null);
        byte[] html = ("<!DOCTYPE html><html><head><title>" + UUID.randomUUID()
                + "</title></head><body><script>alert(1)</script></body></html>").getBytes(StandardCharsets.UTF_8);

        assertRejected(admin, taskId, upload(asAdmin(admin), taskId, "page.html", "text/plain", html))
                .andExpect(status().isUnsupportedMediaType());
    }

    @Test
    void eicarIsRejectedByTheAntivirus() throws Exception {
        User admin = createUser(UserRole.ADMIN);
        UUID taskId = createTask(admin, null);

        assertRejected(admin, taskId, upload(asAdmin(admin), taskId, "notes.txt", "text/plain", EICAR))
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.title").value("File rejected by the antivirus"))
                .andExpect(jsonPath("$.detail").value("The file was rejected by the antivirus: " + TestcontainersConfiguration.EICAR_THREAT));
    }

    @Test
    void rejectedUploadOfTheAssigneeLeavesExistingAttachmentsAlone() throws Exception {
        User admin = createUser(UserRole.ADMIN);
        User assignee = createUser(UserRole.USER);
        UUID taskId = createTask(admin, assignee);
        UUID existing = uploadPdf(asUser(assignee), taskId, "report.pdf");

        upload(asUser(assignee), taskId, "notes.txt", EICAR)
                .andExpect(status().isUnprocessableContent());

        assertThat(attachmentCount(taskId)).isOne();
        assertThat(mediaCountUploadedBy(assignee)).isOne();
        find(asUser(assignee), taskId, existing)
                .andExpect(status().isOk());
    }

    private ResultActions assertRejected(User uploader, UUID taskId, ResultActions result) throws Exception {
        assertThat(attachmentCount(taskId)).isZero();
        assertThat(mediaCountUploadedBy(uploader)).isZero();
        assertThat(eventsThroughApi(uploader, taskId))
                .noneMatch(event -> event.get("type").asString().equals("ATTACHMENT_ADDED"));

        return result;
    }

    // Detection reads only the leading bytes, so a PDF signature followed by padding is typed as a PDF
    private static byte[] pdfOfSize(int size) {
        byte[] content = new byte[size];
        Arrays.fill(content, (byte) ' ');
        System.arraycopy(PDF_SIGNATURE, 0, content, 0, PDF_SIGNATURE.length);
        byte[] marker = UUID.randomUUID().toString().getBytes(StandardCharsets.US_ASCII);
        System.arraycopy(marker, 0, content, PDF_SIGNATURE.length, marker.length);
        return content;
    }

    // DOS and PE headers of a Windows executable
    private static byte[] executable() {
        byte[] content = new byte[512];
        ThreadLocalRandom.current().nextBytes(content);
        content[0] = 'M';
        content[1] = 'Z';
        Arrays.fill(content, 2, 60, (byte) 0);
        content[60] = (byte) 0x80;
        content[61] = 0;
        content[62] = 0;
        content[63] = 0;
        content[0x80] = 'P';
        content[0x81] = 'E';
        content[0x82] = 0;
        content[0x83] = 0;
        return content;
    }
}
