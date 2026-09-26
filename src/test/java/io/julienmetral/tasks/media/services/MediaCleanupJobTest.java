package io.julienmetral.tasks.media.services;

import io.julienmetral.tasks.media.model.MediaCleanupReport;
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
class MediaCleanupJobTest {

    @Mock
    private MediaCleanupService cleanupService;

    @InjectMocks
    private MediaCleanupJob job;

    @Test
    void logsTheCountsOfACompletedRun(CapturedOutput output) {
        when(cleanupService.cleanUp()).thenReturn(new MediaCleanupReport(false, 2, 3, 4, 5));

        job.run();

        verify(cleanupService).cleanUp();
        assertThat(output).contains(
                "Media cleanup: 2 attachments and 3 avatars detached, 4 media rows and 5 orphan objects deleted");
        assertThat(output).doesNotContain("Media cleanup skipped");
    }

    @Test
    void logsASkippedRunWithoutCounts(CapturedOutput output) {
        when(cleanupService.cleanUp()).thenReturn(MediaCleanupReport.skippedRun());

        job.run();

        assertThat(output).contains("Media cleanup skipped: another instance is running it");
        assertThat(output).doesNotContain("attachments and");
    }
}
