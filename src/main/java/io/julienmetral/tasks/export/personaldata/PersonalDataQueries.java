package io.julienmetral.tasks.export.personaldata;

import io.julienmetral.tasks.notification.webhook.WebhookUrls;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * What the API holds about one account, section by section, for its personal data export. Native SQL: the account's
 * soft-deleted tasks are its data too, and JPA hides them. Other people appear by id and display name only; secrets
 * (password hash, token hashes, webhook signing secrets) never appear, and a Slack URL, which is one, is masked.
 */
@Repository
@RequiredArgsConstructor
public class PersonalDataQueries {

    private final NamedParameterJdbcTemplate jdbc;

    public Map<String, Object> account(UUID userId) {
        return one("""
                SELECT u.id, u.email, p.display_name, p.status,
                       (SELECT string_agg(r.role, ' ' ORDER BY r.role) FROM user_roles r WHERE r.user_id = u.id) AS roles,
                       u.email_verified_at, u.last_login_at, u.last_active_at, u.inactivity_warned_at, u.created_at,
                       (p.avatar_media_id IS NOT NULL) AS has_profile_photo
                FROM users u
                JOIN user_profiles p ON p.id = u.id
                WHERE u.id = :userId
                """, userId);
    }

    public Map<String, Object> notificationSettings(UUID userId) {
        return one("""
                SELECT task_assigned, task_unassigned, task_cancelled, task_deleted, task_commented, task_mentioned,
                       task_due_soon, task_overdue, updated_at
                FROM notification_settings
                WHERE user_id = :userId
                """, userId);
    }

    public List<Map<String, Object>> webhooks(UUID userId) {
        return list("""
                SELECT w.id, w.kind,
                       CASE WHEN w.kind = 'SLACK' THEN '%s' ELSE w.url END AS url,
                       -- The API's event names: TASK_DUE_SOON is task.due_soon
                       (SELECT string_agg(lower(regexp_replace(e.event, '^TASK_', 'task.')), ' ' ORDER BY e.event)
                        FROM webhook_endpoint_events e WHERE e.endpoint_id = w.id) AS events,
                       w.disabled_at, w.disabled_reason, w.created_at, w.updated_at
                FROM webhook_endpoints w
                WHERE w.user_id = :userId
                ORDER BY w.created_at
                """.formatted(WebhookUrls.SLACK_MASK), userId);
    }

    /** The tasks the account created or is assigned to, deleted ones included (with their deletion date). */
    public List<Map<String, Object>> tasks(UUID userId) {
        return list("""
                SELECT t.id, t.reference, t.title, t.description, t.status, t.priority,
                       (t.created_by_id = :userId) AS created_by_you, (t.assigned_to_id = :userId) AS assigned_to_you,
                       c.display_name AS created_by, a.display_name AS assignee,
                       t.due_at, t.completed_at, t.archived_at, t.cancelled_at, t.cancelled_reason, t.blocked_at,
                       t.blocked_reason, t.created_at, t.updated_at, t.deleted_at
                FROM tasks t
                LEFT JOIN user_profiles c ON c.id = t.created_by_id
                LEFT JOIN user_profiles a ON a.id = t.assigned_to_id
                WHERE t.created_by_id = :userId OR t.assigned_to_id = :userId
                ORDER BY t.created_at
                """, userId);
    }

    public List<Map<String, Object>> comments(UUID userId) {
        return list("""
                SELECT c.id, t.reference AS task_reference, c.body, c.created_at, c.edited_at
                FROM task_comments c
                JOIN tasks t ON t.id = c.task_id
                WHERE c.author_id = :userId
                ORDER BY c.created_at
                """, userId);
    }

    public List<Map<String, Object>> attachments(UUID userId) {
        return list("""
                SELECT m.id, t.reference AS task_reference, m.original_filename, m.content_type, m.size_bytes,
                       m.created_at
                FROM media m
                JOIN task_attachments ta ON ta.media_id = m.id
                JOIN tasks t ON t.id = ta.task_id
                WHERE m.uploaded_by_id = :userId
                ORDER BY m.created_at
                """, userId);
    }

    /** What the account did, from the history of every task; the details stay in each task's history. */
    public List<Map<String, Object>> history(UUID userId) {
        return list("""
                SELECT e.id, t.reference AS task_reference, e.type, e.occurred_at
                FROM task_events e
                JOIN tasks t ON t.id = e.task_id
                WHERE e.actor_id = :userId
                ORDER BY e.occurred_at
                """, userId);
    }

    /** Mentions of the account in comments, others' included: who, where and when, not their text. */
    public List<Map<String, Object>> mentions(UUID userId) {
        return list("""
                SELECT c.id AS comment_id, t.reference AS task_reference, a.display_name AS author, c.created_at
                FROM task_comment_mentions m
                JOIN task_comments c ON c.id = m.comment_id
                JOIN tasks t ON t.id = c.task_id
                LEFT JOIN user_profiles a ON a.id = c.author_id
                WHERE m.user_id = :userId
                ORDER BY c.created_at
                """, userId);
    }

    public List<Map<String, Object>> reminders(UUID userId) {
        return list("""
                SELECT t.reference AS task_reference, r.kind, r.due_at, r.sent_at
                FROM task_reminders r
                JOIN tasks t ON t.id = r.task_id
                WHERE r.recipient_id = :userId
                ORDER BY r.sent_at
                """, userId);
    }

    /** One row per refresh token, never its hash: when each session started, and when it ended or will end. */
    public List<Map<String, Object>> sessions(UUID userId) {
        return list("""
                SELECT family_id, created_at, expires_at, revoked_at
                FROM refresh_tokens
                WHERE user_id = :userId
                ORDER BY created_at
                """, userId);
    }

    /** Deliveries to the account's webhooks, still kept (see webhooks.delivery-retention); not their payloads. */
    public List<Map<String, Object>> webhookDeliveries(UUID userId) {
        return list("""
                SELECT d.id, d.endpoint_id, lower(regexp_replace(d.event, '^TASK_', 'task.')) AS event, d.status, d.attempts, d.last_status_code, d.created_at,
                       d.delivered_at
                FROM webhook_deliveries d
                JOIN webhook_endpoints w ON w.id = d.endpoint_id
                WHERE w.user_id = :userId
                ORDER BY d.created_at
                """, userId);
    }

    public List<Map<String, Object>> exports(UUID userId) {
        return list("""
                SELECT id, type, status, row_count, created_at, completed_at, expires_at
                FROM data_exports
                WHERE owner_id = :userId
                ORDER BY created_at
                """, userId);
    }

    /** The stored profile photo, or null. */
    public Map<String, Object> profilePhoto(UUID userId) {
        return one("""
                SELECT m.storage_key, m.content_type
                FROM user_profiles p
                JOIN media m ON m.id = p.avatar_media_id
                WHERE p.id = :userId
                """, userId);
    }

    private Map<String, Object> one(String sql, UUID userId) {
        List<Map<String, Object>> rows = list(sql, userId);

        return rows.isEmpty() ? null : rows.getFirst();
    }

    private List<Map<String, Object>> list(String sql, UUID userId) {
        return jdbc.queryForList(sql, new MapSqlParameterSource("userId", userId)).stream()
                .map(PersonalDataQueries::withInstants)
                .toList();
    }

    // Instants serialize as ISO-8601 in UTC, like every date of the API; a Timestamp would take the JVM's zone
    private static Map<String, Object> withInstants(Map<String, Object> row) {
        Map<String, Object> converted = new LinkedHashMap<>();

        row.forEach((column, value) -> converted.put(
                column, value instanceof Timestamp timestamp ? timestamp.toInstant() : value));

        return converted;
    }
}
