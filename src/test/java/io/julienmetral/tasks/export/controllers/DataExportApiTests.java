package io.julienmetral.tasks.export.controllers;

import io.julienmetral.tasks.config.StorageProperties;
import io.julienmetral.tasks.export.AbstractDataExportTests;
import io.julienmetral.tasks.identity.entities.User;
import io.julienmetral.tasks.identity.entities.UserRole;
import org.apache.commons.csv.CSVRecord;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MvcResult;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.NoSuchKeyException;
import tools.jackson.databind.JsonNode;

import java.net.http.HttpResponse;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.stream.IntStream;

import static io.julienmetral.tasks.support.Problems.typedProblem;
import static io.julienmetral.tasks.support.Problems.untypedProblem;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.hamcrest.Matchers.nullValue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class DataExportApiTests extends AbstractDataExportTests {

    private static final List<String> TASK_COLUMNS = List.of(
            "id", "reference", "title", "description", "status", "priority", "assignee", "created_by",
            "due_at", "completed_at", "archived_at", "created_at", "updated_at"
    );

    private static final List<String> USER_COLUMNS = List.of(
            "id", "email", "display_name", "status", "roles", "email_verified_at", "created_at", "last_active_at"
    );

    @Autowired
    private S3Client s3Client;

    @Autowired
    private StorageProperties storageProperties;

    // Requests and their progress

    @Test
    void tasksExportIsAcceptedAsQueuedThenCompletedInTheBackground() throws Exception {
        User owner = createUser(UserRole.USER);
        insertTask(TaskRow.assignedTo(owner));

        MvcResult accepted = mockMvc.perform(post(TASKS_EXPORT)
                        .with(as(owner))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"assigneeId\": \"%s\"}".formatted(owner.getId())))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.type").value("TASKS_CSV"))
                .andExpect(jsonPath("$.status").value("QUEUED"))
                .andExpect(jsonPath("$.filters.status").value(nullValue()))
                .andExpect(jsonPath("$.filters.assigneeId").value(owner.getId().toString()))
                .andExpect(jsonPath("$.filters.archived").value(false))
                .andExpect(jsonPath("$.downloadUrl").value(nullValue()))
                .andReturn();
        UUID exportId = exportIdOf(accepted);
        assertThat(jsonMapper.readTree(accepted.getResponse().getContentAsString()).path("id").asString())
                .isEqualTo(exportId.toString());

        awaitCompleted(exportId);

        JsonNode export = exportJson(owner, exportId);
        Instant completedAt = Instant.parse(export.path("completedAt").asString());
        assertThat(export.path("status").asString()).isEqualTo("COMPLETED");
        assertThat(export.path("rowCount").asLong()).isOne();
        assertThat(Instant.parse(export.path("expiresAt").asString())).isEqualTo(completedAt.plus(Duration.ofDays(7)));
        assertThat(export.path("downloadUrl").asString()).contains(storageKeyOf(exportId));
        assertThat(Path.of(System.getProperty("java.io.tmpdir"), "exports", exportId + ".csv")).doesNotExist();
    }

    @Test
    void secondExportOfTheSameTypeWhileTheFirstIsQueuedIsAConflictButAnotherTypeIsAccepted() throws Exception {
        User admin = createUser(UserRole.ADMIN);
        List<UUID> accepted = new ArrayList<>();

        withExportListenerStopped(() -> {
            accepted.add(exportIdOf(requestTasksExport(admin, "{\"assigneeId\": \"%s\"}".formatted(admin.getId()))));

            mockMvc.perform(post(TASKS_EXPORT).with(as(admin)))
                    .andExpect(typedProblem(409, "export-in-progress", "Export already in progress"));

            accepted.add(exportIdOf(requestUsersExport(admin)));
        });

        accepted.forEach(this::awaitCompleted);
        assertThat(jdbcTemplate.queryForObject(
                "SELECT count(*) FROM data_exports WHERE owner_id = ? AND type = 'TASKS_CSV'",
                Integer.class,
                admin.getId()
        )).isOne();
        awaitCompleted(exportIdOf(requestTasksExport(admin, "{\"assigneeId\": \"%s\"}".formatted(admin.getId()))));
    }

    @Test
    void usersExportIsReservedToAdmins() throws Exception {
        User member = createUser(UserRole.USER);

        mockMvc.perform(post(USERS_EXPORT).with(as(member)))
                .andExpect(status().isForbidden());

        assertThat(exportCountOf(member)).isZero();
    }

    @Test
    void disabledAccountCannotAskForAnExport() throws Exception {
        User disabled = createUser(UserRole.ADMIN);
        jdbcTemplate.update("UPDATE users SET enabled = false WHERE id = ?", disabled.getId());
        jdbcTemplate.update("UPDATE user_profiles SET status = 'DISABLED' WHERE id = ?", disabled.getId());

        mockMvc.perform(post(TASKS_EXPORT).with(as(disabled)))
                .andExpect(status().isForbidden());
        mockMvc.perform(post(USERS_EXPORT).with(as(disabled)))
                .andExpect(status().isForbidden());

        assertThat(exportCountOf(disabled)).isZero();
    }

    // The tasks CSV

    @Test
    void tasksCsvHoldsTheAssigneesTasksThatAreNeitherArchivedNorDeleted() throws Exception {
        User owner = createUser(UserRole.USER);
        User assignee = createUser(UserRole.USER);
        User creator = createUser(UserRole.USER);
        Instant dueAt = Instant.parse("2031-02-03T04:05:06Z");
        String open = insertTask(TaskRow.assignedTo(assignee)
                .titled("Prepare the audit", "Gather the invoices")
                .createdBy(creator, dueAt));
        String done = insertTask(TaskRow.assignedTo(assignee).withStatus("DONE"));
        insertTask(TaskRow.assignedTo(assignee).archived());
        insertTask(TaskRow.assignedTo(assignee).deleted());
        insertTask(TaskRow.assignedTo(createUser(UserRole.USER)));

        UUID exportId = exportTasksOf(owner, assignee, "");

        Csv csv = downloadCsv(owner, exportId);
        assertThat(csv.header()).containsExactlyElementsOf(TASK_COLUMNS);
        assertThat(csv.column("reference")).containsExactlyInAnyOrder(open, done);
        assertThat(exportJson(owner, exportId).path("rowCount").asLong()).isEqualTo(2);

        CSVRecord row = csv.rowWhere("reference", open);
        assertThat(row.get("id")).isEqualTo(taskIdOf(open).toString());
        assertThat(row.get("title")).isEqualTo("Prepare the audit");
        assertThat(row.get("description")).isEqualTo("Gather the invoices");
        assertThat(row.get("status")).isEqualTo("TO_DO");
        assertThat(row.get("priority")).isEqualTo("HIGH");
        assertThat(row.get("assignee")).isEqualTo(assignee.getDisplayName());
        assertThat(row.get("created_by")).isEqualTo(creator.getDisplayName());
        assertThat(row.get("due_at")).isEqualTo("2031-02-03T04:05:06Z");
        assertThat(row.get("completed_at")).isEmpty();
        assertThat(row.get("archived_at")).isEmpty();
        assertThat(Instant.parse(row.get("created_at"))).isBefore(Instant.now());
    }

    @Test
    void everyValueIsQuotedAndEveryLineEndsWithCarriageReturnAndLineFeed() throws Exception {
        User owner = createUser(UserRole.USER);
        String reference = insertTask(TaskRow.assignedTo(owner));

        Csv csv = downloadCsv(owner, exportTasksOf(owner, owner, ""));

        String header = String.join(",", TASK_COLUMNS.stream().map(column -> "\"" + column + "\"").toList());
        assertThat(csv.text())
                .startsWith(header + "\r\n")
                .contains(",\"" + reference + "\",\"Exported task\",\"\",\"TO_DO\",\"HIGH\",")
                .endsWith("\"\r\n");
    }

    @Test
    void statusFilterKeepsOnlyTheTasksOfThatStatus() throws Exception {
        User owner = createUser(UserRole.USER);
        insertTask(TaskRow.assignedTo(owner));
        String blocked = insertTask(TaskRow.assignedTo(owner).withStatus("BLOCKED"));
        insertTask(TaskRow.assignedTo(owner).withStatus("DONE"));

        UUID exportId = exportTasksOf(owner, owner, "\"status\": \"BLOCKED\"");

        assertThat(downloadCsv(owner, exportId).column("reference")).containsExactly(blocked);
        assertThat(exportJson(owner, exportId).path("filters").path("status").asString()).isEqualTo("BLOCKED");
    }

    @Test
    void archivedFilterKeepsOnlyTheArchivedTasksThatAreNotDeleted() throws Exception {
        User owner = createUser(UserRole.USER);
        insertTask(TaskRow.assignedTo(owner));
        String archived = insertTask(TaskRow.assignedTo(owner).archived());
        insertTask(TaskRow.assignedTo(owner).archived().deleted());

        Csv csv = downloadCsv(owner, exportTasksOf(owner, owner, "\"archived\": true"));

        assertThat(csv.column("reference")).containsExactly(archived);
        assertThat(csv.rowWhere("reference", archived).get("archived_at")).isNotEmpty();
    }

    @Test
    void formulasAreNeutralisedWhileQuotesCommasAndLineBreaksSurvive() throws Exception {
        User owner = createUser(UserRole.USER);
        String title = "=HYPERLINK(\"https://evil.example\",\"Open\")";
        String description = "First line, with a comma\r\nSecond \"quoted\" line\n- a list item";
        String reference = insertTask(TaskRow.assignedTo(owner).titled(title, description));

        Csv csv = downloadCsv(owner, exportTasksOf(owner, owner, ""));

        CSVRecord row = csv.rowWhere("reference", reference);
        assertThat(row.get("title")).isEqualTo("'" + title);
        assertThat(row.get("description")).isEqualTo(description);
        assertThat(csv.text()).contains("\"'=HYPERLINK(\"\"https://evil.example\"\",\"\"Open\"\")\"");
    }

    @Test
    void exportWithoutMatchingTaskHoldsOnlyTheHeader() throws Exception {
        User owner = createUser(UserRole.USER);

        UUID exportId = exportTasksOf(owner, owner, "");

        Csv csv = downloadCsv(owner, exportId);
        assertThat(csv.header()).containsExactlyElementsOf(TASK_COLUMNS);
        assertThat(csv.records()).isEmpty();
        assertThat(exportJson(owner, exportId).path("rowCount").asLong()).isZero();
    }

    // More rows than a chunk (exports.chunk-size, 500): the reader pages by id, with named parameters when the export
    // has filters and positional ones otherwise
    @Test
    void exportsLongerThanAChunkAreReadPageByPageInIdOrder() throws Exception {
        User owner = createUser(UserRole.USER);
        String prefix = "BLK-" + UUID.randomUUID().toString().substring(0, 8);
        jdbcTemplate.update(
                """
                        INSERT INTO tasks (reference, title, status, priority, assigned_to_id, created_at, updated_at,
                                           version)
                        SELECT ? || '-' || n, 'Bulk task ' || n, 'TO_DO', 'LOW', ?, now(), now(), 0
                        FROM generate_series(1, 1001) AS n
                        """,
                prefix, owner.getId()
        );
        List<String> references = IntStream.rangeClosed(1, 1001).mapToObj(n -> prefix + "-" + n).toList();

        try {
            UUID filtered = exportTasksOf(owner, owner, "");
            Csv csv = downloadCsv(owner, filtered);

            assertThat(csv.column("reference")).containsExactlyInAnyOrderElementsOf(references);
            assertThat(csv.column("id")).isSorted().doesNotHaveDuplicates();
            assertThat(exportJson(owner, filtered).path("rowCount").asLong()).isEqualTo(1001);

            UUID unfiltered = exportIdOf(requestTasksExport(owner, "{}"));
            awaitCompleted(unfiltered);

            assertThat(downloadCsv(owner, unfiltered).column("reference")).containsAll(references);
        } finally {
            jdbcTemplate.update("DELETE FROM tasks WHERE assigned_to_id = ? AND reference LIKE ?",
                    owner.getId(), prefix + "-%");
        }
    }

    @Test
    void downloadLinkServesTheCsvAsAnAttachmentNamedAfterTheDay() throws Exception {
        User owner = createUser(UserRole.USER);
        UUID exportId = exportTasksOf(owner, owner, "");
        LocalDate day = LocalDate.ofInstant(
                Instant.parse(exportJson(owner, exportId).path("completedAt").asString()), ZoneOffset.UTC);

        HttpResponse<byte[]> response = download(owner, exportId);

        assertThat(response.headers().firstValue("Content-Type")).hasValue("text/csv");
        assertThat(response.headers().firstValue("Content-Disposition")).hasValueSatisfying(disposition ->
                assertThat(disposition).startsWith("attachment").contains("tasks-" + day + ".csv"));
    }

    @Test
    void ownerIsEmailedALinkToTheDownloadPageOnceTheExportIsReady() throws Exception {
        User owner = createUser(UserRole.USER);

        UUID exportId = exportTasksOf(owner, owner, "");

        String expiresAt = exportJson(owner, exportId).path("expiresAt").asString();
        assertThat(mailpit.latestTextTo(owner.getEmail(), "is ready"))
                .startsWith("Hello " + owner.getDisplayName() + ",")
                .contains("Your export of tasks is ready.")
                .contains("http://localhost:3000/exports?id=" + exportId)
                .contains("It stays available until " + expiresAt.substring(0, 16))
                .doesNotContain(storageKeyOf(exportId));
    }

    // The users CSV

    @Test
    void usersCsvListsTheAccountsThatAreNotDeletedWithTheirStatusAndRoles() throws Exception {
        User admin = createUser(UserRole.ADMIN);
        User member = createUser(UserRole.USER);
        User unverified = createUser(UserRole.USER);
        User disabled = createUser(UserRole.USER);
        User deleted = createUser(UserRole.USER);
        changeAccount(unverified, "email_verified_at = NULL", "UNVERIFIED");
        changeAccount(disabled, "enabled = false", "DISABLED");
        changeAccount(deleted, "deleted_at = now()", "DELETED");

        UUID exportId = exportIdOf(requestUsersExport(admin));
        awaitCompleted(exportId);

        Csv csv = downloadCsv(admin, exportId);
        assertThat(csv.header()).containsExactlyElementsOf(USER_COLUMNS);
        assertThat(csv.column("email"))
                .contains(admin.getEmail(), member.getEmail(), unverified.getEmail(), disabled.getEmail())
                .doesNotContain(deleted.getEmail());
        assertThat(exportJson(admin, exportId).path("rowCount").asLong()).isEqualTo(csv.records().size());

        CSVRecord adminRow = csv.rowWhere("email", admin.getEmail());
        assertThat(adminRow.get("id")).isEqualTo(admin.getId().toString());
        assertThat(adminRow.get("display_name")).isEqualTo(admin.getDisplayName());
        assertThat(adminRow.get("status")).isEqualTo("ACTIVE");
        assertThat(adminRow.get("roles")).isEqualTo("ADMIN USER");
        assertThat(adminRow.get("email_verified_at")).isNotEmpty();
        assertThat(adminRow.get("last_active_at")).isEmpty();
        assertThat(csv.rowWhere("email", member.getEmail()).get("roles")).isEqualTo("USER");
        assertThat(csv.rowWhere("email", unverified.getEmail()).get("status")).isEqualTo("UNVERIFIED");
        assertThat(csv.rowWhere("email", unverified.getEmail()).get("email_verified_at")).isEmpty();
        assertThat(csv.rowWhere("email", disabled.getEmail()).get("status")).isEqualTo("DISABLED");
    }

    // The caller's own exports

    @Test
    void listShowsTheCallersExportsNewestFirst() throws Exception {
        User admin = createUser(UserRole.ADMIN);
        UUID tasks = exportTasksOf(admin, admin, "");
        UUID users = exportIdOf(requestUsersExport(admin));
        awaitCompleted(users);

        mockMvc.perform(get(EXPORTS).with(as(admin)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.page.totalElements").value(2))
                .andExpect(jsonPath("$.content[0].id").value(users.toString()))
                .andExpect(jsonPath("$.content[0].type").value("USERS_CSV"))
                .andExpect(jsonPath("$.content[0].filters").value(nullValue()))
                .andExpect(jsonPath("$.content[1].id").value(tasks.toString()))
                .andExpect(jsonPath("$.content[1].downloadUrl").isNotEmpty());
    }

    @Test
    void exportOfSomeoneElseIsNotFoundAndCannotBeDeleted() throws Exception {
        User owner = createUser(UserRole.USER);
        User someoneElse = createUser(UserRole.ADMIN);
        UUID exportId = exportTasksOf(owner, owner, "");

        mockMvc.perform(get(EXPORTS + "/{id}", exportId).with(as(someoneElse)))
                .andExpect(untypedProblem(404, "Not Found"));
        mockMvc.perform(delete(EXPORTS + "/{id}", exportId).with(as(someoneElse)))
                .andExpect(untypedProblem(404, "Not Found"));
        mockMvc.perform(get(EXPORTS).with(as(someoneElse)))
                .andExpect(jsonPath("$.page.totalElements").value(0));

        assertThat(statusOf(exportId)).isEqualTo("COMPLETED");
    }

    @Test
    void deletingACompletedExportDeletesItsFile() throws Exception {
        User owner = createUser(UserRole.USER);
        UUID exportId = exportTasksOf(owner, owner, "");
        String storageKey = storageKeyOf(exportId);

        mockMvc.perform(delete(EXPORTS + "/{id}", exportId).with(as(owner)))
                .andExpect(status().isNoContent());

        mockMvc.perform(get(EXPORTS + "/{id}", exportId).with(as(owner)))
                .andExpect(status().isNotFound());
        assertThat(jdbcTemplate.queryForObject(
                "SELECT count(*) FROM media WHERE storage_key = ?", Integer.class, storageKey)).isZero();
        assertThatThrownBy(() -> s3Client.headObject(request -> request
                .bucket(storageProperties.bucket())
                .key(storageKey)))
                .isInstanceOf(NoSuchKeyException.class);
    }

    @Test
    void exportStillQueuedCannotBeDeleted() throws Exception {
        User owner = createUser(UserRole.USER);
        List<UUID> queued = new ArrayList<>();

        withExportListenerStopped(() -> {
            queued.add(exportIdOf(requestTasksExport(owner, "{\"assigneeId\": \"%s\"}".formatted(owner.getId()))));

            mockMvc.perform(delete(EXPORTS + "/{id}", queued.getFirst()).with(as(owner)))
                    .andExpect(typedProblem(409, "export-in-progress", "Export already in progress"));
            mockMvc.perform(get(EXPORTS + "/{id}", queued.getFirst()).with(as(owner)))
                    .andExpect(jsonPath("$.status").value("QUEUED"))
                    .andExpect(jsonPath("$.downloadUrl").value(nullValue()));
        });

        awaitCompleted(queued.getFirst());
    }

    private UUID taskIdOf(String reference) {
        return jdbcTemplate.queryForObject("SELECT id FROM tasks WHERE reference = ?", UUID.class, reference);
    }

    private int exportCountOf(User owner) {
        return jdbcTemplate.queryForObject(
                "SELECT count(*) FROM data_exports WHERE owner_id = ?", Integer.class, owner.getId());
    }

    // Changes an account as native SQL must: the account and the copy of its status on the profile
    private void changeAccount(User user, String accountChange, String status) {
        jdbcTemplate.update("UPDATE users SET " + accountChange + " WHERE id = ?", user.getId());
        jdbcTemplate.update("UPDATE user_profiles SET status = ? WHERE id = ?", status, user.getId());
    }
}
