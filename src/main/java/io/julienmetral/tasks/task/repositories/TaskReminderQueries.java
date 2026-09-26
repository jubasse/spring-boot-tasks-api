package io.julienmetral.tasks.task.repositories;

import io.julienmetral.tasks.task.entities.TaskReminderKind;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/** Native SQL: recording a reminder and learning whether it was new is one {@code INSERT ... ON CONFLICT}. */
@Repository
@RequiredArgsConstructor
public class TaskReminderQueries {

    // Any stable number, distinct from the media cleanup's: every instance must use the same one
    private static final long LOCK_KEY = 2_906_551_730_418_667L;

    private final NamedParameterJdbcTemplate jdbc;

    public record Reminder(
            UUID taskId,
            String reference,
            String title,
            Instant dueAt,
            UUID recipientId
    ) {
    }

    /** Transaction-scoped lock, released on commit or rollback; false when another instance holds it. */
    public boolean tryLock() {
        return Boolean.TRUE.equals(jdbc.queryForObject(
                "SELECT pg_try_advisory_xact_lock(:key)",
                new MapSqlParameterSource("key", LOCK_KEY),
                Boolean.class
        ));
    }

    /**
     * Records a reminder for every open, assigned task due in {@code (from, to]} whose assignee was not yet reminded
     * of this kind for this due date.
     *
     * @return only the reminders recorded by this call
     */
    public List<Reminder> recordReminders(TaskReminderKind kind, Instant from, Instant to, Instant now) {
        return jdbc.query(
                """
                        WITH due AS (
                            SELECT id, reference, title, due_at, assigned_to_id
                            FROM tasks
                            WHERE deleted_at IS NULL
                              AND archived_at IS NULL
                              AND status NOT IN ('DONE', 'CANCELLED', 'ARCHIVED')
                              AND assigned_to_id IS NOT NULL
                              AND due_at > :from AND due_at <= :to
                        ), recorded AS (
                            INSERT INTO task_reminders (task_id, recipient_id, kind, due_at, sent_at)
                            SELECT id, assigned_to_id, :kind, due_at, :now FROM due
                            ON CONFLICT ON CONSTRAINT "task_reminders_task_id_kind_due_at_recipient_idUQ" DO NOTHING
                            RETURNING task_id, recipient_id
                        )
                        SELECT d.id, d.reference, d.title, d.due_at, r.recipient_id
                        FROM recorded r
                        JOIN due d ON d.id = r.task_id
                        ORDER BY d.due_at, d.reference
                        """,
                new MapSqlParameterSource()
                        .addValue("kind", kind.name())
                        .addValue("from", Timestamp.from(from))
                        .addValue("to", Timestamp.from(to))
                        .addValue("now", Timestamp.from(now)),
                (row, index) -> new Reminder(
                        row.getObject("id", UUID.class),
                        row.getString("reference"),
                        row.getString("title"),
                        row.getTimestamp("due_at").toInstant(),
                        row.getObject("recipient_id", UUID.class)
                )
        );
    }
}
