package io.julienmetral.tasks.export.repositories;

import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.time.Instant;
import java.time.ZoneId;
import java.util.List;

/**
 * Spring Batch keeps every execution forever; nothing in Batch deletes them. Its timestamps have no time zone and are
 * written from the JVM's clock in its zone, hence the cutoff converted the same way.
 */
@Repository
@RequiredArgsConstructor
public class BatchMetadataQueries {

    private static final String ENDED_BEFORE =
            "SELECT job_execution_id FROM batch_job_execution WHERE end_time < :cutoff";

    private final NamedParameterJdbcTemplate jdbc;

    /** @return how many job executions were deleted, with their steps, contexts and parameters */
    public int deleteExecutionsEndedBefore(Instant cutoff) {
        MapSqlParameterSource parameters = new MapSqlParameterSource(
                "cutoff", Timestamp.valueOf(cutoff.atZone(ZoneId.systemDefault()).toLocalDateTime()));
        List<Long> instances = jdbc.queryForList(
                "SELECT DISTINCT job_instance_id FROM batch_job_execution WHERE end_time < :cutoff",
                parameters,
                Long.class);

        jdbc.update("""
                DELETE FROM batch_step_execution_context
                WHERE step_execution_id IN (
                    SELECT step_execution_id FROM batch_step_execution WHERE job_execution_id IN (%s)
                )
                """.formatted(ENDED_BEFORE), parameters);
        jdbc.update("DELETE FROM batch_step_execution WHERE job_execution_id IN (%s)".formatted(ENDED_BEFORE), parameters);
        jdbc.update("DELETE FROM batch_job_execution_context WHERE job_execution_id IN (%s)".formatted(ENDED_BEFORE), parameters);
        jdbc.update("DELETE FROM batch_job_execution_params WHERE job_execution_id IN (%s)".formatted(ENDED_BEFORE), parameters);

        int deleted = jdbc.update("DELETE FROM batch_job_execution WHERE end_time < :cutoff", parameters);

        // Only the instances whose executions went here: Batch creates an instance and its first execution in two
        // transactions, so deleting every instance without executions removed the one of a job starting meanwhile
        if (!instances.isEmpty()) {
            jdbc.update("""
                    DELETE FROM batch_job_instance i
                    WHERE i.job_instance_id IN (:instances)
                      AND NOT EXISTS (SELECT 1 FROM batch_job_execution e WHERE e.job_instance_id = i.job_instance_id)
                    """, new MapSqlParameterSource("instances", instances));
        }

        return deleted;
    }
}
