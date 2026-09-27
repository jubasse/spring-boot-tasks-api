package io.julienmetral.tasks.task.repositories;

import io.julienmetral.tasks.identity.repositories.UserRetentionQueries;
import io.julienmetral.tasks.media.repositories.MediaCleanupQueries;
import io.julienmetral.tasks.support.JdbcSliceTest;
import io.julienmetral.tasks.task.entities.TaskStatus;
import io.julienmetral.tasks.task.repositories.TaskReminderQueries.Reminder;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;

import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static io.julienmetral.tasks.support.Transactions.inNewTransaction;
import static io.julienmetral.tasks.task.entities.TaskReminderKind.DUE_SOON;
import static io.julienmetral.tasks.task.entities.TaskReminderKind.OVERDUE;
import static org.assertj.core.api.Assertions.assertThat;

@JdbcSliceTest
class TaskReminderQueriesTests {

    // Every due date sits in 2000, before anything the other test contexts write to the shared database: the windows
    // below reach only this class's tasks
    private static final Instant NOW = Instant.parse("2000-06-01T12:00:00Z");

    private static final Duration LEAD_TIME = Duration.ofHours(24);

    private static final Duration LOOKBACK = Duration.ofDays(7);

    @Autowired
    private TaskReminderQueries queries;

    @Autowired
    private MediaCleanupQueries mediaCleanupQueries;

    @Autowired
    private UserRetentionQueries userRetentionQueries;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private PlatformTransactionManager transactionManager;

    @Test
    void recordingAReminderReturnsItAndStoresItAsSent() {
        UUID assignee = insertProfile();
        Instant dueAt = NOW.plus(Duration.ofHours(1));
        UUID task = insertTask(assignee, dueAt);

        List<Reminder> reminders = dueSoonAt(NOW);

        assertThat(reminders).contains(new Reminder(task, referenceOf(task), "Reminded task", dueAt, assignee));
        assertThat(jdbc.queryForMap("SELECT * FROM task_reminders WHERE task_id = ?", task))
                .containsEntry("recipient_id", assignee)
                .containsEntry("kind", "DUE_SOON")
                .containsEntry("due_at", Timestamp.from(dueAt))
                .containsEntry("sent_at", Timestamp.from(NOW));
    }

    @Test
    void taskDueExactlyNowIsOverdueRatherThanDueSoon() {
        UUID assignee = insertProfile();
        UUID dueNow = insertTask(assignee, NOW);
        UUID dueAtTheEndOfTheLeadTime = insertTask(assignee, NOW.plus(LEAD_TIME));
        UUID dueAfterTheLeadTime = insertTask(assignee, NOW.plus(LEAD_TIME).plusSeconds(1));

        List<UUID> dueSoon = taskIds(dueSoonAt(NOW));
        List<UUID> overdue = taskIds(overdueAt(NOW));

        assertThat(dueSoon).contains(dueAtTheEndOfTheLeadTime).doesNotContain(dueNow, dueAfterTheLeadTime);
        assertThat(overdue).contains(dueNow).doesNotContain(dueAtTheEndOfTheLeadTime, dueAfterTheLeadTime);
    }

    @Test
    void overdueWindowEndsWithTheLookback() {
        UUID assignee = insertProfile();
        UUID dueAtTheLookbackStart = insertTask(assignee, NOW.minus(LOOKBACK));
        UUID dueJustAfter = insertTask(assignee, NOW.minus(LOOKBACK).plusSeconds(1));

        assertThat(taskIds(overdueAt(NOW))).contains(dueJustAfter).doesNotContain(dueAtTheLookbackStart);
    }

    @Test
    void assigneeIsRemindedOfADueDateOnlyOnce() {
        UUID task = insertTask(insertProfile(), NOW.plus(Duration.ofHours(1)));

        assertThat(taskIds(dueSoonAt(NOW))).contains(task);
        assertThat(taskIds(dueSoonAt(NOW.plus(Duration.ofMinutes(15))))).doesNotContain(task);
        assertThat(remindersOf(task)).isOne();
    }

    @Test
    void movingTheDueDateMakesANewReminderDue() {
        UUID task = insertTask(insertProfile(), NOW.plus(Duration.ofHours(1)));
        dueSoonAt(NOW);

        Instant postponed = NOW.plus(Duration.ofHours(5));
        jdbc.update("UPDATE tasks SET due_at = ? WHERE id = ?", Timestamp.from(postponed), task);

        assertThat(dueSoonAt(NOW.plus(Duration.ofMinutes(15))))
                .filteredOn(reminder -> reminder.taskId().equals(task))
                .extracting(Reminder::dueAt)
                .containsExactly(postponed);
        assertThat(remindersOf(task)).isEqualTo(2);
    }

    @Test
    void reassigningTheTaskMakesANewReminderDueForTheNewAssignee() {
        UUID task = insertTask(insertProfile(), NOW.plus(Duration.ofHours(1)));
        dueSoonAt(NOW);

        UUID newAssignee = insertProfile();
        jdbc.update("UPDATE tasks SET assigned_to_id = ? WHERE id = ?", newAssignee, task);

        assertThat(dueSoonAt(NOW.plus(Duration.ofMinutes(15))))
                .filteredOn(reminder -> reminder.taskId().equals(task))
                .extracting(Reminder::recipientId)
                .containsExactly(newAssignee);
    }

    @Test
    void taskRemindedAsDueSoonIsRemindedAgainOnceOverdue() {
        Instant dueAt = NOW.plus(Duration.ofHours(1));
        UUID task = insertTask(insertProfile(), dueAt);
        dueSoonAt(NOW);

        assertThat(taskIds(overdueAt(dueAt.plus(Duration.ofMinutes(15))))).contains(task);
        assertThat(jdbc.queryForList("SELECT kind FROM task_reminders WHERE task_id = ?", String.class, task))
                .containsExactlyInAnyOrder("DUE_SOON", "OVERDUE");
    }

    @ParameterizedTest
    @EnumSource(value = TaskStatus.class, names = {"DONE", "CANCELLED", "ARCHIVED"})
    void closedTaskIsNotReminded(TaskStatus status) {
        UUID task = insertTask(insertProfile(), NOW.plus(Duration.ofHours(1)));
        jdbc.update("UPDATE tasks SET status = ? WHERE id = ?", status.name(), task);

        assertThat(taskIds(dueSoonAt(NOW))).doesNotContain(task);
    }

    @ParameterizedTest
    @EnumSource(value = TaskStatus.class, names = {"DONE", "CANCELLED", "ARCHIVED"}, mode = EnumSource.Mode.EXCLUDE)
    void openTaskIsReminded(TaskStatus status) {
        UUID task = insertTask(insertProfile(), NOW.plus(Duration.ofHours(1)));
        jdbc.update("UPDATE tasks SET status = ? WHERE id = ?", status.name(), task);

        assertThat(taskIds(dueSoonAt(NOW))).contains(task);
    }

    @Test
    void archivedDeletedAndUnassignedTasksAreNotReminded() {
        Instant dueAt = NOW.plus(Duration.ofHours(1));
        UUID archived = insertTask(insertProfile(), dueAt);
        jdbc.update("UPDATE tasks SET archived_at = ? WHERE id = ?", Timestamp.from(NOW), archived);
        UUID deleted = insertTask(insertProfile(), dueAt);
        jdbc.update("UPDATE tasks SET deleted_at = ? WHERE id = ?", Timestamp.from(NOW), deleted);
        UUID unassigned = insertTask(null, dueAt);

        assertThat(taskIds(dueSoonAt(NOW))).doesNotContain(archived, deleted, unassigned);
    }

    @Test
    void remindersComeByDueDateThenReference() {
        UUID assignee = insertProfile();
        String suffix = UUID.randomUUID().toString().substring(0, 8);
        UUID laterB = insertTask(assignee, NOW.plus(Duration.ofHours(2)), "ORD-B-" + suffix);
        UUID earlier = insertTask(assignee, NOW.plus(Duration.ofHours(1)), "ORD-Z-" + suffix);
        UUID laterA = insertTask(assignee, NOW.plus(Duration.ofHours(2)), "ORD-A-" + suffix);
        List<UUID> mine = List.of(laterB, earlier, laterA);

        assertThat(taskIds(dueSoonAt(NOW)))
                .filteredOn(mine::contains)
                .containsExactly(earlier, laterA, laterB);
    }

    @Test
    void lockIsHeldByOneTransactionAtATimeAndReleasedWhenItEnds() {
        assertThat(inNewTransaction(transactionManager, queries::tryLock)).isTrue();
        assertThat(queries.tryLock()).isTrue();
        assertThat(inNewTransaction(transactionManager, queries::tryLock)).isFalse();
    }

    @Test
    void lockDoesNotBlockTheMediaCleanupOrTheUserRetention() {
        // The reminders run every 15 minutes, so also at 03:30 and 04:00 when the media cleanup and the retention
        // start: with a shared key, one of them would skip its run
        assertThat(queries.tryLock()).isTrue();

        assertThat(inNewTransaction(transactionManager, mediaCleanupQueries::tryLock)).isTrue();
        assertThat(inNewTransaction(transactionManager, userRetentionQueries::tryLock)).isTrue();
    }

    private List<Reminder> dueSoonAt(Instant now) {
        return queries.recordReminders(DUE_SOON, now, now.plus(LEAD_TIME), now);
    }

    private List<Reminder> overdueAt(Instant now) {
        return queries.recordReminders(OVERDUE, now.minus(LOOKBACK), now, now);
    }

    private UUID insertProfile() {
        return jdbc.queryForObject(
                """
                        INSERT INTO user_profiles (display_name, status, created_at, updated_at)
                        VALUES ('Assignee', 'ACTIVE', ?, ?)
                        RETURNING id
                        """,
                UUID.class,
                Timestamp.from(NOW), Timestamp.from(NOW)
        );
    }

    private UUID insertTask(UUID assignee, Instant dueAt) {
        return insertTask(assignee, dueAt, "REM-" + UUID.randomUUID().toString().substring(0, 18));
    }

    private UUID insertTask(UUID assignee, Instant dueAt, String reference) {
        return jdbc.queryForObject(
                """
                        INSERT INTO tasks (created_at, updated_at, priority, reference, status, title, version,
                                           assigned_to_id, due_at)
                        VALUES (?, ?, 'LOW', ?, 'TO_DO', 'Reminded task', 0, ?, ?)
                        RETURNING id
                        """,
                UUID.class,
                Timestamp.from(NOW), Timestamp.from(NOW), reference, assignee, Timestamp.from(dueAt)
        );
    }

    private String referenceOf(UUID task) {
        return jdbc.queryForObject("SELECT reference FROM tasks WHERE id = ?", String.class, task);
    }

    private int remindersOf(UUID task) {
        return jdbc.queryForObject("SELECT count(*) FROM task_reminders WHERE task_id = ?", Integer.class, task);
    }

    private static List<UUID> taskIds(List<Reminder> reminders) {
        return reminders.stream().map(Reminder::taskId).toList();
    }
}
