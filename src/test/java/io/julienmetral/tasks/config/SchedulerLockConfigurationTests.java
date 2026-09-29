package io.julienmetral.tasks.config;

import com.zaxxer.hikari.HikariDataSource;
import io.julienmetral.tasks.support.JdbcSliceTest;
import net.javacrumbs.shedlock.core.DefaultLockingTaskExecutor;
import net.javacrumbs.shedlock.core.LockConfiguration;
import net.javacrumbs.shedlock.core.LockProvider;
import net.javacrumbs.shedlock.provider.jdbctemplate.JdbcTemplateLockProvider;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.SingleConnectionDataSource;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

@JdbcSliceTest
// Outside the slice's rolled-back transaction: ShedLock commits every lock change in a transaction of its own, as the
// other instance must see it. Each test uses lock names of its own and deletes their rows.
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class SchedulerLockConfigurationTests {

    private static final Duration LOCK_AT_MOST_FOR = Duration.ofMinutes(5);

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private DataSource dataSource;

    private final List<String> lockNames = new ArrayList<>();

    private LockProvider firstInstance;

    private LockProvider secondInstance;

    @BeforeEach
    void startTwoInstances() {
        firstInstance = instance("first-instance");
        secondInstance = instance("second-instance");
    }

    @AfterEach
    void deleteTheLocksOfThisTest() {
        lockNames.forEach(name -> jdbc.update("DELETE FROM scheduler_locks WHERE name = ?", name));
    }

    @Test
    void anotherInstanceCannotTakeAHeldLock() {
        LockConfiguration lock = newLock(Duration.ZERO);

        assertThat(firstInstance.lock(lock)).isPresent();

        assertThat(secondInstance.lock(lock)).isEmpty();
        assertThat(rowOf(lock).lockedBy()).isEqualTo("first-instance");
    }

    @Test
    void unlockWithoutLockAtLeastForFreesTheLockAtOnce() {
        LockConfiguration lock = newLock(Duration.ZERO);
        firstInstance.lock(lock).orElseThrow().unlock();

        assertThat(secondInstance.lock(lock)).isPresent();
        assertThat(rowOf(lock).lockedBy()).isEqualTo("second-instance");
    }

    @Test
    void lockAtLeastForKeepsTheLockHeldAfterUnlock() {
        LockConfiguration lock = newLock(Duration.ofMinutes(1));
        firstInstance.lock(lock).orElseThrow().unlock();

        assertThat(secondInstance.lock(lock)).isEmpty();
        assertThat(rowOf(lock).heldFor()).isEqualTo(Duration.ofMinutes(1));
    }

    @Test
    void lockTimesAreTheDatabaseClockInUtcWhateverTheSessionTimeZone() throws SQLException {
        // Created in 2000: a lock timed by the JVM from its configuration would have expired long ago
        LockConfiguration lock = newLock(Instant.parse("2000-01-01T00:00:00Z"), Duration.ZERO);

        // Outside the pool: a pooled connection would keep this session time zone for the next test
        HikariDataSource pool = dataSource.unwrap(HikariDataSource.class);
        try (Connection connection = DriverManager.getConnection(
                pool.getJdbcUrl(), pool.getUsername(), pool.getPassword())) {
            try (Statement statement = connection.createStatement()) {
                statement.execute("SET TIME ZONE 'Pacific/Kiritimati'");
            }
            JdbcTemplate sessionAtUtcPlus14 = new JdbcTemplate(new SingleConnectionDataSource(connection, true));
            LockProvider instance = new JdbcTemplateLockProvider(SchedulerLockConfiguration
                    .lockProviderConfiguration(sessionAtUtcPlus14)
                    .withLockedByValue("instance-at-utc-plus-14")
                    .build());

            assertThat(instance.lock(lock)).isPresent();
        }

        LockRow row = rowOf(lock);
        assertThat(row.lockedAt()).isCloseTo(row.databaseUtcNow(), within(5, ChronoUnit.SECONDS));
        assertThat(row.heldFor()).isEqualTo(LOCK_AT_MOST_FOR);
    }

    @Test
    void twoInstancesRacingForALockRunTheTaskOnce() throws Exception {
        LockConfiguration lock = newLock(Duration.ZERO);
        CountDownLatch start = new CountDownLatch(1);
        // The winner keeps the lock until the other instance gives up: after a quick unlock, the loser would
        // rightly take the lock and run the task too
        CountDownLatch loserGaveUp = new CountDownLatch(1);

        try (ExecutorService executor = Executors.newFixedThreadPool(2)) {
            List<Future<Boolean>> races = List.of(
                    executor.submit(race(firstInstance, lock, start, loserGaveUp)),
                    executor.submit(race(secondInstance, lock, start, loserGaveUp))
            );
            start.countDown();

            List<Boolean> ranTheTask = new ArrayList<>();
            for (Future<Boolean> race : races) {
                ranTheTask.add(race.get(30, TimeUnit.SECONDS));
            }
            assertThat(ranTheTask).containsExactlyInAnyOrder(true, false);
        }
    }

    private LockProvider instance(String lockedBy) {
        return new JdbcTemplateLockProvider(SchedulerLockConfiguration.lockProviderConfiguration(jdbc)
                .withLockedByValue(lockedBy)
                .build());
    }

    private LockConfiguration newLock(Duration lockAtLeastFor) {
        return newLock(Instant.now(), lockAtLeastFor);
    }

    private LockConfiguration newLock(Instant createdAt, Duration lockAtLeastFor) {
        String name = "test-" + UUID.randomUUID();
        lockNames.add(name);
        return new LockConfiguration(createdAt, name, LOCK_AT_MOST_FOR, lockAtLeastFor);
    }

    private static Callable<Boolean> race(
            LockProvider instance,
            LockConfiguration lock,
            CountDownLatch start,
            CountDownLatch loserGaveUp
    ) {
        return () -> {
            awaitAtMostTenSeconds(start);
            AtomicBoolean ran = new AtomicBoolean();
            Runnable task = () -> {
                ran.set(true);
                awaitAtMostTenSeconds(loserGaveUp);
            };

            new DefaultLockingTaskExecutor(instance).executeWithLock(task, lock);

            if (!ran.get()) {
                loserGaveUp.countDown();
            }
            return ran.get();
        };
    }

    private static void awaitAtMostTenSeconds(CountDownLatch latch) {
        try {
            latch.await(10, TimeUnit.SECONDS);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(exception);
        }
    }

    private LockRow rowOf(LockConfiguration lock) {
        return jdbc.queryForObject(
                """
                        SELECT locked_by, locked_at, lock_until, timezone('utc', clock_timestamp()) AS utc_now
                        FROM scheduler_locks
                        WHERE name = ?
                        """,
                (result, rowNumber) -> new LockRow(
                        result.getString("locked_by"),
                        result.getObject("locked_at", LocalDateTime.class),
                        result.getObject("lock_until", LocalDateTime.class),
                        result.getObject("utc_now", LocalDateTime.class)
                ),
                lock.getName()
        );
    }

    private record LockRow(
            String lockedBy,
            LocalDateTime lockedAt,
            LocalDateTime lockUntil,
            LocalDateTime databaseUtcNow
    ) {

        Duration heldFor() {
            return Duration.between(lockedAt, lockUntil);
        }
    }
}
