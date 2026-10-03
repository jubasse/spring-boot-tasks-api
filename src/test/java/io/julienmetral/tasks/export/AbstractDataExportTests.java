package io.julienmetral.tasks.export;

import io.julienmetral.tasks.export.messaging.ExportQueues;
import io.julienmetral.tasks.identity.entities.User;
import io.julienmetral.tasks.identity.entities.UserRole;
import io.julienmetral.tasks.identity.repositories.UserRepository;
import io.julienmetral.tasks.support.IntegrationTest;
import io.julienmetral.tasks.support.Mailpit;
import io.julienmetral.tasks.support.TestClock;
import org.apache.commons.csv.CSVFormat;
import org.apache.commons.csv.CSVParser;
import org.apache.commons.csv.CSVRecord;
import org.springframework.amqp.core.MessageListenerContainer;
import org.springframework.amqp.rabbit.listener.AbstractMessageListenerContainer;
import org.springframework.amqp.rabbit.listener.RabbitListenerEndpointRegistry;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.EnumSet;
import java.util.List;
import java.util.UUID;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Shared fixtures for the export integration tests: active users and tasks written to the database, exports asked
 * for through the API and run by the RabbitMQ listener, and their CSV read back from the download link.
 * <p>
 * Warning: a tasks export reads every task of the shared database. Tests filter by an assignee of their own, or look
 * for their own references, and never count the rows of an unfiltered export.
 */
@IntegrationTest
public abstract class AbstractDataExportTests {

    protected static final String EXPORTS = "/api/v1/exports";

    protected static final String TASKS_EXPORT = EXPORTS + "/tasks";

    protected static final String USERS_EXPORT = EXPORTS + "/users";

    protected static final Duration RUN_TIMEOUT = Duration.ofSeconds(30);

    protected static final byte[] BYTE_ORDER_MARK = {(byte) 0xEF, (byte) 0xBB, (byte) 0xBF};

    @Autowired
    protected MockMvc mockMvc;

    @Autowired
    protected UserRepository userRepository;

    @Autowired
    protected PasswordEncoder passwordEncoder;

    @Autowired
    protected JdbcTemplate jdbcTemplate;

    @Autowired
    protected JsonMapper jsonMapper;

    @Autowired
    protected Mailpit mailpit;

    @Autowired
    protected TestClock testClock;

    @Autowired
    private RabbitListenerEndpointRegistry listenerRegistry;

    private final HttpClient httpClient = HttpClient.newHttpClient();

    protected User createUser(UserRole role) {
        User user = new User();

        user.setEmail("export-" + UUID.randomUUID() + "@example.com");
        user.setPasswordHash(passwordEncoder.encode("password"));
        user.setEmailVerifiedAt(Instant.now());
        user.setDisplayName("Export " + role + " " + UUID.randomUUID().toString().substring(0, 8));
        user.setRoles(EnumSet.of(UserRole.USER, role));

        return userRepository.saveAndFlush(user);
    }

    protected RequestPostProcessor as(User user) {
        GrantedAuthority[] authorities = user.getRoles().stream()
                .map(role -> new SimpleGrantedAuthority("ROLE_" + role.name()))
                .toArray(GrantedAuthority[]::new);

        return jwt().jwt(token -> token.claim("uid", user.getId().toString())).authorities(authorities);
    }

    protected static String uniqueReference() {
        return "EXP-" + UUID.randomUUID().toString().substring(0, 8);
    }

    /** Writes a task straight to the database, with the columns an export reads. */
    protected String insertTask(TaskRow task) {
        String reference = uniqueReference();

        jdbcTemplate.update(
                """
                        INSERT INTO tasks (reference, title, description, status, priority, assigned_to_id,
                                           created_by_id, due_at, archived_at, deleted_at, created_at, updated_at,
                                           version)
                        VALUES (?, ?, ?, ?, 'HIGH', ?, ?, ?, ?, ?, now(), now(), 0)
                        """,
                reference, task.title(), task.description(), task.status(), task.assigneeId(), task.creatorId(),
                timestamp(task.dueAt()), timestamp(task.archivedAt()), timestamp(task.deletedAt())
        );

        return reference;
    }

    protected MvcResult requestTasksExport(User owner, String body) throws Exception {
        return mockMvc.perform(post(TASKS_EXPORT)
                        .with(as(owner))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isAccepted())
                .andReturn();
    }

    protected MvcResult requestUsersExport(User admin) throws Exception {
        return mockMvc.perform(post(USERS_EXPORT).with(as(admin)))
                .andExpect(status().isAccepted())
                .andReturn();
    }

    protected static UUID exportIdOf(MvcResult accepted) {
        String location = accepted.getResponse().getHeader(HttpHeaders.LOCATION);

        assertThat(location).startsWith(EXPORTS + "/");
        return UUID.fromString(location.substring(EXPORTS.length() + 1));
    }

    /** Asks for a tasks export of the assignee's tasks with the given extra filters, and waits for its end. */
    protected UUID exportTasksOf(User owner, User assignee, String extraFilters) throws Exception {
        String body = "{\"assigneeId\": \"%s\"%s}".formatted(assignee.getId(),
                extraFilters.isEmpty() ? "" : ", " + extraFilters);
        UUID exportId = exportIdOf(requestTasksExport(owner, body));

        awaitCompleted(exportId);
        return exportId;
    }

    protected String statusOf(UUID exportId) {
        return jdbcTemplate.queryForObject("SELECT status FROM data_exports WHERE id = ?", String.class, exportId);
    }

    /** The job runs on the listener's thread, after the request's commit. */
    protected String awaitEnded(UUID exportId) {
        await().atMost(RUN_TIMEOUT)
                .pollInterval(Duration.ofMillis(100))
                .until(() -> List.of("COMPLETED", "FAILED").contains(statusOf(exportId)));

        return statusOf(exportId);
    }

    protected void awaitCompleted(UUID exportId) {
        assertThat(awaitEnded(exportId))
                .as("export %s, failure %s", exportId, failureOf(exportId))
                .isEqualTo("COMPLETED");
    }

    protected String failureOf(UUID exportId) {
        return jdbcTemplate.queryForObject("SELECT failure FROM data_exports WHERE id = ?", String.class, exportId);
    }

    protected JsonNode exportJson(User owner, UUID exportId) throws Exception {
        String body = mockMvc.perform(get(EXPORTS + "/{id}", exportId).with(as(owner)))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString();

        return jsonMapper.readTree(body);
    }

    protected HttpResponse<byte[]> download(User owner, UUID exportId) throws Exception {
        String url = exportJson(owner, exportId).path("downloadUrl").asString();
        HttpResponse<byte[]> response = httpClient.send(
                HttpRequest.newBuilder(URI.create(url)).GET().build(),
                HttpResponse.BodyHandlers.ofByteArray()
        );

        assertThat(response.statusCode()).isEqualTo(200);
        return response;
    }

    /** The downloaded file, parsed as RFC 4180 CSV once its byte order mark is checked and removed. */
    protected Csv downloadCsv(User owner, UUID exportId) throws Exception {
        return Csv.parse(download(owner, exportId).body());
    }

    protected String storageKeyOf(UUID exportId) {
        return jdbcTemplate.queryForObject(
                "SELECT m.storage_key FROM data_exports e JOIN media m ON m.id = e.media_id WHERE e.id = ?",
                String.class,
                exportId
        );
    }

    /** Runs {@code work} while no export runs in this context, so that what it asks for stays queued. */
    protected void withExportListenerStopped(ThrowingRunnable work) throws Exception {
        MessageListenerContainer listener = listenerRegistry.getListenerContainers().stream()
                .filter(container -> container instanceof AbstractMessageListenerContainer consumer
                        && List.of(consumer.getQueueNames()).contains(ExportQueues.RUN))
                .findFirst()
                .orElseThrow();

        listener.stop();
        try {
            work.run();
        } finally {
            listener.start();
        }
    }

    private static Timestamp timestamp(Instant instant) {
        return instant == null ? null : Timestamp.from(instant);
    }

    @FunctionalInterface
    protected interface ThrowingRunnable {

        void run() throws Exception;
    }

    /** A task as {@link #insertTask} writes it; {@code status} is a {@code TaskStatus} name. */
    protected record TaskRow(
            String title,
            String description,
            String status,
            UUID assigneeId,
            UUID creatorId,
            Instant dueAt,
            Instant archivedAt,
            Instant deletedAt
    ) {

        public static TaskRow assignedTo(User assignee) {
            return new TaskRow("Exported task", null, "TO_DO", assignee.getId(), null, null, null, null);
        }

        public TaskRow titled(String newTitle, String newDescription) {
            return new TaskRow(newTitle, newDescription, status, assigneeId, creatorId, dueAt, archivedAt, deletedAt);
        }

        public TaskRow withStatus(String newStatus) {
            return new TaskRow(title, description, newStatus, assigneeId, creatorId, dueAt, archivedAt, deletedAt);
        }

        public TaskRow createdBy(User creator, Instant newDueAt) {
            return new TaskRow(title, description, status, assigneeId, creator.getId(), newDueAt, archivedAt,
                    deletedAt);
        }

        public TaskRow archived() {
            return new TaskRow(title, description, status, assigneeId, creatorId, dueAt, Instant.now(), deletedAt);
        }

        public TaskRow deleted() {
            return new TaskRow(title, description, status, assigneeId, creatorId, dueAt, archivedAt, Instant.now());
        }
    }

    protected record Csv(String text, List<String> header, List<CSVRecord> records) {

        static Csv parse(byte[] file) throws IOException {
            assertThat(file).startsWith(BYTE_ORDER_MARK);
            String text = new String(file, BYTE_ORDER_MARK.length, file.length - BYTE_ORDER_MARK.length, UTF_8);
            CSVFormat format = CSVFormat.RFC4180.builder().setHeader().setSkipHeaderRecord(true).get();

            try (CSVParser parser = CSVParser.parse(text, format)) {
                return new Csv(text, parser.getHeaderNames(), parser.getRecords());
            }
        }

        public List<String> column(String name) {
            return records.stream().map(record -> record.get(name)).toList();
        }

        public CSVRecord rowWhere(String column, String value) {
            return records.stream()
                    .filter(record -> record.get(column).equals(value))
                    .findFirst()
                    .orElseThrow(() -> new AssertionError("No row with " + column + " = " + value));
        }
    }
}
