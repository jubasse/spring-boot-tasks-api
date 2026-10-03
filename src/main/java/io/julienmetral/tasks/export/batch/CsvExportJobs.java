package io.julienmetral.tasks.export.batch;

import io.julienmetral.tasks.export.ExportProperties;
import io.julienmetral.tasks.export.entities.DataExport;
import io.julienmetral.tasks.export.entities.TaskExportFilters;
import io.julienmetral.tasks.export.services.DataExportService;
import lombok.RequiredArgsConstructor;
import org.springframework.batch.core.configuration.annotation.StepScope;
import org.springframework.batch.core.job.Job;
import org.springframework.batch.core.job.builder.JobBuilder;
import org.springframework.batch.core.repository.JobRepository;
import org.springframework.batch.core.step.Step;
import org.springframework.batch.core.step.builder.StepBuilder;
import org.springframework.batch.infrastructure.item.database.JdbcPagingItemReader;
import org.springframework.batch.infrastructure.item.database.Order;
import org.springframework.batch.infrastructure.item.database.builder.JdbcPagingItemReaderBuilder;
import org.springframework.batch.infrastructure.item.database.support.PostgresPagingQueryProvider;
import org.springframework.batch.infrastructure.item.file.FlatFileItemWriter;
import org.springframework.batch.infrastructure.item.file.builder.FlatFileItemWriterBuilder;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.io.FileSystemResource;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.transaction.PlatformTransactionManager;

import javax.sql.DataSource;
import java.nio.charset.StandardCharsets;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;

/**
 * The CSV exports, one Spring Batch job each: a chunk step pages through the rows and writes them to a local file,
 * then a tasklet stores the file ({@link PublishExport}). The only job parameter is the export's id; the filters are
 * read from the export, so no personal data lands in Batch's tables.
 * <p>
 * Both steps run again when a job restarts after an interruption ({@code allowStartIfComplete}): the file of the
 * first attempt stayed on the instance that stopped.
 */
@Configuration
@RequiredArgsConstructor
public class CsvExportJobs {

    public static final String TASKS_JOB = "tasksCsvExport";
    public static final String USERS_JOB = "usersCsvExport";

    private static final String TASKS_WRITE = "tasksCsvWrite";
    private static final String USERS_WRITE = "usersCsvWrite";

    private static final String EXPORT_ID_PARAMETER = "#{jobParameters['" + PublishExport.EXPORT_ID + "']}";

    // Native SQL sees soft-deleted rows, which @SoftDelete hides from JPA: each query leaves them out itself
    private static final String TASKS = """
            SELECT t.id, t.reference, t.title, t.description, t.status, t.priority,
                   a.display_name AS assignee, c.display_name AS created_by,
                   t.due_at, t.completed_at, t.archived_at, t.created_at, t.updated_at
            FROM tasks t
            LEFT JOIN user_profiles a ON a.id = t.assigned_to_id
            LEFT JOIN user_profiles c ON c.id = t.created_by_id
            WHERE t.deleted_at IS NULL
            """;

    private static final String USERS = """
            SELECT u.id, u.email, p.display_name, p.status,
                   (SELECT string_agg(r.role, ' ' ORDER BY r.role) FROM user_roles r WHERE r.user_id = u.id) AS roles,
                   u.email_verified_at, u.created_at, u.last_active_at
            FROM users u
            JOIN user_profiles p ON p.id = u.id
            WHERE u.deleted_at IS NULL
            """;

    private final JobRepository jobRepository;
    private final PlatformTransactionManager transactionManager;
    private final ExportProperties properties;
    private final DataExportService exportService;
    private final DataExportJobListener jobListener;
    private final DataExportLeaseRenewal leaseRenewal;
    private final Clock clock;

    @Bean(TASKS_JOB)
    Job tasksCsvExport(Step tasksCsvWrite) {
        return csvJob(TASKS_JOB, tasksCsvWrite, TASKS_WRITE, "tasks");
    }

    @Bean(USERS_JOB)
    Job usersCsvExport(Step usersCsvWrite) {
        return csvJob(USERS_JOB, usersCsvWrite, USERS_WRITE, "users");
    }

    @Bean
    Step tasksCsvWrite(JdbcPagingItemReader<TaskCsvRow> tasksCsvReader, FlatFileItemWriter<TaskCsvRow> tasksCsvWriter) {
        return new StepBuilder(TASKS_WRITE, jobRepository)
                .allowStartIfComplete(true)
                .<TaskCsvRow, TaskCsvRow>chunk(properties.chunkSize())
                .listener(leaseRenewal)
                .reader(tasksCsvReader)
                .writer(tasksCsvWriter)
                .transactionManager(transactionManager)
                .build();
    }

    @Bean
    Step usersCsvWrite(JdbcPagingItemReader<UserCsvRow> usersCsvReader, FlatFileItemWriter<UserCsvRow> usersCsvWriter) {
        return new StepBuilder(USERS_WRITE, jobRepository)
                .allowStartIfComplete(true)
                .<UserCsvRow, UserCsvRow>chunk(properties.chunkSize())
                .listener(leaseRenewal)
                .reader(usersCsvReader)
                .writer(usersCsvWriter)
                .transactionManager(transactionManager)
                .build();
    }

    @Bean
    @StepScope
    JdbcPagingItemReader<TaskCsvRow> tasksCsvReader(
            DataSource dataSource,
            @Value(EXPORT_ID_PARAMETER) String exportId
    ) throws Exception {
        TaskExportFilters filters = exportService.find(UUID.fromString(exportId))
                .map(DataExport::getTaskFilters)
                .orElse(null);
        StringBuilder where = new StringBuilder();
        Map<String, Object> parameters = new HashMap<>();

        where.append(filters != null && Boolean.TRUE.equals(filters.archived())
                ? " AND t.archived_at IS NOT NULL"
                : " AND t.archived_at IS NULL");

        if (filters != null && filters.status() != null) {
            where.append(" AND t.status = :status");
            parameters.put("status", filters.status().name());
        }

        if (filters != null && filters.assigneeId() != null) {
            where.append(" AND t.assigned_to_id = :assigneeId");
            parameters.put("assigneeId", filters.assigneeId());
        }

        return pagingReader("tasksCsvReader", dataSource, TASKS + where, parameters, (rows, number) -> new TaskCsvRow(
                rows.getObject("id", UUID.class),
                rows.getString("reference"),
                rows.getString("title"),
                rows.getString("description"),
                rows.getString("status"),
                rows.getString("priority"),
                rows.getString("assignee"),
                rows.getString("created_by"),
                instant(rows, "due_at"),
                instant(rows, "completed_at"),
                instant(rows, "archived_at"),
                instant(rows, "created_at"),
                instant(rows, "updated_at")
        ));
    }

    @Bean
    @StepScope
    JdbcPagingItemReader<UserCsvRow> usersCsvReader(DataSource dataSource) throws Exception {
        return pagingReader("usersCsvReader", dataSource, USERS, Map.of(), (rows, number) -> new UserCsvRow(
                rows.getObject("id", UUID.class),
                rows.getString("email"),
                rows.getString("display_name"),
                rows.getString("status"),
                rows.getString("roles"),
                instant(rows, "email_verified_at"),
                instant(rows, "created_at"),
                instant(rows, "last_active_at")
        ));
    }

    @Bean
    @StepScope
    FlatFileItemWriter<TaskCsvRow> tasksCsvWriter(@Value(EXPORT_ID_PARAMETER) String exportId) {
        return csvWriter("tasksCsvWriter", UUID.fromString(exportId), TaskCsvRow.HEADER, TaskCsvRow::values);
    }

    @Bean
    @StepScope
    FlatFileItemWriter<UserCsvRow> usersCsvWriter(@Value(EXPORT_ID_PARAMETER) String exportId) {
        return csvWriter("usersCsvWriter", UUID.fromString(exportId), UserCsvRow.HEADER, UserCsvRow::values);
    }

    private Job csvJob(String name, Step write, String writeStepName, String filenamePrefix) {
        Step publish = new StepBuilder(name + "Publish", jobRepository)
                .allowStartIfComplete(true)
                .listener(leaseRenewal)
                .tasklet(new PublishExport(exportService, clock, writeStepName, filenamePrefix), transactionManager)
                .build();

        return new JobBuilder(name, jobRepository)
                .listener(jobListener)
                .start(write)
                .next(publish)
                .build();
    }

    /**
     * Pages by id, a UUIDv7, so each page starts after the last id of the previous one instead of skipping rows
     * with an offset. The query becomes a subquery, so that its joins cannot make the sort key ambiguous; the query
     * provider is given, since detecting it would read the database's metadata.
     */
    private <T> JdbcPagingItemReader<T> pagingReader(
            String name,
            DataSource dataSource,
            String query,
            Map<String, Object> parameters,
            RowMapper<T> rowMapper
    ) throws Exception {
        PostgresPagingQueryProvider queryProvider = new PostgresPagingQueryProvider();
        queryProvider.setSelectClause("*");
        queryProvider.setFromClause("(" + query + ") AS rows");
        queryProvider.setSortKeys(Map.of("id", Order.ASCENDING));

        return new JdbcPagingItemReaderBuilder<T>()
                .name(name)
                .dataSource(dataSource)
                .queryProvider(queryProvider)
                .parameterValues(parameters)
                .pageSize(properties.chunkSize())
                .rowMapper(rowMapper)
                .saveState(false)
                .build();
    }

    private static <T> FlatFileItemWriter<T> csvWriter(
            String name,
            UUID exportId,
            List<String> header,
            Function<T, List<Object>> values
    ) {
        return new FlatFileItemWriterBuilder<T>()
                .name(name)
                .resource(new FileSystemResource(ExportFiles.of(exportId, PublishExport.CSV_FILE)))
                .encoding(StandardCharsets.UTF_8.name())
                .lineSeparator("\r\n")
                // The byte order mark makes Excel read the file as UTF-8
                .headerCallback(writer -> writer.write("﻿" + CsvLineAggregator.line(header)))
                .lineAggregator(new CsvLineAggregator<>(values))
                .shouldDeleteIfExists(true)
                .saveState(false)
                .build();
    }

    private static Instant instant(ResultSet rows, String column) throws SQLException {
        Timestamp timestamp = rows.getTimestamp(column);

        return timestamp == null ? null : timestamp.toInstant();
    }
}
