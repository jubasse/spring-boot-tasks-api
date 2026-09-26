package io.julienmetral.tasks.task.services;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith({MockitoExtension.class, OutputCaptureExtension.class})
class TaskReminderJobTest {

    @Mock
    private TaskReminderService reminderService;

    @InjectMocks
    private TaskReminderJob job;

    @Test
    void logsTheCountsOfARunThatRecordedReminders(CapturedOutput output) {
        when(reminderService.sendDueReminders()).thenReturn(new TaskReminderReport(false, 2, 3));

        job.run();

        verify(reminderService).sendDueReminders();
        assertThat(output).contains("Task reminders: 2 due soon, 3 overdue");
        assertThat(output).doesNotContain("Task reminders skipped");
    }

    @Test
    void logsASkippedRunWithoutCounts(CapturedOutput output) {
        when(reminderService.sendDueReminders()).thenReturn(TaskReminderReport.skippedRun());

        job.run();

        verify(reminderService).sendDueReminders();
        assertThat(output).contains("Task reminders skipped: another instance is sending them");
        assertThat(output).doesNotContain("due soon,");
    }

    @Test
    void logsARunThatRecordedOnlyOverdueReminders(CapturedOutput output) {
        when(reminderService.sendDueReminders()).thenReturn(new TaskReminderReport(false, 0, 1));

        job.run();

        assertThat(output).contains("Task reminders: 0 due soon, 1 overdue");
    }

    @Test
    void logsNothingWhenNoReminderWasDue(CapturedOutput output) {
        when(reminderService.sendDueReminders()).thenReturn(new TaskReminderReport(false, 0, 0));

        job.run();

        verify(reminderService).sendDueReminders();
        assertThat(output).doesNotContain("Task reminders");
    }
}
