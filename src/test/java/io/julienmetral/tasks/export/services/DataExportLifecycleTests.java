package io.julienmetral.tasks.export.services;

import io.julienmetral.tasks.config.StorageProperties;
import io.julienmetral.tasks.export.AbstractDataExportTests;
import io.julienmetral.tasks.export.batch.CsvExportJobs;
import io.julienmetral.tasks.export.batch.PublishExport;
import io.julienmetral.tasks.export.entities.DataExportType;
import io.julienmetral.tasks.export.messaging.ExportQueues;
import io.julienmetral.tasks.export.repositories.BatchMetadataQueries;
import io.julienmetral.tasks.identity.entities.User;
import io.julienmetral.tasks.identity.entities.UserRole;
import io.julienmetral.tasks.support.SqlStatementCounter;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.batch.core.BatchStatus;
import org.springframework.batch.core.job.JobExecution;
import org.springframework.batch.core.job.JobInstance;
import org.springframework.batch.core.job.parameters.JobParameters;
import org.springframework.batch.core.job.parameters.JobParametersBuilder;
import org.springframework.batch.core.repository.JobRepository;
import org.springframework.batch.core.step.StepExecution;
import org.springframework.batch.infrastructure.item.ExecutionContext;
import org.springframework.beans.factory.annotation.Autowired;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.NoSuchKeyException;
import tools.jackson.databind.JsonNode;

import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Pattern;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The scheduled work of the exports, called directly: the purge, and the recovery of interrupted runs and of lost run
 * messages, which relies on the lease a run renews.
 */
class DataExportLifecycleTests extends AbstractDataExportTests {

    private static final Instant LONG_AGO = Instant.parse("2000-01-01T00:00:00Z");

    private static final Pattern LEASE_RENEWAL = Pattern.compile("(?i)^update data_exports \\S+ set lease_until=\\?");

    @Autowired
    private DataExportService exportService;

    @Autowired
    private RabbitTemplate rabbitTemplate;

    @Autowired
    private DataExportRunner runner;

    @Autowired
    private BatchMetadataQueries batchMetadataQueries;

    @Autowired
    private JobRepository jobRepository;

    @Autowired
    private S3Client s3Client;

    @Autowired
    private StorageProperties storageProperties;

    // Purge

    @Test
    void purgeDeletesTheFileOfAnExportPastItsRetentionAndKeepsTheExportListedAsExpired() throws Exception {
        User owner = createUser(UserRole.USER);
        UUID exportId = exportTasksOf(owner, owner, "");
        String storageKey = storageKeyOf(exportId);
        Instant expiresAt = jdbcTemplate.queryForObject(
                "SELECT expires_at FROM data_exports WHERE id = ?", Timestamp.class, exportId).toInstant();

        exportService.purge();
        assertThat(statusOf(exportId)).isEqualTo("COMPLETED");

        // Just past this export's expiry: the purge reaches no export completed after it, in any test context
        testClock.set(expiresAt.plusSeconds(1));
        assertThat(exportService.purge()).isPositive();

        assertThat(statusOf(exportId)).isEqualTo("EXPIRED");
        assertThat(jdbcTemplate.queryForObject(
                "SELECT count(*) FROM media WHERE storage_key = ?", Integer.class, storageKey)).isZero();
        assertThatThrownBy(() -> s3Client.headObject(request -> request
                .bucket(storageProperties.bucket())
                .key(storageKey)))
                .isInstanceOf(NoSuchKeyException.class);

        JsonNode export = exportJson(owner, exportId);
        assertThat(export.path("status").asString()).isEqualTo("EXPIRED");
        assertThat(export.path("downloadUrl").isNull()).isTrue();
    }

    @Test
    void purgeDeletesExpiredAndFailedExportsOnceTheHistoryRetentionHasPassed() {
        User owner = createUser(UserRole.USER);
        UUID expired = insertEndedExport(owner, "EXPIRED", LONG_AGO, null);
        UUID failed = insertEndedExport(owner, "FAILED", LONG_AGO, null);
        UUID failedRecently = insertEndedExport(owner, "FAILED", Instant.now(), null);

        exportService.purge();

        assertThat(exportIdsOf(owner)).containsExactly(failedRecently).doesNotContain(expired, failed);
    }

    // The status change must reach the database before the deletion of old rows, which is a bulk JPQL delete
    @Test
    void exportCompletedBeforeTheHistoryRetentionIsExpiredAndDeletedInTheSamePurge() {
        User owner = createUser(UserRole.USER);
        UUID file = jdbcTemplate.queryForObject(
                """
                        INSERT INTO media (storage_key, usage, original_filename, content_type, size_bytes, sha256,
                                           uploaded_by_id, created_at)
                        VALUES (?, 'EXPORT', 'tasks-2000-01-01.csv', 'text/csv', 1, repeat('0', 64), ?, ?)
                        RETURNING id
                        """,
                UUID.class,
                "export/" + UUID.randomUUID(), owner.getId(), Timestamp.from(LONG_AGO)
        );
        UUID completed = insertEndedExport(owner, "COMPLETED", LONG_AGO, file);

        exportService.purge();

        assertThat(exportIdsOf(owner)).doesNotContain(completed);
        assertThat(jdbcTemplate.queryForObject("SELECT count(*) FROM media WHERE id = ?", Integer.class, file))
                .isZero();
    }

    // Batch writes its own clock into its tables, which the test clock cannot move: the run's end is moved to 2000
    // instead, so that the cutoff reaches no other execution of the shared database
    @Test
    void batchHistoryOfAnExportIsDeletedWithItsStepsContextsAndParameters() throws Exception {
        User owner = createUser(UserRole.USER);
        UUID exportId = exportTasksOf(owner, owner, "");
        JobInstance instance = jobRepository.getJobInstance(CsvExportJobs.TASKS_JOB, parametersOf(exportId));
        List<Long> executions = jdbcTemplate.queryForList(
                "SELECT job_execution_id FROM batch_job_execution WHERE job_instance_id = ?",
                Long.class,
                instance.getInstanceId()
        );
        assertThat(executions).hasSize(1);
        assertThat(batchRowsOf(executions.getFirst()).values()).allSatisfy(rows -> assertThat(rows).isPositive());
        jdbcTemplate.update(
                "UPDATE batch_job_execution SET end_time = ? WHERE job_instance_id = ?",
                Timestamp.valueOf(LocalDateTime.of(2000, 1, 1, 0, 0)), instance.getInstanceId()
        );

        assertThat(batchMetadataQueries.deleteExecutionsEndedBefore(Instant.parse("2000-06-01T00:00:00Z")))
                .isPositive();

        assertThat(batchRowsOf(executions.getFirst()).values()).containsOnly(0);
        assertThat(jobRepository.getJobInstance(CsvExportJobs.TASKS_JOB, parametersOf(exportId))).isNull();
        assertThat(statusOf(exportId)).isEqualTo("COMPLETED");
    }

    // Recovery

    @Test
    void interruptedExportIsQueuedAgainAndItsBatchRunRestartsToCompletion() throws Exception {
        User owner = createUser(UserRole.USER);
        String reference = insertTask(TaskRow.assignedTo(owner));
        UUID exportId = insertInterruptedExport(owner, 1, Instant.now().minus(Duration.ofMinutes(1)));
        JobExecution interrupted = startedRunOf(exportId);

        assertThat(exportService.requeueInterrupted()).isPositive();
        awaitCompleted(exportId);

        assertThat(jdbcTemplate.queryForObject(
                "SELECT attempts FROM data_exports WHERE id = ?", Integer.class, exportId)).isEqualTo(2);
        List<JobExecution> runs = jobRepository.getJobExecutions(interrupted.getJobInstance());
        assertThat(runs).extracting(JobExecution::getStatus)
                .containsExactlyInAnyOrder(BatchStatus.FAILED, BatchStatus.COMPLETED);
        JobExecution recovered = runs.stream()
                .filter(run -> run.getId() == interrupted.getId())
                .findFirst()
                .orElseThrow();
        assertThat(recovered.getStatus()).isEqualTo(BatchStatus.FAILED);
        assertThat(recovered.getEndTime()).isNotNull();
        assertThat(recovered.getStepExecutions())
                .extracting(StepExecution::getStatus)
                .containsOnly(BatchStatus.FAILED);
        assertThat(downloadCsv(owner, exportId).column("reference")).containsExactly(reference);
    }

    @Test
    void interruptedExportThatUsedEveryAttemptFailsAndItsOwnerIsEmailed() {
        User owner = createUser(UserRole.USER);
        UUID exportId = insertInterruptedExport(owner, 3, Instant.now().minus(Duration.ofMinutes(1)));

        exportService.requeueInterrupted();

        assertThat(statusOf(exportId)).isEqualTo("FAILED");
        assertThat(failureOf(exportId)).isEqualTo("Interrupted");
        assertThat(jdbcTemplate.queryForObject(
                "SELECT lease_until FROM data_exports WHERE id = ?", Timestamp.class, exportId)).isNull();
        assertThat(mailpit.latestTextTo(owner.getEmail(), "could not be produced"))
                .contains("Your export of tasks could not be produced.")
                .contains("reference: " + exportId + ".");
    }

    @Test
    void exportRunningUnderALiveLeaseIsNotQueuedAgain() {
        User owner = createUser(UserRole.USER);
        UUID exportId = insertInterruptedExport(owner, 1, Instant.now().plus(Duration.ofMinutes(10)));

        try {
            exportService.requeueInterrupted();

            assertThat(statusOf(exportId)).isEqualTo("RUNNING");
        } finally {
            jdbcTemplate.update("DELETE FROM data_exports WHERE id = ?", exportId);
        }
    }

    @Test
    void queuedExportWhoseRunMessageWasLostIsSentAgainOnceItsLeaseRanOutAndCompletes() throws Exception {
        User owner = createUser(UserRole.USER);
        String reference = insertTask(TaskRow.assignedTo(owner));
        String body = "{\"assigneeId\": \"%s\"}".formatted(owner.getId());
        List<UUID> queued = new ArrayList<>();

        withExportListenerStopped(() -> {
            queued.add(exportIdOf(requestTasksExport(owner, body)));

            // Taken off the queue, as the listener does when it dead-letters a message it cannot handle
            Message lost = rabbitTemplate.receive(ExportQueues.RUN, RUN_TIMEOUT.toMillis());
            assertThat(lost).isNotNull();
            assertThat(new String(lost.getBody(), UTF_8)).contains(queued.getFirst().toString());
        });
        UUID exportId = queued.getFirst();
        Instant leaseUntil = leaseOf(exportId);

        exportService.requeueInterrupted();
        assertThat(statusOf(exportId)).isEqualTo("QUEUED");
        assertThat(leaseOf(exportId)).isEqualTo(leaseUntil);

        testClock.set(leaseUntil.plusSeconds(1));
        assertThat(exportService.requeueInterrupted()).isPositive();
        awaitCompleted(exportId);

        assertThat(downloadCsv(owner, exportId).column("reference")).containsExactly(reference);
        mockMvc.perform(delete(EXPORTS + "/{id}", exportId).with(as(owner)))
                .andExpect(status().isNoContent());
        awaitCompleted(exportIdOf(requestTasksExport(owner, body)));
    }

    // Statements of the test thread only: the run is started here rather than by the listener. The steps renew through
    // DataExportService.renewLease, the only update of data_exports that sets the lease alone
    @Test
    void runRenewsItsLeaseAsEachStepStartsAndAfterEachChunk() throws Exception {
        User owner = createUser(UserRole.USER);
        String prefix = insertTasksAssignedTo(owner, 1001);
        UUID exportId = insertQueuedExport(owner, DataExportType.TASKS_CSV);

        try {
            List<String> statements = SqlStatementCounter.statementsDuring(() -> runner.run(exportId));

            assertThat(statusOf(exportId)).isEqualTo("COMPLETED");
            // The write step starts, writes chunks of 500, 500 and 1 rows, then the publish step starts
            assertThat(statements).filteredOn(sql -> LEASE_RENEWAL.matcher(sql).find()).hasSize(5);
        } finally {
            jdbcTemplate.update("DELETE FROM tasks WHERE assigned_to_id = ? AND reference LIKE ?",
                    owner.getId(), prefix + "-%");
        }
    }

    // Every account of the shared database, so at least one chunk: without the listener on its write step, the only
    // renewal would be the one of the publish step
    @Test
    void usersExportRenewsItsLeaseAfterTheChunksOfItsWriteStepToo() throws Exception {
        User admin = createUser(UserRole.ADMIN);
        UUID exportId = insertQueuedExport(admin, DataExportType.USERS_CSV);

        List<String> statements = SqlStatementCounter.statementsDuring(() -> runner.run(exportId));

        assertThat(statusOf(exportId)).isEqualTo("COMPLETED");
        assertThat(statements).filteredOn(sql -> LEASE_RENEWAL.matcher(sql).find()).hasSizeGreaterThanOrEqualTo(3);
        mockMvc.perform(delete(EXPORTS + "/{id}", exportId).with(as(admin)))
                .andExpect(status().isNoContent());
    }

    @Test
    void runThatRenewsItsLeaseIsNotQueuedAgainWhileAStoppedOneIs() {
        Instant now = Instant.now().truncatedTo(ChronoUnit.MICROS);
        testClock.set(now);
        UUID longRun = insertInterruptedExport(createUser(UserRole.USER), 1, now.minus(Duration.ofMinutes(1)));
        UUID stopped = insertInterruptedExport(createUser(UserRole.USER), 1, now.minus(Duration.ofMinutes(1)));

        try {
            exportService.renewLease(longRun);
            exportService.requeueInterrupted();

            assertThat(statusOf(longRun)).isEqualTo("RUNNING");
            assertThat(leaseOf(longRun)).isEqualTo(now.plus(Duration.ofMinutes(15)));
            assertThat(jdbcTemplate.queryForObject(
                    "SELECT attempts FROM data_exports WHERE id = ?", Integer.class, longRun)).isOne();
            awaitCompleted(stopped);
        } finally {
            jdbcTemplate.update("DELETE FROM data_exports WHERE id = ?", longRun);
        }
    }

    @Test
    void secondRunMessageForACompletedExportStartsNoNewRun() throws Exception {
        User owner = createUser(UserRole.USER);
        UUID exportId = exportTasksOf(owner, owner, "");

        runner.run(exportId);

        JobInstance instance = jobRepository.getJobInstance(CsvExportJobs.TASKS_JOB, parametersOf(exportId));
        assertThat(jobRepository.getJobExecutions(instance)).hasSize(1);
        assertThat(statusOf(exportId)).isEqualTo("COMPLETED");
        assertThat(jdbcTemplate.queryForObject(
                "SELECT attempts FROM data_exports WHERE id = ?", Integer.class, exportId)).isOne();
    }

    private Instant leaseOf(UUID exportId) {
        return jdbcTemplate.queryForObject(
                "SELECT lease_until FROM data_exports WHERE id = ?", Timestamp.class, exportId).toInstant();
    }

    /**
     * An export as a request leaves it, without its run message: of the owner's own tasks, or of every user.
     */
    private UUID insertQueuedExport(User owner, DataExportType type) {
        Instant now = Instant.now();
        boolean tasks = type == DataExportType.TASKS_CSV;

        return jdbcTemplate.queryForObject(
                """
                        INSERT INTO data_exports (owner_id, type, status, filter_assignee_id, filter_archived,
                                                  attempts, lease_until, created_at)
                        VALUES (?, ?, 'QUEUED', ?, ?, 0, ?, ?)
                        RETURNING id
                        """,
                UUID.class,
                owner.getId(), type.name(), tasks ? owner.getId() : null, tasks ? false : null,
                Timestamp.from(now.plus(Duration.ofMinutes(15))), Timestamp.from(now)
        );
    }

    /** @return the prefix of their references */
    private String insertTasksAssignedTo(User assignee, int count) {
        String prefix = "LSE-" + UUID.randomUUID().toString().substring(0, 8);

        jdbcTemplate.update(
                """
                        INSERT INTO tasks (reference, title, status, priority, assigned_to_id, created_at, updated_at,
                                           version)
                        SELECT ? || '-' || n, 'Leased task ' || n, 'TO_DO', 'LOW', ?, now(), now(), 0
                        FROM generate_series(1, ?) AS n
                        """,
                prefix, assignee.getId(), count
        );
        return prefix;
    }

    private static JobParameters parametersOf(UUID exportId) {
        return new JobParametersBuilder().addString(PublishExport.EXPORT_ID, exportId.toString()).toJobParameters();
    }

    private UUID insertEndedExport(User owner, String status, Instant createdAt, UUID mediaId) {
        return jdbcTemplate.queryForObject(
                """
                        INSERT INTO data_exports (owner_id, type, status, filter_archived, media_id, attempts,
                                                  created_at, completed_at, expires_at)
                        VALUES (?, 'TASKS_CSV', ?, false, ?, 1, ?, ?, ?)
                        RETURNING id
                        """,
                UUID.class,
                owner.getId(), status, mediaId, Timestamp.from(createdAt), Timestamp.from(createdAt),
                Timestamp.from(createdAt.plus(Duration.ofDays(7)))
        );
    }

    /** A tasks export of the owner's own tasks, left RUNNING by an instance that stopped. */
    private UUID insertInterruptedExport(User owner, int attempts, Instant leaseUntil) {
        Timestamp startedAt = Timestamp.from(Instant.now().minus(Duration.ofMinutes(20)));

        return jdbcTemplate.queryForObject(
                """
                        INSERT INTO data_exports (owner_id, type, status, filter_assignee_id, filter_archived,
                                                  attempts, lease_until, created_at, started_at)
                        VALUES (?, 'TASKS_CSV', 'RUNNING', ?, false, ?, ?, ?, ?)
                        RETURNING id
                        """,
                UUID.class,
                owner.getId(), owner.getId(), attempts, Timestamp.from(leaseUntil), startedAt, startedAt
        );
    }

    /** What Batch leaves of a run whose instance stopped in its first step: the job and the step STARTED. */
    private JobExecution startedRunOf(UUID exportId) {
        JobParameters parameters = parametersOf(exportId);
        JobInstance instance = jobRepository.createJobInstance(CsvExportJobs.TASKS_JOB, parameters);
        JobExecution execution = jobRepository.createJobExecution(instance, parameters, new ExecutionContext());
        execution.setStartTime(LocalDateTime.now());
        execution.setStatus(BatchStatus.STARTED);
        jobRepository.update(execution);

        StepExecution write = jobRepository.createStepExecution("tasksCsvWrite", execution);
        write.setStartTime(LocalDateTime.now());
        write.setStatus(BatchStatus.STARTED);
        jobRepository.update(write);

        return execution;
    }

    private List<UUID> exportIdsOf(User owner) {
        return jdbcTemplate.queryForList("SELECT id FROM data_exports WHERE owner_id = ?", UUID.class, owner.getId());
    }

    private Map<String, Integer> batchRowsOf(long execution) {
        return Map.of(
                "batch_job_execution", count(
                        "SELECT count(*) FROM batch_job_execution WHERE job_execution_id = ?", execution),
                "batch_job_execution_params", count(
                        "SELECT count(*) FROM batch_job_execution_params WHERE job_execution_id = ?", execution),
                "batch_job_execution_context", count(
                        "SELECT count(*) FROM batch_job_execution_context WHERE job_execution_id = ?", execution),
                "batch_step_execution", count(
                        "SELECT count(*) FROM batch_step_execution WHERE job_execution_id = ?", execution),
                "batch_step_execution_context", count("""
                        SELECT count(*) FROM batch_step_execution_context c
                        JOIN batch_step_execution s ON s.step_execution_id = c.step_execution_id
                        WHERE s.job_execution_id = ?
                        """, execution)
        );
    }

    private int count(String sql, long execution) {
        return jdbcTemplate.queryForObject(sql, Integer.class, execution);
    }
}
