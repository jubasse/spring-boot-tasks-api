package io.julienmetral.tasks.export.personaldata;

import io.julienmetral.tasks.notification.webhook.WebhookUrls;
import io.julienmetral.tasks.support.JdbcSliceTest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@JdbcSliceTest
class PersonalDataQueriesTests {

    private static final Instant CREATED_AT = Instant.parse("2030-01-01T08:00:00Z");

    private static final Instant VERIFIED_AT = Instant.parse("2030-01-01T09:00:00Z");

    private static final Instant DELETED_AT = Instant.parse("2030-02-01T08:00:00Z");

    private static final String WEBHOOK_SECRET = "v1:encrypted-signing-secret";

    private static final String SLACK_URL = "v1:encrypted-slack-url-with-its-token";

    @Autowired
    private PersonalDataQueries queries;

    @Autowired
    private JdbcTemplate jdbc;

    private UUID owner;

    private UUID other;

    private String otherEmail;

    @BeforeEach
    void twoAccounts() {
        owner = insertUser("owner-" + UUID.randomUUID() + "@example.com", "Owner Zoé", VERIFIED_AT);
        otherEmail = "other-" + UUID.randomUUID() + "@example.com";
        other = insertUser(otherEmail, "Other Łukasz", VERIFIED_AT);
        jdbc.update("INSERT INTO user_roles (user_id, role) VALUES (?, 'USER'), (?, 'ADMIN'), (?, 'USER')",
                owner, owner, other);
    }

    // Account

    @Test
    void accountHoldsItsFieldsWithTimesAsInstantsAndNoPasswordHash() {
        Map<String, Object> account = queries.account(owner);

        assertThat(account).containsOnlyKeys(
                "id", "email", "display_name", "status", "roles", "email_verified_at", "last_login_at",
                "last_active_at", "inactivity_warned_at", "created_at", "has_profile_photo");
        assertThat(account)
                .containsEntry("id", owner)
                .containsEntry("display_name", "Owner Zoé")
                .containsEntry("status", "ACTIVE")
                .containsEntry("roles", "ADMIN USER")
                .containsEntry("email_verified_at", VERIFIED_AT)
                .containsEntry("created_at", CREATED_AT)
                .containsEntry("last_login_at", null)
                .containsEntry("has_profile_photo", false);
    }

    @Test
    void accountOfAnUnknownIdIsNull() {
        assertThat(queries.account(UUID.randomUUID())).isNull();
    }

    @Test
    void accountSoftDeletedIsStillExported() {
        jdbc.update("UPDATE users SET deleted_at = ? WHERE id = ?", Timestamp.from(DELETED_AT), owner);

        assertThat(queries.account(owner)).containsEntry("id", owner);
    }

    // Profile photo

    @Test
    void profilePhotoIsTheStoredObjectOfTheProcessedPhoto() {
        UUID photo = insertMedia(owner, "AVATAR", "avatar/" + UUID.randomUUID(), "image/png", "me.png");
        jdbc.update("UPDATE user_profiles SET avatar_media_id = ? WHERE id = ?", photo, owner);

        assertThat(queries.profilePhoto(owner)).containsOnlyKeys("storage_key", "content_type")
                .containsEntry("storage_key", storageKeyOf(photo))
                .containsEntry("content_type", "image/png");
        assertThat(queries.account(owner)).containsEntry("has_profile_photo", true);
    }

    @Test
    void accountWithoutPhotoOrWithAPhotoStillPendingHasNone() {
        UUID pending = insertMedia(owner, "AVATAR_UPLOAD", "avatar-upload/" + UUID.randomUUID(), "image/png", "me.png");
        jdbc.update("UPDATE user_profiles SET pending_avatar_media_id = ? WHERE id = ?", pending, owner);

        assertThat(queries.profilePhoto(owner)).isNull();
    }

    // Notification settings

    @Test
    void notificationSettingsAreTheSwitchesOfTheAccount() {
        jdbc.update("""
                INSERT INTO notification_settings (user_id, task_assigned, task_unassigned, task_cancelled,
                                                   task_deleted, task_commented, task_mentioned, task_due_soon,
                                                   task_overdue, updated_at)
                VALUES (?, false, true, true, true, true, false, true, true, ?)
                """, owner, Timestamp.from(CREATED_AT));

        assertThat(queries.notificationSettings(owner))
                .containsOnlyKeys("task_assigned", "task_unassigned", "task_cancelled", "task_deleted",
                        "task_commented", "task_mentioned", "task_due_soon", "task_overdue", "updated_at")
                .containsEntry("task_assigned", false)
                .containsEntry("task_mentioned", false)
                .containsEntry("task_overdue", true)
                .containsEntry("updated_at", CREATED_AT);
    }

    @Test
    void accountThatKeptTheDefaultSettingsHasNone() {
        assertThat(queries.notificationSettings(owner)).isNull();
    }

    // Webhooks

    @Test
    void webhooksShowTheirUrlAndEventsAsTheApiNamesThemButNeitherSecretNorSlackUrl() {
        UUID webhook = insertWebhook(owner, "WEBHOOK", "https://hooks.example.com/tasks", "TASK_DUE_SOON",
                "TASK_ASSIGNED");
        UUID slack = insertWebhook(owner, "SLACK", SLACK_URL, "TASK_OVERDUE");
        insertWebhook(other, "WEBHOOK", "https://hooks.example.com/other", "TASK_ASSIGNED");

        List<Map<String, Object>> webhooks = queries.webhooks(owner);

        assertThat(webhooks).extracting(row -> row.get("id")).containsExactly(webhook, slack);
        assertThat(webhooks.getFirst())
                .containsOnlyKeys("id", "kind", "url", "events", "disabled_at", "disabled_reason", "created_at",
                        "updated_at")
                .containsEntry("url", "https://hooks.example.com/tasks")
                .containsEntry("events", "task.assigned task.due_soon");
        assertThat(webhooks.get(1))
                .containsEntry("kind", "SLACK")
                .containsEntry("url", WebhookUrls.SLACK_MASK)
                .containsEntry("events", "task.overdue");
        assertThat(valuesOf(webhooks)).noneMatch(value -> value.contains("encrypted"));
    }

    // Tasks

    @Test
    void tasksAreThoseTheAccountCreatedOrIsAssignedToDeletedOnesIncluded() {
        UUID created = insertTask("Created by the owner", owner, other, null);
        UUID assigned = insertTask("Assigned to the owner", other, owner, null);
        UUID deleted = insertTask("Deleted by the owner", owner, null, DELETED_AT);
        UUID someoneElses = insertTask("Between other people", other, other, null);

        List<Map<String, Object>> tasks = queries.tasks(owner);

        assertThat(tasks).extracting(row -> row.get("id"))
                .containsExactlyInAnyOrder(created, assigned, deleted)
                .doesNotContain(someoneElses);
        assertThat(rowWithId(tasks, created))
                .containsEntry("created_by_you", true)
                .containsEntry("assigned_to_you", false)
                .containsEntry("created_by", "Owner Zoé")
                .containsEntry("assignee", "Other Łukasz")
                .containsEntry("deleted_at", null);
        assertThat(rowWithId(tasks, assigned))
                .containsEntry("created_by_you", false)
                .containsEntry("assigned_to_you", true)
                .containsEntry("created_by", "Other Łukasz");
        assertThat(rowWithId(tasks, deleted)).containsEntry("deleted_at", DELETED_AT);
    }

    // Comments, attachments, history and exports

    @Test
    void commentsAreThoseTheAccountWroteWithTheirTaskReference() {
        UUID task = insertTask("Discussed", other, other, null);
        UUID mine = insertComment(task, owner, "My comment");
        insertComment(task, other, "Their comment");

        List<Map<String, Object>> comments = queries.comments(owner);

        assertThat(comments).singleElement().satisfies(comment -> assertThat(comment)
                .containsOnlyKeys("id", "task_reference", "body", "created_at", "edited_at")
                .containsEntry("id", mine)
                .containsEntry("task_reference", referenceOf(task))
                .containsEntry("body", "My comment")
                .containsEntry("created_at", CREATED_AT));
    }

    @Test
    void attachmentsAreTheFilesTheAccountUploadedWithoutTheirStorageKey() {
        UUID task = insertTask("With files", other, other, null);
        UUID mine = insertMedia(owner, "TASK_ATTACHMENT", "task-attachment/" + UUID.randomUUID(), "application/pdf",
                "invoice.pdf");
        UUID theirs = insertMedia(other, "TASK_ATTACHMENT", "task-attachment/" + UUID.randomUUID(), "application/pdf",
                "theirs.pdf");
        attach(task, mine);
        attach(task, theirs);

        assertThat(queries.attachments(owner)).singleElement().satisfies(attachment -> assertThat(attachment)
                .containsOnlyKeys("id", "task_reference", "original_filename", "content_type", "size_bytes",
                        "created_at")
                .containsEntry("id", mine)
                .containsEntry("task_reference", referenceOf(task))
                .containsEntry("original_filename", "invoice.pdf"));
    }

    @Test
    void historyIsWhatTheAccountDidWithoutTheDetailsOfEachEvent() {
        UUID task = insertTask("Changed", other, other, null);
        insertEvent(task, owner, "STATUS_CHANGED");
        insertEvent(task, other, "ARCHIVED");

        assertThat(queries.history(owner)).singleElement().satisfies(event -> assertThat(event)
                .containsOnlyKeys("id", "task_reference", "type", "occurred_at")
                .containsEntry("task_reference", referenceOf(task))
                .containsEntry("type", "STATUS_CHANGED")
                .containsEntry("occurred_at", CREATED_AT));
    }

    // Sessions

    @Test
    void sessionsAreTheAccountsRefreshTokensWithoutTheirHash() {
        UUID family = UUID.randomUUID();
        insertRefreshToken(owner, family, CREATED_AT, CREATED_AT.plusSeconds(900), null);
        insertRefreshToken(other, UUID.randomUUID(), CREATED_AT, CREATED_AT.plusSeconds(900), null);

        List<Map<String, Object>> sessions = queries.sessions(owner);

        assertThat(sessions).extracting(row -> row.get("family_id")).containsExactly(family);
        assertThat(sessions.getFirst())
                .containsOnlyKeys("family_id", "created_at", "expires_at", "revoked_at")
                .containsEntry("created_at", CREATED_AT)
                .containsEntry("expires_at", CREATED_AT.plusSeconds(900))
                .containsEntry("revoked_at", null);
    }

    @Test
    void revokedSessionShowsWhenItWasRevoked() {
        insertRefreshToken(owner, UUID.randomUUID(), CREATED_AT, CREATED_AT.plusSeconds(900), DELETED_AT);

        assertThat(queries.sessions(owner)).singleElement()
                .satisfies(session -> assertThat(session).containsEntry("revoked_at", DELETED_AT));
    }

    // Mentions

    @Test
    void mentionsAreOfTheAccountWithoutTheCommentBody() {
        UUID task = insertTask("Discussed", other, other, null);
        UUID comment = insertComment(task, other, "Hey <@" + owner + ">, thoughts?");
        mention(comment, owner);
        UUID theirComment = insertComment(task, owner, "Hey <@" + other + ">, look at this");
        mention(theirComment, other);

        List<Map<String, Object>> mentions = queries.mentions(owner);

        assertThat(mentions).singleElement().satisfies(row -> assertThat(row)
                .containsOnlyKeys("comment_id", "task_reference", "author", "created_at")
                .containsEntry("comment_id", comment)
                .containsEntry("task_reference", referenceOf(task))
                .containsEntry("author", "Other Łukasz")
                .containsEntry("created_at", CREATED_AT));
        assertThat(valuesOf(mentions)).noneMatch(value -> value.contains("thoughts"));
    }

    // Reminders

    @Test
    void remindersAreThoseSentToTheAccount() {
        UUID task = insertTask("Due soon", other, owner, null);
        insertReminder(task, owner, "DUE_SOON", CREATED_AT.plusSeconds(3600), CREATED_AT);
        insertReminder(task, other, "OVERDUE", CREATED_AT.plusSeconds(7200), CREATED_AT);

        assertThat(queries.reminders(owner)).singleElement().satisfies(row -> assertThat(row)
                .containsOnlyKeys("task_reference", "kind", "due_at", "sent_at")
                .containsEntry("task_reference", referenceOf(task))
                .containsEntry("kind", "DUE_SOON")
                .containsEntry("due_at", CREATED_AT.plusSeconds(3600))
                .containsEntry("sent_at", CREATED_AT));
    }

    // Webhook deliveries

    @Test
    void webhookDeliveriesAreThoseOfTheAccountsEndpointsWithTheApisEventNamesButNoPayload() {
        UUID endpoint = insertWebhook(owner, "WEBHOOK", "https://hooks.example.com/tasks", "TASK_ASSIGNED");
        UUID othersEndpoint = insertWebhook(other, "WEBHOOK", "https://hooks.example.com/other", "TASK_ASSIGNED");
        UUID delivery = insertWebhookDelivery(
                endpoint, "TASK_ASSIGNED", "DELIVERED", 1, 200, CREATED_AT, "{\"secret-detail\": true}");
        insertWebhookDelivery(othersEndpoint, "TASK_ASSIGNED", "DELIVERED", 1, 200, CREATED_AT, "{}");

        List<Map<String, Object>> deliveries = queries.webhookDeliveries(owner);

        assertThat(deliveries).singleElement().satisfies(row -> assertThat(row)
                .containsOnlyKeys("id", "endpoint_id", "event", "status", "attempts", "last_status_code",
                        "created_at", "delivered_at")
                .containsEntry("id", delivery)
                .containsEntry("endpoint_id", endpoint)
                .containsEntry("event", "task.assigned")
                .containsEntry("status", "DELIVERED")
                .containsEntry("attempts", 1)
                .containsEntry("last_status_code", 200)
                .containsEntry("created_at", CREATED_AT));
        assertThat(valuesOf(deliveries)).noneMatch(value -> value.contains("secret-detail"));
    }

    @Test
    void exportsAreThoseOfTheAccount() {
        UUID mine = insertExport(owner);
        insertExport(other);

        assertThat(queries.exports(owner)).singleElement().satisfies(export -> assertThat(export)
                .containsOnlyKeys("id", "type", "status", "row_count", "created_at", "completed_at", "expires_at")
                .containsEntry("id", mine)
                .containsEntry("type", "PERSONAL_DATA"));
    }

    // Across every section

    @Test
    void otherAccountsAppearByDisplayNameOnlyAndNoSecretAppearsAnywhere() {
        UUID task = insertTask("Shared", other, owner, null);
        insertComment(task, owner, "Hello");
        insertEvent(task, owner, "COMMENT_ADDED");
        insertWebhook(owner, "WEBHOOK", "https://hooks.example.com/tasks", "TASK_ASSIGNED");
        insertExport(owner);

        List<String> values = new ArrayList<>();
        values.addAll(valuesOf(List.of(queries.account(owner))));
        values.addAll(valuesOf(queries.webhooks(owner)));
        values.addAll(valuesOf(queries.tasks(owner)));
        values.addAll(valuesOf(queries.comments(owner)));
        values.addAll(valuesOf(queries.history(owner)));
        values.addAll(valuesOf(queries.exports(owner)));

        assertThat(values)
                .contains("Other Łukasz")
                .noneMatch(value -> value.contains(otherEmail))
                .noneMatch(value -> value.contains(other.toString()))
                .noneMatch(value -> value.contains(WEBHOOK_SECRET))
                .noneMatch(value -> value.startsWith("$argon2"));
    }

    private UUID insertUser(String email, String displayName, Instant verifiedAt) {
        UUID id = jdbc.queryForObject(
                """
                        INSERT INTO user_profiles (display_name, status, created_at, updated_at)
                        VALUES (?, 'ACTIVE', ?, ?)
                        RETURNING id
                        """,
                UUID.class,
                displayName, Timestamp.from(CREATED_AT), Timestamp.from(CREATED_AT)
        );
        jdbc.update(
                """
                        INSERT INTO users (id, email, password_hash, enabled, email_verified_at, created_at, updated_at)
                        VALUES (?, ?, '$argon2id$v=19$m=16384,t=2,p=1$c2FsdA$aGFzaA', true, ?, ?, ?)
                        """,
                id, email, Timestamp.from(verifiedAt), Timestamp.from(CREATED_AT), Timestamp.from(CREATED_AT)
        );
        return id;
    }

    private UUID insertWebhook(UUID user, String kind, String url, String... events) {
        UUID id = jdbc.queryForObject(
                """
                        INSERT INTO webhook_endpoints (user_id, kind, url, secret, previous_secret, created_at,
                                                       updated_at)
                        VALUES (?, ?, ?, ?, ?, ?, ?)
                        RETURNING id
                        """,
                UUID.class,
                user, kind, url, WEBHOOK_SECRET, WEBHOOK_SECRET + "-previous", Timestamp.from(CREATED_AT),
                Timestamp.from(CREATED_AT)
        );
        for (String event : events) {
            jdbc.update("INSERT INTO webhook_endpoint_events (endpoint_id, event) VALUES (?, ?)", id, event);
        }
        return id;
    }

    private UUID insertTask(String title, UUID creator, UUID assignee, Instant deletedAt) {
        return jdbc.queryForObject(
                """
                        INSERT INTO tasks (reference, title, status, priority, created_by_id, assigned_to_id,
                                           created_at, updated_at, deleted_at, version)
                        VALUES (?, ?, 'TO_DO', 'LOW', ?, ?, ?, ?, ?, 0)
                        RETURNING id
                        """,
                UUID.class,
                "PDQ-" + UUID.randomUUID().toString().substring(0, 8), title, creator, assignee,
                Timestamp.from(CREATED_AT), Timestamp.from(CREATED_AT),
                deletedAt == null ? null : Timestamp.from(deletedAt)
        );
    }

    private UUID insertComment(UUID task, UUID author, String body) {
        return jdbc.queryForObject(
                "INSERT INTO task_comments (task_id, author_id, body, created_at) VALUES (?, ?, ?, ?) RETURNING id",
                UUID.class,
                task, author, body, Timestamp.from(CREATED_AT)
        );
    }

    private UUID insertMedia(UUID uploader, String usage, String storageKey, String contentType, String filename) {
        return jdbc.queryForObject(
                """
                        INSERT INTO media (storage_key, usage, original_filename, content_type, size_bytes, sha256,
                                           uploaded_by_id, created_at)
                        VALUES (?, ?, ?, ?, 2048, repeat('0', 64), ?, ?)
                        RETURNING id
                        """,
                UUID.class,
                storageKey, usage, filename, contentType, uploader, Timestamp.from(CREATED_AT)
        );
    }

    private void attach(UUID task, UUID media) {
        jdbc.update("INSERT INTO task_attachments (task_id, media_id, created_at) VALUES (?, ?, ?)",
                task, media, Timestamp.from(CREATED_AT));
    }

    private void insertEvent(UUID task, UUID actor, String type) {
        jdbc.update(
                """
                        INSERT INTO task_events (task_id, actor_id, type, payload, occurred_at)
                        VALUES (?, ?, ?, '{"detail": "kept in the task history"}'::jsonb, ?)
                        """,
                task, actor, type, Timestamp.from(CREATED_AT)
        );
    }

    private UUID insertRefreshToken(UUID user, UUID familyId, Instant createdAt, Instant expiresAt, Instant revokedAt) {
        String tokenHash = UUID.randomUUID().toString().replace("-", "").repeat(2).substring(0, 64);
        return jdbc.queryForObject(
                """
                        INSERT INTO refresh_tokens (user_id, token_hash, family_id, created_at, expires_at,
                                                     revoked_at)
                        VALUES (?, ?, ?, ?, ?, ?)
                        RETURNING id
                        """,
                UUID.class,
                user, tokenHash, familyId, Timestamp.from(createdAt), Timestamp.from(expiresAt),
                revokedAt == null ? null : Timestamp.from(revokedAt)
        );
    }

    private void mention(UUID comment, UUID user) {
        jdbc.update("INSERT INTO task_comment_mentions (comment_id, user_id) VALUES (?, ?)", comment, user);
    }

    private void insertReminder(UUID task, UUID recipient, String kind, Instant dueAt, Instant sentAt) {
        jdbc.update(
                "INSERT INTO task_reminders (task_id, recipient_id, kind, due_at, sent_at) VALUES (?, ?, ?, ?, ?)",
                task, recipient, kind, Timestamp.from(dueAt), Timestamp.from(sentAt)
        );
    }

    private UUID insertWebhookDelivery(UUID endpoint, String event, String status, int attempts,
            Integer lastStatusCode, Instant createdAt, String payload) {
        return jdbc.queryForObject(
                """
                        INSERT INTO webhook_deliveries (endpoint_id, event, payload, status, attempts,
                                                         last_status_code, delivered_at, created_at)
                        VALUES (?, ?, ?, ?, ?, ?, ?, ?)
                        RETURNING id
                        """,
                UUID.class,
                endpoint, event, payload, status, attempts, lastStatusCode, Timestamp.from(createdAt),
                Timestamp.from(createdAt)
        );
    }

    private UUID insertExport(UUID owner) {
        return jdbc.queryForObject(
                """
                        INSERT INTO data_exports (owner_id, type, status, attempts, created_at)
                        VALUES (?, 'PERSONAL_DATA', 'RUNNING', 1, ?)
                        RETURNING id
                        """,
                UUID.class,
                owner, Timestamp.from(CREATED_AT)
        );
    }

    private String referenceOf(UUID task) {
        return jdbc.queryForObject("SELECT reference FROM tasks WHERE id = ?", String.class, task);
    }

    private String storageKeyOf(UUID media) {
        return jdbc.queryForObject("SELECT storage_key FROM media WHERE id = ?", String.class, media);
    }

    private static Map<String, Object> rowWithId(List<Map<String, Object>> rows, UUID id) {
        return rows.stream().filter(row -> id.equals(row.get("id"))).findFirst().orElseThrow();
    }

    private static List<String> valuesOf(List<Map<String, Object>> rows) {
        return rows.stream()
                .flatMap(row -> row.values().stream())
                .map(String::valueOf)
                .toList();
    }
}
