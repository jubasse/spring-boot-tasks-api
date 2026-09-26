package io.julienmetral.tasks.task.controllers;

import io.julienmetral.tasks.identity.entities.User;
import io.julienmetral.tasks.identity.entities.UserRole;
import org.junit.jupiter.api.Test;
import org.springframework.http.ContentDisposition;
import org.springframework.mock.web.MockHttpServletResponse;
import tools.jackson.databind.JsonNode;

import java.net.http.HttpResponse;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.nullValue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Uploading, listing, reading, downloading and removing task attachments, and the history they leave. */
class TaskAttachmentApiTests extends AbstractTaskAttachmentApiTests {

    @Test
    void uploadReturnsCreatedAttachmentWithLocationAndUploader() throws Exception {
        User admin = createUser(UserRole.ADMIN);
        User assignee = createUser(UserRole.USER);
        UUID taskId = createTask(admin, assignee);
        byte[] pdf = uniquePdf();
        Instant before = Instant.now();

        MockHttpServletResponse response = upload(asUser(assignee), taskId, "report.pdf", "application/pdf", pdf)
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.filename").value("report.pdf"))
                .andExpect(jsonPath("$.contentType").value("application/pdf"))
                .andExpect(jsonPath("$.sizeBytes").value(pdf.length))
                .andExpect(jsonPath("$.uploadedBy.id").value(assignee.getId().toString()))
                .andExpect(jsonPath("$.uploadedBy.displayName").value(assignee.getDisplayName()))
                .andExpect(jsonPath("$.uploadedBy.status").value("ACTIVE"))
                .andExpect(jsonPath("$.uploadedBy.avatarUrl").value(nullValue()))
                .andExpect(jsonPath("$.commentId").value(nullValue()))
                .andExpect(jsonPath("$.downloadUrl").isNotEmpty())
                .andReturn()
                .getResponse();

        JsonNode body = json(response.getContentAsString());
        UUID attachmentId = UUID.fromString(body.get("id").asString());

        assertThat(response.getHeader("Location")).isEqualTo(attachment(taskId, attachmentId));
        assertThat(Instant.parse(body.get("createdAt").asString())).isBetween(before, Instant.now());
        assertThat(storageKeyOf(attachmentId)).matches("task-attachment/[0-9a-f-]{36}");
        assertThat(objectBytes(storageKeyOf(attachmentId))).isEqualTo(pdf);
    }

    @Test
    void downloadUrlServesTheBytesUnderTheNonAsciiFilename() throws Exception {
        User admin = createUser(UserRole.ADMIN);
        UUID taskId = createTask(admin, null);
        byte[] pdf = uniquePdf();
        String filename = "Compte rendu réunion 東京.pdf";

        String body = upload(asAdmin(admin), taskId, filename, "application/pdf", pdf)
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.filename").value(filename))
                .andReturn()
                .getResponse()
                .getContentAsString();

        HttpResponse<byte[]> download = download(json(body).get("downloadUrl").asString());

        assertThat(download.body()).isEqualTo(pdf);
        assertThat(download.headers().firstValue("Content-Type")).hasValue("application/pdf");
        assertThat(ContentDisposition.parse(download.headers().firstValue("Content-Disposition").orElseThrow()))
                .satisfies(disposition -> {
                    assertThat(disposition.isAttachment()).isTrue();
                    assertThat(disposition.getFilename()).isEqualTo(filename);
                });
    }

    @Test
    void docxKeepsItsDetectedWordType() throws Exception {
        User admin = createUser(UserRole.ADMIN);
        UUID taskId = createTask(admin, null);
        byte[] docx = uniqueDocx();

        String body = upload(asAdmin(admin), taskId, "letter.docx", docx)
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.contentType").value(DOCX))
                .andReturn()
                .getResponse()
                .getContentAsString();

        HttpResponse<byte[]> download = download(json(body).get("downloadUrl").asString());

        assertThat(download.body()).isEqualTo(docx);
        assertThat(download.headers().firstValue("Content-Type")).hasValue(DOCX);
    }

    @Test
    void odtKeepsItsDetectedOpenDocumentType() throws Exception {
        User admin = createUser(UserRole.ADMIN);
        UUID taskId = createTask(admin, null);
        byte[] odt = uniqueOdt();

        String body = upload(asAdmin(admin), taskId, "letter.odt", "application/zip", odt)
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.contentType").value(ODT))
                .andReturn()
                .getResponse()
                .getContentAsString();

        HttpResponse<byte[]> download = download(json(body).get("downloadUrl").asString());

        assertThat(download.body()).isEqualTo(odt);
        assertThat(download.headers().firstValue("Content-Type")).hasValue(ODT);
    }

    @Test
    void declaredContentTypeIsIgnoredInFavourOfTheDetectedOne() throws Exception {
        User admin = createUser(UserRole.ADMIN);
        UUID taskId = createTask(admin, null);

        upload(asAdmin(admin), taskId, "notes", "image/png", uniqueText())
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.contentType").value("text/plain"));
    }

    @Test
    void listReturnsAttachmentsOldestFirst() throws Exception {
        User admin = createUser(UserRole.ADMIN);
        User assignee = createUser(UserRole.USER);
        UUID taskId = createTask(admin, assignee);

        UUID first = uploadPdf(asAdmin(admin), taskId, "first.pdf");
        UUID second = uploadPdf(asUser(assignee), taskId, "second.pdf");
        UUID third = uploadPdf(asAdmin(admin), taskId, "third.pdf");

        list(asUser(assignee), taskId)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(3)))
                .andExpect(jsonPath("$[0].id").value(first.toString()))
                .andExpect(jsonPath("$[0].filename").value("first.pdf"))
                .andExpect(jsonPath("$[1].id").value(second.toString()))
                .andExpect(jsonPath("$[1].uploadedBy.id").value(assignee.getId().toString()))
                .andExpect(jsonPath("$[2].id").value(third.toString()))
                .andExpect(jsonPath("$[2].downloadUrl").isNotEmpty());
    }

    @Test
    void listOfTaskWithoutAttachmentsIsEmpty() throws Exception {
        User admin = createUser(UserRole.ADMIN);
        UUID taskId = createTask(admin, null);

        list(asAdmin(admin), taskId)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(0)));
    }

    @Test
    void attachmentsOfOtherTasksAreNotListed() throws Exception {
        User admin = createUser(UserRole.ADMIN);
        UUID taskId = createTask(admin, null);
        UUID otherTaskId = createTask(admin, null);

        UUID own = uploadPdf(asAdmin(admin), taskId, "own.pdf");
        uploadPdf(asAdmin(admin), otherTaskId, "other.pdf");

        list(asAdmin(admin), taskId)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(1)))
                .andExpect(jsonPath("$[0].id").value(own.toString()));
    }

    @Test
    void findReturnsTheAttachmentWithAWorkingDownloadUrl() throws Exception {
        User admin = createUser(UserRole.ADMIN);
        UUID taskId = createTask(admin, null);
        byte[] text = uniqueText();

        String created = upload(asAdmin(admin), taskId, "notes.txt", "text/plain", text)
                .andExpect(status().isCreated())
                .andReturn()
                .getResponse()
                .getContentAsString();
        UUID attachmentId = UUID.fromString(json(created).get("id").asString());

        String found = find(asAdmin(admin), taskId, attachmentId)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(attachmentId.toString()))
                .andExpect(jsonPath("$.filename").value("notes.txt"))
                .andExpect(jsonPath("$.contentType").value("text/plain"))
                .andExpect(jsonPath("$.sizeBytes").value(text.length))
                .andExpect(jsonPath("$.uploadedBy.id").value(admin.getId().toString()))
                .andReturn()
                .getResponse()
                .getContentAsString();

        // Postgres rounds the timestamp to the microsecond, the upload response carries the unrounded value
        assertThat(Instant.parse(json(found).get("createdAt").asString()))
                .isCloseTo(Instant.parse(json(created).get("createdAt").asString()), within(1, ChronoUnit.MICROS));

        HttpResponse<byte[]> download = download(json(found).get("downloadUrl").asString());

        assertThat(download.body()).isEqualTo(text);
        assertThat(download.headers().firstValue("Content-Type")).hasValue("text/plain");
    }

    @Test
    void findUnknownAttachmentReturnsNotFound() throws Exception {
        User admin = createUser(UserRole.ADMIN);
        UUID taskId = createTask(admin, null);

        find(asAdmin(admin), taskId, UUID.randomUUID())
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.title").value("Attachment not found"));
    }

    @Test
    void findAttachmentThroughAnotherTaskReturnsNotFound() throws Exception {
        User admin = createUser(UserRole.ADMIN);
        UUID taskId = createTask(admin, null);
        UUID otherTaskId = createTask(admin, null);
        UUID attachmentId = uploadPdf(asAdmin(admin), taskId, "report.pdf");

        find(asAdmin(admin), otherTaskId, attachmentId)
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.title").value("Attachment not found"));
    }

    @Test
    void uploadToUnknownTaskReturnsNotFound() throws Exception {
        User admin = createUser(UserRole.ADMIN);

        upload(asAdmin(admin), UUID.randomUUID(), "report.pdf", uniquePdf())
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.title").value("Task not found"));

        assertThat(mediaCountUploadedBy(admin)).isZero();
    }

    @Test
    void listOfUnknownTaskReturnsNotFound() throws Exception {
        User admin = createUser(UserRole.ADMIN);

        list(asAdmin(admin), UUID.randomUUID())
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.title").value("Task not found"));
    }

    @Test
    void attachmentsOfSoftDeletedTaskAreNotFound() throws Exception {
        User admin = createUser(UserRole.ADMIN);
        UUID taskId = createTask(admin, null);
        UUID attachmentId = uploadPdf(asAdmin(admin), taskId, "report.pdf");

        mockMvc.perform(delete(TASKS + "/" + taskId).with(asAdmin(admin)))
                .andExpect(status().isNoContent());

        upload(asAdmin(admin), taskId, "late.pdf", uniquePdf())
                .andExpect(status().isNotFound());
        list(asAdmin(admin), taskId)
                .andExpect(status().isNotFound());
        find(asAdmin(admin), taskId, attachmentId)
                .andExpect(status().isNotFound());
        remove(asAdmin(admin), taskId, attachmentId)
                .andExpect(status().isNotFound());
    }

    @Test
    void uploadRecordsAttachmentAddedEvent() throws Exception {
        User admin = createUser(UserRole.ADMIN);
        User assignee = createUser(UserRole.USER);
        UUID taskId = createTask(admin, assignee);

        UUID attachmentId = uploadPdf(asUser(assignee), taskId, "plan été.pdf");

        JsonNode latest = eventsThroughApi(admin, taskId).get(0);
        assertThat(latest.get("type").asString()).isEqualTo("ATTACHMENT_ADDED");
        assertThat(latest.get("actor").get("id").asString()).isEqualTo(assignee.getId().toString());
        assertThat(latest.get("payload").get("attachmentId").asString()).isEqualTo(attachmentId.toString());
        assertThat(latest.get("payload").get("filename").asString()).isEqualTo("plan été.pdf");
    }

    @Test
    void removeDeletesTheRowAndTheStoredObject() throws Exception {
        User admin = createUser(UserRole.ADMIN);
        UUID taskId = createTask(admin, null);
        UUID attachmentId = uploadPdf(asAdmin(admin), taskId, "report.pdf");
        String storageKey = storageKeyOf(attachmentId);

        remove(asAdmin(admin), taskId, attachmentId)
                .andExpect(status().isNoContent());

        assertThat(attachmentCount(taskId)).isZero();
        assertThat(mediaCountUploadedBy(admin)).isZero();
        assertObjectMissing(storageKey);
        find(asAdmin(admin), taskId, attachmentId)
                .andExpect(status().isNotFound());
        list(asAdmin(admin), taskId)
                .andExpect(jsonPath("$", hasSize(0)));
    }

    @Test
    void removeKeepsTheOtherAttachmentsOfTheTask() throws Exception {
        User admin = createUser(UserRole.ADMIN);
        UUID taskId = createTask(admin, null);
        UUID removed = uploadPdf(asAdmin(admin), taskId, "removed.pdf");
        UUID kept = uploadPdf(asAdmin(admin), taskId, "kept.pdf");
        String keptKey = storageKeyOf(kept);

        remove(asAdmin(admin), taskId, removed)
                .andExpect(status().isNoContent());

        list(asAdmin(admin), taskId)
                .andExpect(jsonPath("$", hasSize(1)))
                .andExpect(jsonPath("$[0].id").value(kept.toString()));
        assertThat(objectBytes(keptKey)).isNotEmpty();
    }

    @Test
    void removeRecordsAttachmentRemovedEvent() throws Exception {
        User admin = createUser(UserRole.ADMIN);
        User otherAdmin = createUser(UserRole.ADMIN);
        UUID taskId = createTask(admin, null);
        UUID attachmentId = uploadPdf(asAdmin(admin), taskId, "report.pdf");

        remove(asAdmin(otherAdmin), taskId, attachmentId)
                .andExpect(status().isNoContent());

        JsonNode events = eventsThroughApi(admin, taskId);
        JsonNode latest = events.get(0);
        assertThat(latest.get("type").asString()).isEqualTo("ATTACHMENT_REMOVED");
        assertThat(latest.get("actor").get("id").asString()).isEqualTo(otherAdmin.getId().toString());
        assertThat(latest.get("payload").get("attachmentId").asString()).isEqualTo(attachmentId.toString());
        assertThat(latest.get("payload").get("filename").asString()).isEqualTo("report.pdf");
        assertThat(events.get(1).get("type").asString()).isEqualTo("ATTACHMENT_ADDED");
    }

    @Test
    void removeUnknownAttachmentReturnsNotFound() throws Exception {
        User admin = createUser(UserRole.ADMIN);
        UUID taskId = createTask(admin, null);

        remove(asAdmin(admin), taskId, UUID.randomUUID())
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.title").value("Attachment not found"));
    }

    @Test
    void removeThroughAnotherTaskReturnsNotFoundAndKeepsTheAttachment() throws Exception {
        User admin = createUser(UserRole.ADMIN);
        UUID taskId = createTask(admin, null);
        UUID otherTaskId = createTask(admin, null);
        UUID attachmentId = uploadPdf(asAdmin(admin), taskId, "report.pdf");
        String storageKey = storageKeyOf(attachmentId);

        remove(asAdmin(admin), otherTaskId, attachmentId)
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.title").value("Attachment not found"));

        assertThat(attachmentCount(taskId)).isOne();
        assertThat(objectBytes(storageKey)).isNotEmpty();
    }
}
