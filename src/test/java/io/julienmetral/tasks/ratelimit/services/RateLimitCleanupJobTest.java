package io.julienmetral.tasks.ratelimit.services;

import io.julienmetral.tasks.ratelimit.repositories.RateLimitQueries;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;

import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;

@ExtendWith(MockitoExtension.class)
class RateLimitCleanupJobTest {

    @Mock
    private RateLimitQueries queries;

    @Test
    void deletesWindowsThatStartedMoreThanOneDayBeforeTheClock() {
        Clock clock = Clock.fixed(Instant.parse("2026-09-26T10:20:00.250Z"), ZoneOffset.UTC);

        new RateLimitCleanupJob(queries, clock).deleteOldWindows();

        verify(queries).deleteWindowsStartedBefore(Instant.parse("2026-09-25T10:20:00.250Z"));
        verifyNoMoreInteractions(queries);
    }
}
