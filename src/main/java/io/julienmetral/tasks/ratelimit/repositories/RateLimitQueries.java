package io.julienmetral.tasks.ratelimit.repositories;

import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.time.Instant;

@Repository
@RequiredArgsConstructor
public class RateLimitQueries {

    private final NamedParameterJdbcTemplate jdbc;

    /**
     * Counts one request for the key in the window, atomically: concurrent requests, on one instance or several,
     * never read the same count.
     *
     * @return the number of requests counted in the window, this one included
     */
    public int increment(String bucketKey, Instant windowStart) {
        Integer count = jdbc.queryForObject(
                """
                        INSERT INTO rate_limit_counters (bucket_key, window_start, count)
                        VALUES (:key, :windowStart, 1)
                        ON CONFLICT (bucket_key, window_start)
                        DO UPDATE SET count = rate_limit_counters.count + 1
                        RETURNING count
                        """,
                new MapSqlParameterSource()
                        .addValue("key", bucketKey)
                        .addValue("windowStart", Timestamp.from(windowStart)),
                Integer.class
        );

        return count == null ? 0 : count;
    }

    public int deleteWindowsStartedBefore(Instant cutoff) {
        return jdbc.update(
                "DELETE FROM rate_limit_counters WHERE window_start < :cutoff",
                new MapSqlParameterSource("cutoff", Timestamp.from(cutoff))
        );
    }
}
