package io.julienmetral.tasks.export.repositories;

import io.julienmetral.tasks.support.JdbcSliceTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@JdbcSliceTest
class BatchMetadataQueriesTests {

    // Every execution sits in 2000, before anything the jobs of the other test contexts record in the shared
    // database: the cutoffs below reach only this class's rows
    private static final Instant CUTOFF = Instant.parse("2000-06-01T00:00:00Z");

    private static final Instant LONG_BEFORE = CUTOFF.minus(Duration.ofDays(60));

    @Autowired
    private BatchMetadataQueries queries;

    @Autowired
    private JdbcTemplate jdbc;

    @Test
    void executionEndedBeforeTheCutoffIsDeletedWithItsStepsContextsAndParameters() {
        long instance = insertInstance();
        long execution = insertExecution(instance, CUTOFF.minus(Duration.ofDays(1)));

        queries.deleteExecutionsEndedBefore(CUTOFF);

        assertThat(rowsOf(execution)).isZero();
        assertThat(count("SELECT count(*) FROM batch_job_instance WHERE job_instance_id = ?", instance)).isZero();
    }

    @Test
    void returnsHowManyExecutionsItDeleted() {
        long instance = insertInstance();
        insertExecution(instance, CUTOFF.minus(Duration.ofDays(2)));
        insertExecution(instance, CUTOFF.minus(Duration.ofDays(1)));

        assertThat(queries.deleteExecutionsEndedBefore(CUTOFF)).isEqualTo(2);
    }

    @Test
    void executionEndedAtTheCutoffOrLaterStaysWithItsInstance() {
        long instance = insertInstance();
        long atTheCutoff = insertExecution(instance, CUTOFF);
        long later = insertExecution(instance, CUTOFF.plus(Duration.ofDays(1)));

        queries.deleteExecutionsEndedBefore(CUTOFF);

        assertThat(rowsOf(atTheCutoff)).isEqualTo(5);
        assertThat(rowsOf(later)).isEqualTo(5);
        assertThat(count("SELECT count(*) FROM batch_job_instance WHERE job_instance_id = ?", instance)).isOne();
    }

    @Test
    void executionThatNeverEndedStays() {
        long instance = insertInstance();
        long running = insertExecution(instance, null);

        queries.deleteExecutionsEndedBefore(CUTOFF);

        assertThat(rowsOf(running)).isEqualTo(5);
    }

    @Test
    void instanceStaysWhileOneOfItsExecutionsStays() {
        long instance = insertInstance();
        long old = insertExecution(instance, CUTOFF.minus(Duration.ofDays(1)));
        long restarted = insertExecution(instance, CUTOFF.plus(Duration.ofDays(1)));

        queries.deleteExecutionsEndedBefore(CUTOFF);

        assertThat(rowsOf(old)).isZero();
        assertThat(rowsOf(restarted)).isEqualTo(5);
        assertThat(count("SELECT count(*) FROM batch_job_instance WHERE job_instance_id = ?", instance)).isOne();
    }

    // Batch commits a new instance before its first execution: a job starting during the purge has one without any
    @Test
    void instanceWithoutExecutionThatThePurgeDidNotEmptyStays() {
        long starting = insertInstance();
        insertExecution(insertInstance(), CUTOFF.minus(Duration.ofDays(1)));

        queries.deleteExecutionsEndedBefore(CUTOFF);

        assertThat(count("SELECT count(*) FROM batch_job_instance WHERE job_instance_id = ?", starting)).isOne();
    }

    @Test
    void purgeWithNothingToDeleteLeavesEveryInstance() {
        long starting = insertInstance();
        long instance = insertInstance();
        long execution = insertExecution(instance, CUTOFF.minus(Duration.ofDays(1)));

        assertThat(queries.deleteExecutionsEndedBefore(LONG_BEFORE)).isZero();

        assertThat(rowsOf(execution)).isEqualTo(5);
        assertThat(count("SELECT count(*) FROM batch_job_instance WHERE job_instance_id IN (?, ?)", starting, instance))
                .isEqualTo(2);
    }

    // Batch writes LocalDateTime.now() into columns without time zone: the cutoff must be read in the same zone, or
    // the history of a JVM east or west of UTC would be kept or deleted hours off
    @Test
    void endTimesAreComparedInTheJvmTimeZoneBatchWritesThemIn() {
        long instance = insertInstance();
        long endedJustBefore = insertExecution(instance, CUTOFF.minus(Duration.ofMinutes(1)));
        long endedJustAfter = insertExecution(instance, CUTOFF.plus(Duration.ofMinutes(1)));

        queries.deleteExecutionsEndedBefore(CUTOFF);

        assertThat(rowsOf(endedJustBefore)).isZero();
        assertThat(rowsOf(endedJustAfter)).isEqualTo(5);
    }

    private long insertInstance() {
        return jdbc.queryForObject(
                """
                        INSERT INTO batch_job_instance (job_instance_id, version, job_name, job_key)
                        VALUES (nextval('batch_job_instance_seq'), 0, 'batchMetadataQueriesTests', ?)
                        RETURNING job_instance_id
                        """,
                Long.class,
                UUID.randomUUID().toString().replace("-", "")
        );
    }

    /** An execution as Batch records it: its parameter, its context and one step with its context. */
    private long insertExecution(long instance, Instant endedAt) {
        Timestamp created = batchTime(LONG_BEFORE);
        Long execution = jdbc.queryForObject(
                """
                        INSERT INTO batch_job_execution (job_execution_id, version, job_instance_id, create_time,
                                                         start_time, end_time, status, exit_code, exit_message,
                                                         last_updated)
                        VALUES (nextval('batch_job_execution_seq'), 2, ?, ?, ?, ?, ?, ?, '', ?)
                        RETURNING job_execution_id
                        """,
                Long.class,
                instance, created, created, batchTime(endedAt),
                endedAt == null ? "STARTED" : "COMPLETED", endedAt == null ? "UNKNOWN" : "COMPLETED", created
        );
        jdbc.update(
                """
                        INSERT INTO batch_job_execution_params (job_execution_id, parameter_name, parameter_type,
                                                                parameter_value, identifying)
                        VALUES (?, 'exportId', 'java.lang.String', ?, 'Y')
                        """,
                execution, UUID.randomUUID().toString()
        );
        jdbc.update(
                "INSERT INTO batch_job_execution_context (job_execution_id, short_context) VALUES (?, '{}')",
                execution
        );
        Long step = jdbc.queryForObject(
                """
                        INSERT INTO batch_step_execution (step_execution_id, version, step_name, job_execution_id,
                                                          create_time, start_time, end_time, status, write_count)
                        VALUES (nextval('batch_step_execution_seq'), 3, 'tasksCsvWrite', ?, ?, ?, ?, 'COMPLETED', 1)
                        RETURNING step_execution_id
                        """,
                Long.class,
                execution, created, created, batchTime(endedAt)
        );
        jdbc.update(
                "INSERT INTO batch_step_execution_context (step_execution_id, short_context) VALUES (?, '{}')",
                step
        );
        return execution;
    }

    /** The rows an execution left in Batch's tables, its instance aside: 5 as inserted, 0 once deleted. */
    private int rowsOf(long execution) {
        return count("SELECT count(*) FROM batch_job_execution WHERE job_execution_id = ?", execution)
                + count("SELECT count(*) FROM batch_job_execution_params WHERE job_execution_id = ?", execution)
                + count("SELECT count(*) FROM batch_job_execution_context WHERE job_execution_id = ?", execution)
                + count("SELECT count(*) FROM batch_step_execution WHERE job_execution_id = ?", execution)
                + count("""
                        SELECT count(*) FROM batch_step_execution_context c
                        JOIN batch_step_execution s ON s.step_execution_id = c.step_execution_id
                        WHERE s.job_execution_id = ?
                        """, execution);
    }

    private int count(String sql, Object... arguments) {
        return jdbc.queryForObject(sql, Integer.class, arguments);
    }

    private static Timestamp batchTime(Instant instant) {
        return instant == null
                ? null
                : Timestamp.valueOf(LocalDateTime.ofInstant(instant, ZoneId.systemDefault()));
    }
}
