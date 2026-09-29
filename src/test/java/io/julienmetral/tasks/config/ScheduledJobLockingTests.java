package io.julienmetral.tasks.config;

import io.julienmetral.tasks.ratelimit.services.RateLimitCleanupJob;
import io.julienmetral.tasks.support.IntegrationTest;
import io.micrometer.core.instrument.MeterRegistry;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static io.julienmetral.tasks.config.ScheduledJobLocks.RATE_LIMIT_PURGE;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * The row of the rate-limit purge in {@code scheduler_locks} is shared with every other test context, whose own purge
 * skips while this class holds it.
 */
@IntegrationTest
class ScheduledJobLockingTests {

    // Older than the day a purge keeps, and long before any window the other test contexts count
    private static final Instant WINDOW_OF_2000 = Instant.parse("2000-06-01T12:00:00Z");

    @Autowired
    private RateLimitCleanupJob rateLimitCleanupJob;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private MeterRegistry meterRegistry;

    private final List<String> counterKeys = new ArrayList<>();

    // Released, never deleted: the application's lock provider remembers the rows it inserted and then only updates
    // them, so every later purge of this context would be skipped without its row
    @AfterEach
    void releaseTheRateLimitPurgeLockAndDeleteTheCounters() {
        release(RATE_LIMIT_PURGE);
        counterKeys.forEach(key -> jdbc.update("DELETE FROM rate_limit_counters WHERE bucket_key = ?", key));
    }

    @Test
    void rateLimitPurgeRunsOnlyWhenNoOtherInstanceHoldsItsLock() {
        String counter = insertCounterOf2000();
        holdAsAnotherInstance(RATE_LIMIT_PURGE);

        rateLimitCleanupJob.deleteOldWindows();

        assertThat(counterExists(counter)).isTrue();

        release(RATE_LIMIT_PURGE);
        rateLimitCleanupJob.deleteOldWindows();

        assertThat(counterExists(counter)).isFalse();
    }

    @Test
    void rateLimitPurgeKeepsItsLockForOneMinuteAfterARun() {
        release(RATE_LIMIT_PURGE);
        rateLimitCleanupJob.deleteOldWindows();
        String counter = insertCounterOf2000();

        rateLimitCleanupJob.deleteOldWindows();

        assertThat(counterExists(counter)).isTrue();
        assertThat(heldFor(RATE_LIMIT_PURGE)).isEqualTo(Duration.ofMinutes(1));
    }

    @Test
    void lockAttemptsOfAJobAreCountedByOutcome() {
        double attempts = rateLimitPurgeCount("shedlock.lock.attempts");
        double acquired = rateLimitPurgeCount("shedlock.lock.acquired");
        double notAcquired = rateLimitPurgeCount("shedlock.lock.not.acquired");

        holdAsAnotherInstance(RATE_LIMIT_PURGE);
        rateLimitCleanupJob.deleteOldWindows();
        release(RATE_LIMIT_PURGE);
        rateLimitCleanupJob.deleteOldWindows();

        assertThat(rateLimitPurgeCount("shedlock.lock.attempts")).isEqualTo(attempts + 2);
        assertThat(rateLimitPurgeCount("shedlock.lock.acquired")).isEqualTo(acquired + 1);
        assertThat(rateLimitPurgeCount("shedlock.lock.not.acquired")).isEqualTo(notAcquired + 1);
    }

    @Test
    void everyJobLockHasItsMetricsFromStartup() {
        assertThat(ScheduledJobLocks.NAMES).allSatisfy(name ->
                assertThat(meterRegistry.find("shedlock.lock.attempts").tag("lock.name", name).counter())
                        .as(name)
                        .isNotNull());
    }

    // Held for 30 seconds at most, in case the test dies before releasing it
    private void holdAsAnotherInstance(String lockName) {
        jdbc.update(
                """
                        INSERT INTO scheduler_locks (name, lock_until, locked_at, locked_by)
                        VALUES (?, timezone('utc', now()) + interval '30 seconds', timezone('utc', now()), ?)
                        ON CONFLICT (name) DO UPDATE
                        SET lock_until = excluded.lock_until,
                            locked_at = excluded.locked_at,
                            locked_by = excluded.locked_by
                        """,
                lockName,
                "another-instance"
        );
    }

    private void release(String lockName) {
        jdbc.update("UPDATE scheduler_locks SET lock_until = timezone('utc', now()) WHERE name = ?", lockName);
    }

    private Duration heldFor(String lockName) {
        return jdbc.queryForObject(
                "SELECT locked_at, lock_until FROM scheduler_locks WHERE name = ?",
                (result, rowNumber) -> Duration.between(
                        result.getObject("locked_at", LocalDateTime.class),
                        result.getObject("lock_until", LocalDateTime.class)
                ),
                lockName
        );
    }

    private String insertCounterOf2000() {
        String key = "test:" + UUID.randomUUID();
        counterKeys.add(key);
        jdbc.update(
                "INSERT INTO rate_limit_counters (bucket_key, window_start, count) VALUES (?, ?, 1)",
                key,
                Timestamp.from(WINDOW_OF_2000)
        );
        return key;
    }

    private boolean counterExists(String key) {
        return Boolean.TRUE.equals(jdbc.queryForObject(
                "SELECT exists (SELECT 1 FROM rate_limit_counters WHERE bucket_key = ?)",
                Boolean.class,
                key
        ));
    }

    private double rateLimitPurgeCount(String meterName) {
        return meterRegistry.get(meterName).tag("lock.name", RATE_LIMIT_PURGE).counter().count();
    }
}
