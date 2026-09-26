package io.julienmetral.tasks.task.services;

import io.julienmetral.tasks.identity.entities.User;
import io.julienmetral.tasks.task.entities.TaskReminderKind;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * End-to-end tests of the reminder emails: the run is called directly, and the emails sent after its commit are read
 * back from the Mailpit container.
 */
class TaskReminderEmailTests extends AbstractTaskReminderTests {

    @Autowired
    private TransactionTemplate transactionTemplate;

    @Test
    void dueSoonReminderEmailsTheAssigneeWithReferenceTitleAndUtcDueDate() throws Exception {
        User assignee = createAssignee();
        CreatedTask task = createTask(createAdmin(), assignee, NOW.plus(Duration.ofHours(12).plusMinutes(30)));

        reminderService.sendDueReminders();

        String text = awaitTextWithSubject(assignee, dueSoonSubject(task));

        assertThat(text)
                .contains("Hello " + assignee.getDisplayName() + ",")
                .contains("The task " + task.reference() + ": \"" + TITLE + "\" is due on 2100-01-01 12:30 UTC.")
                .contains("notification settings");
    }

    @Test
    void overdueReminderEmailsTheAssigneeWithReferenceTitleAndUtcDueDate() throws Exception {
        User assignee = createAssignee();
        CreatedTask task = createTask(createAdmin(), assignee, NOW.minus(Duration.ofDays(2).plusHours(3)));

        reminderService.sendDueReminders();

        String text = awaitTextWithSubject(assignee, overdueSubject(task));

        assertThat(text)
                .contains("Hello " + assignee.getDisplayName() + ",")
                .contains("The task " + task.reference() + ": \"" + TITLE
                        + "\" was due on 2099-12-29 21:00 UTC and is not done yet.")
                .contains("notification settings");
    }

    @Test
    void secondRunDoesNotEmailTheAssigneeAgain() throws Exception {
        User assignee = createAssignee();
        CreatedTask task = createTask(createAdmin(), assignee, NOW.plus(Duration.ofHours(2)));
        reminderService.sendDueReminders();
        awaitTextWithSubject(assignee, dueSoonSubject(task));

        reminderService.sendDueReminders();

        waitForAsyncDispatch();
        assertThat(countWithSubject(assignee, dueSoonSubject(task))).isEqualTo(1);
    }

    @Test
    void adminAssignedToTheirOwnTaskIsStillReminded() throws Exception {
        User admin = createAdmin();
        CreatedTask task = createTask(admin, admin, NOW.plus(Duration.ofHours(2)));

        reminderService.sendDueReminders();

        awaitTextWithSubject(admin, dueSoonSubject(task));
    }

    @Test
    void reassignedTaskEmailsTheReminderToTheNewAssigneeOnly() throws Exception {
        User admin = createAdmin();
        User previous = createAssignee();
        User next = createAssignee();
        CreatedTask task = createTask(admin, previous, NOW.plus(Duration.ofHours(2)));
        assign(admin, task, next);

        reminderService.sendDueReminders();

        awaitTextWithSubject(next, dueSoonSubject(task));
        waitForAsyncDispatch();
        assertThat(countWithSubject(previous, dueSoonSubject(task))).isZero();
    }

    @Test
    void disabledAssigneeIsNotEmailedAndIsNotRemindedOnceEnabledAgain() throws Exception {
        User assignee = createAssignee();
        CreatedTask task = createTask(createAdmin(), assignee, NOW.plus(Duration.ofHours(2)));
        disable(assignee);

        reminderService.sendDueReminders();

        assertThat(remindersOf(task)).extracting(ReminderRow::kind).containsExactly(TaskReminderKind.DUE_SOON);
        enable(assignee);
        reminderService.sendDueReminders();

        waitForAsyncDispatch();
        assertThat(countWithSubject(assignee, dueSoonSubject(task))).isZero();
    }

    @Test
    void unverifiedAssigneeIsNotEmailedYetTheReminderIsRecorded() throws Exception {
        User assignee = createAssignee();
        CreatedTask task = createTask(createAdmin(), assignee, NOW.minus(Duration.ofHours(2)));
        unverify(assignee);

        reminderService.sendDueReminders();

        assertThat(remindersOf(task)).extracting(ReminderRow::kind).containsExactly(TaskReminderKind.OVERDUE);
        waitForAsyncDispatch();
        assertThat(countWithSubject(assignee, overdueSubject(task))).isZero();
    }

    @Test
    void deletedAssigneeIsNotEmailedYetTheReminderIsRecorded() throws Exception {
        User admin = createAdmin();
        User assignee = createAssignee();
        CreatedTask task = createTask(admin, assignee, NOW.plus(Duration.ofHours(2)));
        deleteUser(admin, assignee);

        reminderService.sendDueReminders();

        assertThat(remindersOf(task)).extracting(ReminderRow::recipientId).containsExactly(assignee.getId());
        waitForAsyncDispatch();
        assertThat(countWithSubject(assignee, dueSoonSubject(task))).isZero();
    }

    @Test
    void assigneeWithDueSoonOffIsNotEmailedAndIsNotRemindedOnceTurnedBackOn() throws Exception {
        User assignee = createAssignee();
        updateReminderSettings(assignee, false, true);
        CreatedTask task = createTask(createAdmin(), assignee, NOW.plus(Duration.ofHours(2)));

        reminderService.sendDueReminders();

        assertThat(remindersOf(task)).extracting(ReminderRow::kind).containsExactly(TaskReminderKind.DUE_SOON);
        updateReminderSettings(assignee, true, true);
        reminderService.sendDueReminders();

        waitForAsyncDispatch();
        assertThat(countWithSubject(assignee, dueSoonSubject(task))).isZero();
    }

    @Test
    void assigneeWithOverdueOffIsNotEmailedYetTheReminderIsRecorded() throws Exception {
        User assignee = createAssignee();
        updateReminderSettings(assignee, true, false);
        CreatedTask task = createTask(createAdmin(), assignee, NOW.minus(Duration.ofHours(2)));

        reminderService.sendDueReminders();

        assertThat(remindersOf(task)).extracting(ReminderRow::kind).containsExactly(TaskReminderKind.OVERDUE);
        waitForAsyncDispatch();
        assertThat(countWithSubject(assignee, overdueSubject(task))).isZero();
    }

    @Test
    void reminderSwitchesAreIndependent() throws Exception {
        User admin = createAdmin();
        User assignee = createAssignee();
        updateReminderSettings(assignee, true, false);
        CreatedTask dueSoon = createTask(admin, assignee, NOW.plus(Duration.ofHours(2)));
        CreatedTask overdue = createTask(admin, assignee, NOW.minus(Duration.ofHours(2)));

        reminderService.sendDueReminders();

        awaitTextWithSubject(assignee, dueSoonSubject(dueSoon));
        waitForAsyncDispatch();
        assertThat(countWithSubject(assignee, overdueSubject(overdue))).isZero();
    }

    @Test
    void rolledBackRunEmailsNothingAndTheNextRunDoes() throws Exception {
        User assignee = createAssignee();
        CreatedTask task = createTask(createAdmin(), assignee, NOW.plus(Duration.ofHours(2)));

        transactionTemplate.executeWithoutResult(status -> {
            reminderService.sendDueReminders();
            status.setRollbackOnly();
        });

        waitForAsyncDispatch();
        assertThat(countWithSubject(assignee, dueSoonSubject(task))).isZero();

        reminderService.sendDueReminders();

        awaitTextWithSubject(assignee, dueSoonSubject(task));
    }

    @Test
    void concurrentRunsEmailTheAssigneeOnce() throws Exception {
        User assignee = createAssignee();
        CreatedTask task = createTask(createAdmin(), assignee, NOW.plus(Duration.ofHours(2)));
        CyclicBarrier start = new CyclicBarrier(2);

        try (ExecutorService executor = Executors.newFixedThreadPool(2)) {
            CompletableFuture<?> first = CompletableFuture.runAsync(() -> runAfter(start), executor);
            CompletableFuture<?> second = CompletableFuture.runAsync(() -> runAfter(start), executor);
            CompletableFuture.allOf(first, second).get(30, TimeUnit.SECONDS);
        }

        awaitTextWithSubject(assignee, dueSoonSubject(task));
        waitForAsyncDispatch();
        assertThat(countWithSubject(assignee, dueSoonSubject(task))).isEqualTo(1);
    }

    @Test
    void dueDateIsShownInUtcWhateverTheMinutesAndSeconds() throws Exception {
        User assignee = createAssignee();
        Instant dueAt = Instant.parse("2100-01-01T07:05:59Z");
        CreatedTask task = createTask(createAdmin(), assignee, dueAt);

        reminderService.sendDueReminders();

        assertThat(awaitTextWithSubject(assignee, dueSoonSubject(task))).contains("is due on 2100-01-01 07:05 UTC.");
    }

    private void runAfter(CyclicBarrier start) {
        try {
            start.await(10, TimeUnit.SECONDS);
        } catch (Exception exception) {
            throw new IllegalStateException(exception);
        }
        reminderService.sendDueReminders();
    }
}
