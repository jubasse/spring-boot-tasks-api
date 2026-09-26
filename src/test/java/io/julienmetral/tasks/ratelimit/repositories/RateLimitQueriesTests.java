package io.julienmetral.tasks.ratelimit.repositories;

import io.julienmetral.tasks.support.JdbcSliceTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;

@JdbcSliceTest
class RateLimitQueriesTests {

    // Before anything the other test contexts write to the shared database: the purge below reaches only this
    // class's counters
    private static final Instant WINDOW = Instant.parse("2000-06-01T12:00:00Z");

    @Autowired
    private RateLimitQueries queries;

    @Autowired
    private JdbcTemplate jdbc;

    @Test
    void eachRequestOfAWindowIncrementsItsCount() {
        String key = uniqueKey();

        assertThat(queries.increment(key, WINDOW)).isOne();
        assertThat(queries.increment(key, WINDOW)).isEqualTo(2);
        assertThat(queries.increment(key, WINDOW)).isEqualTo(3);
    }

    @Test
    void nextWindowCountsFromOneWithoutResettingThePreviousOne() {
        String key = uniqueKey();
        queries.increment(key, WINDOW);
        queries.increment(key, WINDOW);

        assertThat(queries.increment(key, WINDOW.plus(Duration.ofMinutes(1)))).isOne();
        assertThat(queries.increment(key, WINDOW)).isEqualTo(3);
    }

    @Test
    void eachKeyHasItsOwnCount() {
        String address = uniqueKey();
        String email = uniqueKey();
        queries.increment(address, WINDOW);
        queries.increment(address, WINDOW);

        assertThat(queries.increment(email, WINDOW)).isOne();
    }

    @Test
    // Outside the test transaction: each increment commits on its own connection, as concurrent requests on one or
    // several instances do. The counter is deleted at the end.
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    void concurrentRequestsNeverReadTheSameCount() throws Exception {
        String key = uniqueKey();
        int threads = 8;
        int requestsPerThread = 25;
        List<Integer> counts = new ArrayList<>();

        try (ExecutorService executor = Executors.newFixedThreadPool(threads)) {
            CountDownLatch start = new CountDownLatch(1);
            List<Future<List<Integer>>> results = new ArrayList<>();
            for (int thread = 0; thread < threads; thread++) {
                results.add(executor.submit(() -> {
                    start.await();
                    List<Integer> seen = new ArrayList<>();
                    for (int request = 0; request < requestsPerThread; request++) {
                        seen.add(queries.increment(key, WINDOW));
                    }
                    return seen;
                }));
            }
            start.countDown();
            for (Future<List<Integer>> result : results) {
                counts.addAll(result.get(30, TimeUnit.SECONDS));
            }
        } finally {
            jdbc.update("DELETE FROM rate_limit_counters WHERE bucket_key = ?", key);
        }

        assertThat(counts).containsExactlyInAnyOrderElementsOf(
                IntStream.rangeClosed(1, threads * requestsPerThread).boxed().toList()
        );
    }

    @Test
    void purgeDeletesOnlyTheWindowsStartedBeforeTheCutoff() {
        String key = uniqueKey();
        queries.increment(key, WINDOW.minus(Duration.ofMinutes(1)));
        queries.increment(key, WINDOW);

        queries.deleteWindowsStartedBefore(WINDOW);

        assertThat(jdbc.queryForList(
                "SELECT window_start FROM rate_limit_counters WHERE bucket_key = ?",
                Timestamp.class,
                key
        )).containsExactly(Timestamp.from(WINDOW));
    }

    private static String uniqueKey() {
        return "test:" + UUID.randomUUID();
    }
}
