package io.julienmetral.tasks.task.services;

import io.julienmetral.tasks.identity.entities.User;
import io.julienmetral.tasks.task.entities.TaskReminderKind;
import io.julienmetral.tasks.task.events.TaskDueSoon;
import io.julienmetral.tasks.task.events.TaskOverdue;
import io.julienmetral.tasks.task.repositories.TaskReminderQueries;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.event.ApplicationEvents;
import org.springframework.test.context.event.RecordApplicationEvents;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.support.TransactionTemplate;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;

@RecordApplicationEvents
class TaskReminderTests extends AbstractTaskReminderTests {

    private static final long LOCK_KEY = (long) ReflectionTestUtils.getField(TaskReminderQueries.class, "LOCK_KEY");

    @Autowired
    private ApplicationEvents events;

    @Autowired
    private DataSource dataSource;

    @Autowired
    private TransactionTemplate transactionTemplate;

    @Test
    void taskDueWithinTheLeadTimeIsRemindedDueSoonOnce() throws Exception {
        User assignee = createAssignee();
        CreatedTask task = createTask(createAdmin(), assignee, NOW.plus(Duration.ofHours(12)));

        TaskReminderReport first = reminderService.sendDueReminders();

        assertThat(first.skipped()).isFalse();
        assertThat(first.dueSoon()).isGreaterThanOrEqualTo(1);
        assertThat(dueSoonEventsFor(task)).containsExactly(new TaskDueSoon(
                task.id(), task.reference(), TITLE, NOW.plus(Duration.ofHours(12)), assignee.getId()
        ));
        assertThat(overdueEventsFor(task)).isEmpty();

        TaskReminderReport second = reminderService.sendDueReminders();

        assertThat(second.skipped()).isFalse();
        assertThat(dueSoonEventsFor(task)).hasSize(1);
        assertThat(remindersOf(task)).hasSize(1);
    }

    @Test
    void taskDueExactlyAtTheEndOfTheLeadTimeIsRemindedDueSoon() throws Exception {
        CreatedTask task = createTask(createAdmin(), createAssignee(), dueSoonEnd());

        reminderService.sendDueReminders();

        assertThat(kindsOf(task)).containsExactly(TaskReminderKind.DUE_SOON);
    }

    @Test
    void taskDueJustAfterTheLeadTimeIsNotReminded() throws Exception {
        CreatedTask task = createTask(createAdmin(), createAssignee(), dueSoonEnd().plusSeconds(1));

        reminderService.sendDueReminders();

        assertNeverReminded(task);
    }

    @Test
    void taskDueFarInTheFutureIsNotReminded() throws Exception {
        CreatedTask task = createTask(createAdmin(), createAssignee(), NOW.plus(Duration.ofDays(30)));

        reminderService.sendDueReminders();

        assertNeverReminded(task);
    }

    @Test
    void taskWithoutDueDateIsNotReminded() throws Exception {
        CreatedTask task = createTask(createAdmin(), createAssignee(), null);

        reminderService.sendDueReminders();

        assertNeverReminded(task);
    }

    @Test
    void taskDueExactlyNowIsOverdueAndNotDueSoon() throws Exception {
        CreatedTask task = createTask(createAdmin(), createAssignee(), NOW);

        reminderService.sendDueReminders();

        assertThat(kindsOf(task)).containsExactly(TaskReminderKind.OVERDUE);
        assertThat(dueSoonEventsFor(task)).isEmpty();
        assertThat(overdueEventsFor(task)).hasSize(1);
    }

    @Test
    void taskPastDueWithinTheLookbackIsRemindedOverdueOnce() throws Exception {
        User assignee = createAssignee();
        Instant dueAt = NOW.minus(Duration.ofDays(2));
        CreatedTask task = createTask(createAdmin(), assignee, dueAt);

        TaskReminderReport first = reminderService.sendDueReminders();

        assertThat(first.overdue()).isGreaterThanOrEqualTo(1);
        assertThat(overdueEventsFor(task)).containsExactly(new TaskOverdue(
                task.id(), task.reference(), TITLE, dueAt, assignee.getId()
        ));
        assertThat(dueSoonEventsFor(task)).isEmpty();

        reminderService.sendDueReminders();

        assertThat(overdueEventsFor(task)).hasSize(1);
        assertThat(remindersOf(task)).hasSize(1);
    }

    @Test
    void taskPastDueJustInsideTheLookbackIsRemindedOverdue() throws Exception {
        CreatedTask task = createTask(createAdmin(), createAssignee(), overdueStart().plusSeconds(1));

        reminderService.sendDueReminders();

        assertThat(kindsOf(task)).containsExactly(TaskReminderKind.OVERDUE);
    }

    @Test
    void taskPastDueExactlyAtTheLookbackIsNotReminded() throws Exception {
        CreatedTask task = createTask(createAdmin(), createAssignee(), overdueStart());

        reminderService.sendDueReminders();

        assertNeverReminded(task);
    }

    @Test
    void taskPastDueBeforeTheLookbackIsNotReminded() throws Exception {
        CreatedTask task = createTask(createAdmin(), createAssignee(), overdueStart().minus(Duration.ofDays(1)));

        reminderService.sendDueReminders();

        assertNeverReminded(task);
    }

    @Test
    void taskRemindedDueSoonIsRemindedOverdueOnceItsDueDatePasses() throws Exception {
        Instant dueAt = NOW.plus(Duration.ofHours(3));
        CreatedTask task = createTask(createAdmin(), createAssignee(), dueAt);
        reminderService.sendDueReminders();

        clock.set(dueAt.plus(Duration.ofMinutes(15)));
        reminderService.sendDueReminders();
        reminderService.sendDueReminders();

        assertThat(kindsOf(task)).containsExactly(TaskReminderKind.DUE_SOON, TaskReminderKind.OVERDUE);
        assertThat(dueSoonEventsFor(task)).hasSize(1);
        assertThat(overdueEventsFor(task)).hasSize(1);
    }

    @Test
    void doneTaskIsNotReminded() throws Exception {
        User admin = createAdmin();
        User assignee = createAssignee();
        CreatedTask dueSoon = createTask(admin, assignee, NOW.plus(Duration.ofHours(1)));
        CreatedTask overdue = createTask(admin, assignee, NOW.minus(Duration.ofHours(1)));
        changeStatus(admin, dueSoon, "DONE");
        changeStatus(admin, overdue, "DONE");

        reminderService.sendDueReminders();

        assertNeverReminded(dueSoon);
        assertNeverReminded(overdue);
    }

    @Test
    void cancelledTaskIsNotReminded() throws Exception {
        User admin = createAdmin();
        User assignee = createAssignee();
        CreatedTask dueSoon = createTask(admin, assignee, NOW.plus(Duration.ofHours(1)));
        CreatedTask overdue = createTask(admin, assignee, NOW.minus(Duration.ofHours(1)));
        cancel(admin, dueSoon);
        changeStatus(admin, overdue, "CANCELLED");

        reminderService.sendDueReminders();

        assertNeverReminded(dueSoon);
        assertNeverReminded(overdue);
    }

    @Test
    void archivedTaskIsNotReminded() throws Exception {
        User admin = createAdmin();
        User assignee = createAssignee();
        CreatedTask dueSoon = createTask(admin, assignee, NOW.plus(Duration.ofHours(1)));
        CreatedTask overdue = createTask(admin, assignee, NOW.minus(Duration.ofHours(1)));
        archive(admin, dueSoon);
        archive(admin, overdue);

        reminderService.sendDueReminders();

        assertNeverReminded(dueSoon);
        assertNeverReminded(overdue);
    }

    @Test
    void taskWithArchivedStatusIsNotReminded() throws Exception {
        User admin = createAdmin();
        CreatedTask task = createTask(admin, createAssignee(), NOW.plus(Duration.ofHours(1)));
        changeStatus(admin, task, "ARCHIVED");

        reminderService.sendDueReminders();

        assertNeverReminded(task);
    }

    @Test
    void unarchivedTaskIsReminded() throws Exception {
        User admin = createAdmin();
        CreatedTask task = createTask(admin, createAssignee(), NOW.plus(Duration.ofHours(1)));
        archive(admin, task);
        reminderService.sendDueReminders();

        unarchive(admin, task);
        reminderService.sendDueReminders();

        assertThat(kindsOf(task)).containsExactly(TaskReminderKind.DUE_SOON);
    }

    @Test
    void reopenedTaskIsReminded() throws Exception {
        User admin = createAdmin();
        CreatedTask task = createTask(admin, createAssignee(), NOW.minus(Duration.ofHours(1)));
        changeStatus(admin, task, "DONE");
        reminderService.sendDueReminders();

        changeStatus(admin, task, "IN_PROGRESS");
        reminderService.sendDueReminders();

        assertThat(kindsOf(task)).containsExactly(TaskReminderKind.OVERDUE);
    }

    @Test
    void deletedTaskIsNotReminded() throws Exception {
        User admin = createAdmin();
        User assignee = createAssignee();
        CreatedTask dueSoon = createTask(admin, assignee, NOW.plus(Duration.ofHours(1)));
        CreatedTask overdue = createTask(admin, assignee, NOW.minus(Duration.ofHours(1)));
        deleteTask(admin, dueSoon);
        deleteTask(admin, overdue);

        reminderService.sendDueReminders();

        assertNeverReminded(dueSoon);
        assertNeverReminded(overdue);
    }

    @Test
    void unassignedTaskIsNotRemindedUntilItIsAssigned() throws Exception {
        User admin = createAdmin();
        User assignee = createAssignee();
        CreatedTask task = createTask(admin, null, NOW.plus(Duration.ofHours(1)));

        reminderService.sendDueReminders();

        assertNeverReminded(task);

        assign(admin, task, assignee);
        reminderService.sendDueReminders();

        assertThat(remindersOf(task))
                .extracting(ReminderRow::kind, ReminderRow::recipientId)
                .containsExactly(tuple(TaskReminderKind.DUE_SOON, assignee.getId()));
    }

    @Test
    void movingTheDueDateMakesANewDueSoonReminderDue() throws Exception {
        User admin = createAdmin();
        User assignee = createAssignee();
        Instant firstDueAt = NOW.plus(Duration.ofHours(2));
        Instant postponedDueAt = NOW.plus(Duration.ofHours(20));
        CreatedTask task = createTask(admin, assignee, firstDueAt);
        reminderService.sendDueReminders();

        moveDueDate(admin, task, postponedDueAt);
        reminderService.sendDueReminders();
        reminderService.sendDueReminders();

        assertThat(remindersOf(task))
                .extracting(ReminderRow::kind, ReminderRow::dueAt)
                .containsExactly(
                        tuple(TaskReminderKind.DUE_SOON, firstDueAt),
                        tuple(TaskReminderKind.DUE_SOON, postponedDueAt)
                );
        assertThat(dueSoonEventsFor(task))
                .extracting(TaskDueSoon::dueAt)
                .containsExactly(firstDueAt, postponedDueAt);
    }

    @Test
    void movingTheDueDateOfAnOverdueTaskMakesANewOverdueReminderDue() throws Exception {
        User admin = createAdmin();
        Instant firstDueAt = NOW.minus(Duration.ofDays(3));
        Instant movedDueAt = NOW.minus(Duration.ofDays(1));
        CreatedTask task = createTask(admin, createAssignee(), firstDueAt);
        reminderService.sendDueReminders();

        moveDueDate(admin, task, movedDueAt);
        reminderService.sendDueReminders();

        assertThat(overdueEventsFor(task))
                .extracting(TaskOverdue::dueAt)
                .containsExactly(firstDueAt, movedDueAt);
    }

    @Test
    void savingTheSameDueDateAgainDoesNotRemindAgain() throws Exception {
        User admin = createAdmin();
        Instant dueAt = NOW.plus(Duration.ofHours(2));
        CreatedTask task = createTask(admin, createAssignee(), dueAt);
        reminderService.sendDueReminders();

        moveDueDate(admin, task, dueAt);
        reminderService.sendDueReminders();

        assertThat(remindersOf(task)).hasSize(1);
    }

    @Test
    void reassigningTheTaskRemindsTheNewAssignee() throws Exception {
        User admin = createAdmin();
        User first = createAssignee();
        User next = createAssignee();
        CreatedTask task = createTask(admin, first, NOW.plus(Duration.ofHours(2)));
        reminderService.sendDueReminders();

        assign(admin, task, next);
        reminderService.sendDueReminders();

        assertThat(dueSoonEventsFor(task))
                .extracting(TaskDueSoon::assigneeId)
                .containsExactly(first.getId(), next.getId());
        assertThat(remindersOf(task))
                .extracting(ReminderRow::recipientId)
                .containsExactly(first.getId(), next.getId());
    }

    @Test
    void reassigningBackToAnAlreadyRemindedAssigneeDoesNotRemindThemAgain() throws Exception {
        User admin = createAdmin();
        User first = createAssignee();
        User next = createAssignee();
        CreatedTask task = createTask(admin, first, NOW.plus(Duration.ofHours(2)));
        reminderService.sendDueReminders();
        assign(admin, task, next);
        reminderService.sendDueReminders();

        assign(admin, task, first);
        reminderService.sendDueReminders();

        assertThat(dueSoonEventsFor(task))
                .extracting(TaskDueSoon::assigneeId)
                .containsExactly(first.getId(), next.getId());
    }

    @Test
    void reminderRowRecordsKindDueDateRecipientAndSendingTime() throws Exception {
        User assignee = createAssignee();
        Instant dueAt = NOW.minus(Duration.ofHours(5)).plusMillis(250);
        CreatedTask task = createTask(createAdmin(), assignee, dueAt);

        reminderService.sendDueReminders();

        assertThat(remindersOf(task)).containsExactly(
                new ReminderRow(TaskReminderKind.OVERDUE, dueAt, assignee.getId(), NOW)
        );
    }

    @Test
    void reminderIsRecordedForAnAssigneeWhoIsNoLongerActive() throws Exception {
        User admin = createAdmin();
        User disabled = createAssignee();
        User deleted = createAssignee();
        CreatedTask ofDisabled = createTask(admin, disabled, NOW.plus(Duration.ofHours(1)));
        CreatedTask ofDeleted = createTask(admin, deleted, NOW.plus(Duration.ofHours(1)));
        disable(disabled);
        deleteUser(admin, deleted);

        reminderService.sendDueReminders();

        assertThat(remindersOf(ofDisabled)).extracting(ReminderRow::recipientId).containsExactly(disabled.getId());
        assertThat(remindersOf(ofDeleted)).extracting(ReminderRow::recipientId).containsExactly(deleted.getId());
    }

    @Test
    void runIsSkippedWhileAnotherInstanceHoldsTheLock() throws Exception {
        CreatedTask task = createTask(createAdmin(), createAssignee(), NOW.plus(Duration.ofHours(1)));

        try (Connection otherInstance = dataSource.getConnection()) {
            execute(otherInstance, "select pg_advisory_lock(?)");
            try {
                assertThat(reminderService.sendDueReminders()).isEqualTo(TaskReminderReport.skippedRun());
            } finally {
                execute(otherInstance, "select pg_advisory_unlock(?)");
            }
        }

        assertNeverReminded(task);

        TaskReminderReport afterRelease = reminderService.sendDueReminders();

        assertThat(afterRelease.skipped()).isFalse();
        assertThat(kindsOf(task)).containsExactly(TaskReminderKind.DUE_SOON);
    }

    @Test
    void runIsSkippedWhileAnotherRunIsInProgress() throws Exception {
        CreatedTask task = createTask(createAdmin(), createAssignee(), NOW.plus(Duration.ofHours(1)));
        CountDownLatch firstRunRecorded = new CountDownLatch(1);
        CountDownLatch releaseFirstRun = new CountDownLatch(1);

        try (ExecutorService executor = Executors.newSingleThreadExecutor()) {
            // The first run keeps its transaction, and so the lock, open until the second run is done
            Future<TaskReminderReport> firstRun = executor.submit(() -> transactionTemplate.execute(status -> {
                TaskReminderReport report = reminderService.sendDueReminders();
                firstRunRecorded.countDown();
                await(releaseFirstRun);
                return report;
            }));
            assertThat(firstRunRecorded.await(10, TimeUnit.SECONDS)).isTrue();

            TaskReminderReport secondRun = reminderService.sendDueReminders();
            releaseFirstRun.countDown();

            assertThat(secondRun).isEqualTo(TaskReminderReport.skippedRun());
            assertThat(firstRun.get(10, TimeUnit.SECONDS).skipped()).isFalse();
        }

        assertThat(kindsOf(task)).containsExactly(TaskReminderKind.DUE_SOON);
    }

    @Test
    void concurrentRunsRecordEachReminderOnce() throws Exception {
        CreatedTask task = createTask(createAdmin(), createAssignee(), NOW.plus(Duration.ofHours(1)));
        CyclicBarrier start = new CyclicBarrier(2);

        try (ExecutorService executor = Executors.newFixedThreadPool(2)) {
            List<CompletableFuture<TaskReminderReport>> runs = List.of(
                    CompletableFuture.supplyAsync(() -> runAfter(start), executor),
                    CompletableFuture.supplyAsync(() -> runAfter(start), executor)
            );

            List<TaskReminderReport> reports = runs.stream().map(CompletableFuture::join).toList();

            assertThat(reports).anyMatch(report -> !report.skipped());
        }

        assertThat(kindsOf(task)).containsExactly(TaskReminderKind.DUE_SOON);
    }

    @Test
    void lockIsReleasedWhenTheRunCommits() throws Exception {
        reminderService.sendDueReminders();

        try (Connection otherInstance = dataSource.getConnection()) {
            otherInstance.setAutoCommit(false);
            try (PreparedStatement statement = otherInstance.prepareStatement("select pg_try_advisory_xact_lock(?)")) {
                statement.setLong(1, LOCK_KEY);
                try (var result = statement.executeQuery()) {
                    result.next();
                    assertThat(result.getBoolean(1)).isTrue();
                }
            } finally {
                otherInstance.rollback();
                otherInstance.setAutoCommit(true);
            }
        }
    }

    @Test
    void rolledBackRunRecordsNothingAndTheNextRunReminds() throws Exception {
        CreatedTask task = createTask(createAdmin(), createAssignee(), NOW.plus(Duration.ofHours(1)));

        transactionTemplate.executeWithoutResult(status -> {
            assertThat(reminderService.sendDueReminders().skipped()).isFalse();
            status.setRollbackOnly();
        });

        assertThat(remindersOf(task)).isEmpty();

        reminderService.sendDueReminders();

        assertThat(kindsOf(task)).containsExactly(TaskReminderKind.DUE_SOON);
    }

    private List<TaskDueSoon> dueSoonEventsFor(CreatedTask task) {
        return events.stream(TaskDueSoon.class).filter(event -> event.taskId().equals(task.id())).toList();
    }

    private List<TaskOverdue> overdueEventsFor(CreatedTask task) {
        return events.stream(TaskOverdue.class).filter(event -> event.taskId().equals(task.id())).toList();
    }

    private List<TaskReminderKind> kindsOf(CreatedTask task) {
        return remindersOf(task).stream().map(ReminderRow::kind).toList();
    }

    private void assertNeverReminded(CreatedTask task) {
        assertThat(remindersOf(task)).isEmpty();
        assertThat(dueSoonEventsFor(task)).isEmpty();
        assertThat(overdueEventsFor(task)).isEmpty();
    }

    private TaskReminderReport runAfter(CyclicBarrier start) {
        try {
            start.await(10, TimeUnit.SECONDS);
        } catch (Exception exception) {
            throw new IllegalStateException(exception);
        }
        return reminderService.sendDueReminders();
    }

    private static void await(CountDownLatch latch) {
        try {
            assertThat(latch.await(10, TimeUnit.SECONDS)).isTrue();
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(exception);
        }
    }

    private static void execute(Connection connection, String sql) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setLong(1, LOCK_KEY);
            statement.execute();
        }
    }
}
