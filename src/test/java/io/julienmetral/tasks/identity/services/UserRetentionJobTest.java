package io.julienmetral.tasks.identity.services;

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
class UserRetentionJobTest {

    @Mock
    private UserRetentionService retentionService;

    @InjectMocks
    private UserRetentionJob job;

    @Test
    void logsTheCountsOfACompletedRun(CapturedOutput output) {
        when(retentionService.apply()).thenReturn(new UserRetentionReport(2, 3, 4));

        job.run();

        verify(retentionService).apply();
        assertThat(output).contains(
                "User retention: 2 deleted users anonymized, 3 inactive accounts warned, 4 deleted");
    }
}
