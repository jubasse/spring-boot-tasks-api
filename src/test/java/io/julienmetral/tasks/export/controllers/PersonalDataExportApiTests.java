package io.julienmetral.tasks.export.controllers;

import io.julienmetral.tasks.export.AbstractDataExportTests;
import io.julienmetral.tasks.identity.entities.User;
import io.julienmetral.tasks.identity.entities.UserRole;
import io.julienmetral.tasks.media.services.ObjectStorage;
import io.julienmetral.tasks.notification.webhook.WebhookUrls;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.PDFTextStripper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.web.servlet.MvcResult;
import tools.jackson.databind.JsonNode;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.net.http.HttpResponse;
import java.nio.file.Path;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

import static io.julienmetral.tasks.support.Problems.typedProblem;
import static java.nio.charset.StandardCharsets.UTF_8;
import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.nullValue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class PersonalDataExportApiTests extends AbstractDataExportTests {

    private static final String MY_DATA_EXPORT = EXPORTS + "/my-data";

    private static final String WEBHOOK_SECRET = "v1:encrypted-signing-secret-of-the-test";

    private static final byte[] PHOTO = {(byte) 0x89, 'P', 'N', 'G', '\r', '\n', 0x1A, '\n', 1, 2, 3};

    @Autowired
    private ObjectStorage objectStorage;

    @Test
    void personalDataIsProducedInTheBackgroundAsAnArchiveOfTheJsonAndThePdf() throws Exception {
        User owner = createUser(UserRole.USER);
        User colleague = createUser(UserRole.USER);
        String created = insertTask(TaskRow.assignedTo(colleague)
                .titled("Prepare the audit été", "Gather the invoices")
                .createdBy(owner, null));
        String assigned = insertTask(TaskRow.assignedTo(owner)
                .titled("Review the budget", null)
                .createdBy(colleague, null));
        String deleted = insertTask(TaskRow.assignedTo(owner)
                .titled("Removed task", null)
                .createdBy(owner, null)
                .deleted());
        insertComment(created, owner, "<b>Done</b> on my side");
        insertSlackWebhook(owner);

        MvcResult accepted = mockMvc.perform(post(MY_DATA_EXPORT).with(as(owner)))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.type").value("PERSONAL_DATA"))
                .andExpect(jsonPath("$.status").value("QUEUED"))
                .andExpect(jsonPath("$.filters").value(nullValue()))
                .andReturn();
        UUID exportId = exportIdOf(accepted);
        awaitCompleted(exportId);

        JsonNode export = exportJson(owner, exportId);
        assertThat(export.path("rowCount").isNull()).isTrue();
        HttpResponse<byte[]> download = download(owner, exportId);
        LocalDate day = LocalDate.ofInstant(Instant.parse(export.path("completedAt").asString()), ZoneOffset.UTC);
        assertThat(download.headers().firstValue("Content-Type")).hasValue("application/zip");
        assertThat(download.headers().firstValue("Content-Disposition")).hasValueSatisfying(disposition ->
                assertThat(disposition).startsWith("attachment").contains("my-data-" + day + ".zip"));
        Map<String, byte[]> archive = entriesOf(download.body());
        assertThat(archive).containsOnlyKeys("my-data.json", "my-data.pdf");

        String jsonText = new String(archive.get("my-data.json"), UTF_8);
        JsonNode json = jsonMapper.readTree(jsonText);
        assertThat(json.path("format").asString()).isEqualTo("tasks-api-personal-data");
        assertThat(json.path("version").asInt()).isEqualTo(1);
        assertThat(Instant.parse(json.path("exported_at").asString())).isBeforeOrEqualTo(Instant.now());
        assertThat(json.path("account").path("id").asString()).isEqualTo(owner.getId().toString());
        assertThat(json.path("account").path("email").asString()).isEqualTo(owner.getEmail());
        assertThat(json.path("account").path("has_profile_photo").asBoolean()).isFalse();
        assertThat(referencesOf(json.path("tasks"))).containsExactlyInAnyOrder(created, assigned, deleted);
        assertThat(taskWithReference(json, deleted).path("deleted_at").isNull()).isFalse();
        assertThat(taskWithReference(json, created).path("assignee").asString())
                .isEqualTo(colleague.getDisplayName());
        assertThat(json.path("comments").path(0).path("body").asString()).isEqualTo("<b>Done</b> on my side");
        assertThat(json.path("webhooks").path(0).path("url").asString()).isEqualTo(WebhookUrls.SLACK_MASK);
        assertThat(json.path("webhooks").path(0).path("events").asString()).isEqualTo("task.assigned task.due_soon");
        assertThat(json.path("exports").valueStream().map(row -> row.path("id").asString()))
                .contains(exportId.toString());
        assertThat(jsonText)
                .doesNotContain(colleague.getEmail())
                .doesNotContain(WEBHOOK_SECRET)
                .doesNotContain("hooks.slack.com/services/T")
                .doesNotContain("$argon2")
                .doesNotContain("password");

        String pdfText = textOf(archive.get("my-data.pdf"));
        assertThat(pdfText).contains(
                owner.getEmail(), owner.getDisplayName(), created, "Review the budget", "<b>Done</b> on my side");
        assertThat(Path.of(System.getProperty("java.io.tmpdir"), "exports", exportId.toString())).doesNotExist();
    }

    @Test
    void profilePhotoOfTheAccountIsAddedToTheArchive() throws Exception {
        User owner = createUser(UserRole.USER);
        givePhoto(owner);

        UUID exportId = exportIdOf(mockMvc.perform(post(MY_DATA_EXPORT).with(as(owner)))
                .andExpect(status().isAccepted())
                .andReturn());
        awaitCompleted(exportId);

        Map<String, byte[]> archive = entriesOf(download(owner, exportId).body());
        assertThat(archive).containsOnlyKeys("my-data.json", "my-data.pdf", "profile-photo.png");
        assertThat(archive.get("profile-photo.png")).isEqualTo(PHOTO);
        assertThat(jsonMapper.readTree(archive.get("my-data.json")).path("account").path("has_profile_photo")
                .asBoolean()).isTrue();
        assertThat(textOf(archive.get("my-data.pdf"))).contains("Yes, in the archive");
    }

    @Test
    void ownerIsEmailedOnceTheirPersonalDataIsReady() throws Exception {
        User owner = createUser(UserRole.USER);

        UUID exportId = exportIdOf(mockMvc.perform(post(MY_DATA_EXPORT).with(as(owner)))
                .andExpect(status().isAccepted())
                .andReturn());
        awaitCompleted(exportId);

        assertThat(mailpit.latestTextTo(owner.getEmail(), "is ready"))
                .contains("Your export of your personal data is ready.")
                .contains("http://localhost:3000/exports?id=" + exportId);
    }

    @Test
    void secondPersonalDataExportWhileTheFirstIsQueuedIsAConflictButATasksExportIsAccepted() throws Exception {
        User owner = createUser(UserRole.USER);
        List<UUID> accepted = new ArrayList<>();

        withExportListenerStopped(() -> {
            accepted.add(exportIdOf(mockMvc.perform(post(MY_DATA_EXPORT).with(as(owner)))
                    .andExpect(status().isAccepted())
                    .andReturn()));

            mockMvc.perform(post(MY_DATA_EXPORT).with(as(owner)))
                    .andExpect(typedProblem(409, "export-in-progress", "Export already in progress"));

            accepted.add(exportIdOf(requestTasksExport(owner, "{\"assigneeId\": \"%s\"}".formatted(owner.getId()))));
        });

        accepted.forEach(this::awaitCompleted);
        assertThat(jdbcTemplate.queryForObject(
                "SELECT count(*) FROM data_exports WHERE owner_id = ? AND type = 'PERSONAL_DATA'",
                Integer.class,
                owner.getId()
        )).isOne();
    }

    @Test
    void disabledAccountCannotAskForItsPersonalData() throws Exception {
        User disabled = createUser(UserRole.USER);
        jdbcTemplate.update("UPDATE users SET enabled = false WHERE id = ?", disabled.getId());
        jdbcTemplate.update("UPDATE user_profiles SET status = 'DISABLED' WHERE id = ?", disabled.getId());

        mockMvc.perform(post(MY_DATA_EXPORT).with(as(disabled)))
                .andExpect(status().isForbidden());

        assertThat(jdbcTemplate.queryForObject(
                "SELECT count(*) FROM data_exports WHERE owner_id = ?", Integer.class, disabled.getId())).isZero();
    }

    private void insertComment(String taskReference, User author, String body) {
        jdbcTemplate.update(
                """
                        INSERT INTO task_comments (task_id, author_id, body, created_at)
                        SELECT id, ?, ?, now() FROM tasks WHERE reference = ?
                        """,
                author.getId(), body, taskReference
        );
    }

    private void insertSlackWebhook(User owner) {
        UUID endpoint = jdbcTemplate.queryForObject(
                """
                        INSERT INTO webhook_endpoints (user_id, kind, url, secret, created_at, updated_at)
                        VALUES (?, 'SLACK', 'https://hooks.slack.com/services/T0001/B0002/token', ?, now(), now())
                        RETURNING id
                        """,
                UUID.class,
                owner.getId(), WEBHOOK_SECRET
        );
        jdbcTemplate.update(
                "INSERT INTO webhook_endpoint_events (endpoint_id, event) VALUES (?, ?), (?, ?)",
                endpoint, "TASK_DUE_SOON", endpoint, "TASK_ASSIGNED");
    }

    // A processed photo as the avatar worker leaves it: a stored object and the media row the profile points to
    private void givePhoto(User owner) {
        String storageKey = "avatar/" + UUID.randomUUID();
        objectStorage.put(storageKey, new ByteArrayInputStream(PHOTO), PHOTO.length, "image/png");
        UUID media = jdbcTemplate.queryForObject(
                """
                        INSERT INTO media (storage_key, usage, original_filename, content_type, size_bytes, sha256,
                                           uploaded_by_id, created_at)
                        VALUES (?, 'AVATAR', 'avatar.png', 'image/png', ?, repeat('0', 64), ?, ?)
                        RETURNING id
                        """,
                UUID.class,
                storageKey, PHOTO.length, owner.getId(), Timestamp.from(Instant.now())
        );
        jdbcTemplate.update("UPDATE user_profiles SET avatar_media_id = ? WHERE id = ?", media, owner.getId());
    }

    private static Map<String, byte[]> entriesOf(byte[] zipFile) throws IOException {
        Map<String, byte[]> entries = new LinkedHashMap<>();

        try (ZipInputStream zip = new ZipInputStream(new ByteArrayInputStream(zipFile))) {
            for (ZipEntry entry = zip.getNextEntry(); entry != null; entry = zip.getNextEntry()) {
                entries.put(entry.getName(), zip.readAllBytes());
            }
        }

        return entries;
    }

    private static List<String> referencesOf(JsonNode tasks) {
        return tasks.valueStream().map(task -> task.path("reference").asString()).toList();
    }

    private static JsonNode taskWithReference(JsonNode json, String reference) {
        return json.path("tasks").valueStream()
                .filter(task -> task.path("reference").asString().equals(reference))
                .findFirst()
                .orElseThrow();
    }

    // Narrow table cells wrap their text: words are compared whatever the line breaks between them
    private static String textOf(byte[] pdf) throws IOException {
        try (PDDocument document = Loader.loadPDF(pdf)) {
            return new PDFTextStripper().getText(document).replaceAll("\\s+", " ");
        }
    }
}
