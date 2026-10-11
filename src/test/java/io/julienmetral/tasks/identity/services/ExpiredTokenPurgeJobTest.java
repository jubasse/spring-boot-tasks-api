package io.julienmetral.tasks.identity.services;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

import io.julienmetral.tasks.identity.services.ExpiredTokenPurgeService.PurgedTokens;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;

@ExtendWith({MockitoExtension.class, OutputCaptureExtension.class})
class ExpiredTokenPurgeJobTest {

    @Mock private ExpiredTokenPurgeService purgeService;

    @InjectMocks private ExpiredTokenPurgeJob job;

    @Test
    void runThatDeletedTokensLogsTheirCounts(CapturedOutput output) {
        when(purgeService.purge()).thenReturn(new PurgedTokens(3, 2, 1));

        job.purge();

        assertThat(output)
                .contains(
                        "Expired tokens purged: 3 refresh, 2 email verification, 1 password reset");
    }

    @Test
    void runThatDeletedNothingLogsNothing(CapturedOutput output) {
        when(purgeService.purge()).thenReturn(new PurgedTokens(0, 0, 0));

        job.purge();

        assertThat(output).doesNotContain("Expired tokens purged");
    }
}
