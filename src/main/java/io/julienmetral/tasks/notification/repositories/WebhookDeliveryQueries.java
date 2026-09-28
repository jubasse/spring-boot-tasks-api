package io.julienmetral.tasks.notification.repositories;

import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

@Repository
@RequiredArgsConstructor
public class WebhookDeliveryQueries {

    private final NamedParameterJdbcTemplate jdbc;

    /**
     * Takes up to {@code limit} pending deliveries due at {@code now} and moves them to {@code leaseEnd}, so another
     * instance or the next poll does not take them again while their attempt runs. {@code SKIP LOCKED} lets
     * several instances poll at once without waiting for each other.
     *
     * @return the ids of the deliveries taken
     */
    public List<UUID> claimDue(Instant now, Instant leaseEnd, int limit) {
        return jdbc.queryForList(
                """
                        UPDATE webhook_deliveries
                        SET next_attempt_at = :leaseEnd
                        WHERE id IN (
                            SELECT id FROM webhook_deliveries
                            WHERE status = 'PENDING' AND next_attempt_at <= :now
                            ORDER BY next_attempt_at
                            LIMIT :limit
                            FOR UPDATE SKIP LOCKED
                        )
                        RETURNING id
                        """,
                new MapSqlParameterSource()
                        .addValue("now", Timestamp.from(now))
                        .addValue("leaseEnd", Timestamp.from(leaseEnd))
                        .addValue("limit", limit),
                UUID.class
        );
    }
}
