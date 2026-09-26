package io.julienmetral.tasks.task.services;

import io.julienmetral.tasks.config.TaskReminderProperties;
import io.julienmetral.tasks.task.entities.TaskReminderKind;
import io.julienmetral.tasks.task.events.TaskDueSoon;
import io.julienmetral.tasks.task.events.TaskOverdue;
import io.julienmetral.tasks.task.repositories.TaskReminderQueries;
import io.julienmetral.tasks.task.repositories.TaskReminderQueries.Reminder;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class TaskReminderServiceTest {

    private static final Instant NOW = Instant.parse("2026-09-26T10:15:00Z");

    private static final Instant DUE_SOON_END = Instant.parse("2026-09-27T10:15:00Z");

    private static final Instant OVERDUE_START = Instant.parse("2026-09-19T10:15:00Z");

    private static final UUID TASK_1 = UUID.fromString("00000000-0000-0000-0000-00000000000a");
    private static final UUID TASK_2 = UUID.fromString("00000000-0000-0000-0000-00000000000b");
    private static final UUID TASK_3 = UUID.fromString("00000000-0000-0000-0000-00000000000c");
    private static final UUID ALICE = UUID.fromString("00000000-0000-0000-0000-000000000001");
    private static final UUID BOB = UUID.fromString("00000000-0000-0000-0000-000000000002");

    @Mock
    private TaskReminderQueries queries;

    @Mock
    private ApplicationEventPublisher eventPublisher;

    private TaskReminderService service;

    @BeforeEach
    void setUp() {
        service = serviceWith(Duration.ofHours(24), Duration.ofDays(7));
    }

    private TaskReminderService serviceWith(Duration dueSoonLeadTime, Duration overdueLookback) {
        TaskReminderProperties properties =
                new TaskReminderProperties(true, "0 */15 * * * *", dueSoonLeadTime, overdueLookback);
        return new TaskReminderService(queries, properties, eventPublisher, Clock.fixed(NOW, ZoneOffset.UTC));
    }

    private void stubReminders(List<Reminder> dueSoon, List<Reminder> overdue) {
        when(queries.tryLock()).thenReturn(true);
        when(queries.recordReminders(TaskReminderKind.DUE_SOON, NOW, DUE_SOON_END, NOW)).thenReturn(dueSoon);
        when(queries.recordReminders(TaskReminderKind.OVERDUE, OVERDUE_START, NOW, NOW)).thenReturn(overdue);
    }

    private List<Object> publishedEvents(int expected) {
        ArgumentCaptor<Object> captor = ArgumentCaptor.forClass(Object.class);
        verify(eventPublisher, times(expected)).publishEvent(captor.capture());
        return captor.getAllValues();
    }

    @Test
    void skipsTheRunWhenAnotherInstanceHoldsTheLock() {
        when(queries.tryLock()).thenReturn(false);

        TaskReminderReport report = service.sendDueReminders();

        assertThat(report).isEqualTo(TaskReminderReport.skippedRun());
        assertThat(report.skipped()).isTrue();
        verify(queries).tryLock();
        verifyNoMoreInteractions(queries);
        verifyNoInteractions(eventPublisher);
    }

    @Test
    void recordsDueSoonAheadOfNowAndOverdueBehindNowAfterTakingTheLock() {
        when(queries.tryLock()).thenReturn(true);
        when(queries.recordReminders(TaskReminderKind.DUE_SOON, NOW, DUE_SOON_END, NOW)).thenReturn(List.of());
        when(queries.recordReminders(TaskReminderKind.OVERDUE, OVERDUE_START, NOW, NOW)).thenReturn(List.of());

        service.sendDueReminders();

        InOrder order = inOrder(queries);
        order.verify(queries).tryLock();
        order.verify(queries).recordReminders(TaskReminderKind.DUE_SOON, NOW, DUE_SOON_END, NOW);
        order.verify(queries).recordReminders(TaskReminderKind.OVERDUE, OVERDUE_START, NOW, NOW);
        verifyNoMoreInteractions(queries);
    }

    @Test
    void windowsFollowTheConfiguredLeadTimeAndLookback() {
        TaskReminderService custom = serviceWith(Duration.ofMinutes(90), Duration.ofDays(2));
        when(queries.tryLock()).thenReturn(true);
        when(queries.recordReminders(TaskReminderKind.DUE_SOON, NOW, Instant.parse("2026-09-26T11:45:00Z"), NOW))
                .thenReturn(List.of());
        when(queries.recordReminders(TaskReminderKind.OVERDUE, Instant.parse("2026-09-24T10:15:00Z"), NOW, NOW))
                .thenReturn(List.of());

        TaskReminderReport report = custom.sendDueReminders();

        assertThat(report).isEqualTo(new TaskReminderReport(false, 0, 0));
    }

    @Test
    void publishesOneDueSoonEventPerRecordedReminder() {
        Instant firstDue = Instant.parse("2026-09-26T18:00:00Z");
        Instant secondDue = Instant.parse("2026-09-27T09:00:00Z");
        stubReminders(
                List.of(
                        new Reminder(TASK_1, "TASK-1", "Write tests", firstDue, ALICE),
                        new Reminder(TASK_2, "TASK-2", "Ship it", secondDue, BOB)
                ),
                List.of()
        );

        service.sendDueReminders();

        assertThat(publishedEvents(2)).containsExactly(
                new TaskDueSoon(TASK_1, "TASK-1", "Write tests", firstDue, ALICE),
                new TaskDueSoon(TASK_2, "TASK-2", "Ship it", secondDue, BOB)
        );
    }

    @Test
    void publishesOneOverdueEventPerRecordedReminder() {
        Instant firstDue = Instant.parse("2026-09-20T08:00:00Z");
        Instant secondDue = Instant.parse("2026-09-26T10:15:00Z");
        stubReminders(
                List.of(),
                List.of(
                        new Reminder(TASK_1, "TASK-1", "Write tests", firstDue, ALICE),
                        new Reminder(TASK_3, "TASK-3", "Review", secondDue, BOB)
                )
        );

        service.sendDueReminders();

        assertThat(publishedEvents(2)).containsExactly(
                new TaskOverdue(TASK_1, "TASK-1", "Write tests", firstDue, ALICE),
                new TaskOverdue(TASK_3, "TASK-3", "Review", secondDue, BOB)
        );
    }

    @Test
    void publishesEachKindAsItsOwnEventAndCountsThemInTheReport() {
        Instant soon = Instant.parse("2026-09-27T08:00:00Z");
        Instant past = Instant.parse("2026-09-25T08:00:00Z");
        stubReminders(
                List.of(new Reminder(TASK_1, "TASK-1", "Write tests", soon, ALICE)),
                List.of(
                        new Reminder(TASK_2, "TASK-2", "Ship it", past, ALICE),
                        new Reminder(TASK_3, "TASK-3", "Review", past, BOB)
                )
        );

        TaskReminderReport report = service.sendDueReminders();

        assertThat(report).isEqualTo(new TaskReminderReport(false, 1, 2));
        assertThat(publishedEvents(3)).containsExactlyInAnyOrder(
                new TaskDueSoon(TASK_1, "TASK-1", "Write tests", soon, ALICE),
                new TaskOverdue(TASK_2, "TASK-2", "Ship it", past, ALICE),
                new TaskOverdue(TASK_3, "TASK-3", "Review", past, BOB)
        );
    }

    @Test
    void publishesNothingWhenNothingIsRecorded() {
        stubReminders(List.of(), List.of());

        TaskReminderReport report = service.sendDueReminders();

        assertThat(report).isEqualTo(new TaskReminderReport(false, 0, 0));
        assertThat(report.skipped()).isFalse();
        verifyNoInteractions(eventPublisher);
    }
}
