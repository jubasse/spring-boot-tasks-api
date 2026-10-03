package io.julienmetral.tasks.export.services;

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
class DataExportRecoveryJobTest {

    @Mock
    private DataExportService exportService;

    @InjectMocks
    private DataExportRecoveryJob job;

    @Test
    void logsHowManyInterruptedExportsWereQueuedAgain(CapturedOutput output) {
        when(exportService.requeueInterrupted()).thenReturn(3);

        job.requeueInterrupted();

        verify(exportService).requeueInterrupted();
        assertThat(output).contains("Interrupted exports queued again: 3");
    }

    @Test
    void runWithoutInterruptedExportLogsNothing(CapturedOutput output) {
        job.requeueInterrupted();

        assertThat(output).doesNotContain("Interrupted exports queued again");
    }
}
