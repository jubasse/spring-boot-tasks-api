package io.julienmetral.tasks.ratelimit.services;

import io.julienmetral.tasks.ratelimit.repositories.RateLimitQueries;
import lombok.RequiredArgsConstructor;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Duration;

@Component
@RequiredArgsConstructor
public class RateLimitCleanupJob {

    // Longer than any configured window, so a window still counting is never deleted
    private static final Duration KEEP = Duration.ofDays(1);

    private final RateLimitQueries queries;
    private final Clock clock;

    @Scheduled(cron = "${rate-limit.purge-cron}")
    public void deleteOldWindows() {
        queries.deleteWindowsStartedBefore(clock.instant().minus(KEEP));
    }
}
