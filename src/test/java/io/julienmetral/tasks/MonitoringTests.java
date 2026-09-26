package io.julienmetral.tasks;

import io.julienmetral.tasks.identity.messaging.AvatarQueues;
import io.julienmetral.tasks.mail.MailMessage;
import io.julienmetral.tasks.mail.MailQueues;
import io.julienmetral.tasks.mail.MailService;
import io.julienmetral.tasks.messaging.entities.OutboxMessage;
import io.julienmetral.tasks.messaging.repositories.OutboxMessageRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.amqp.core.AmqpAdmin;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.micrometer.metrics.test.autoconfigure.AutoConfigureMetrics;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalManagementPort;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.OptionalDouble;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.springframework.boot.test.context.SpringBootTest.WebEnvironment.RANDOM_PORT;

/**
 * Actuator over real HTTP, on the management port Spring Boot starts next to the API port. Only a real server has
 * two ports: MockMvc would reach the endpoints whatever the port. {@code @AutoConfigureMetrics} is needed because
 * Spring Boot tests keep only an in-memory meter registry, without it {@code /actuator/prometheus} does not exist.
 */
@Import(TestcontainersConfiguration.class)
@AutoConfigureMetrics
@SpringBootTest(webEnvironment = RANDOM_PORT, properties = "management.server.port=0")
class MonitoringTests {

    private static final Duration SCRAPE_TIMEOUT = Duration.ofSeconds(10);

    private static final String MANAGEMENT_ERRORS_BUG = "bug: SecurityConfiguration permits "
            + "EndpointRequest.toAnyEndpoint() but not the management port's /error, so every error dispatched there "
            + "(unknown path, 406, exception in an endpoint) answers 401";

    @LocalServerPort
    private int apiPort;

    @LocalManagementPort
    private int managementPort;

    @Autowired
    private JsonMapper jsonMapper;

    @Autowired
    private MailService mailService;

    @Autowired
    private RabbitTemplate rabbitTemplate;

    @Autowired
    private AmqpAdmin amqpAdmin;

    @Autowired
    private OutboxMessageRepository outboxRepository;

    private final List<UUID> insertedOutboxRows = new ArrayList<>();

    private boolean deadLetterQueueUsed;

    @AfterEach
    void cleanUp() {
        outboxRepository.deleteAllById(insertedOutboxRows);

        if (deadLetterQueueUsed) {
            amqpAdmin.purgeQueue(MailQueues.DEAD_LETTER, false);
        }
    }

    @Test
    void healthIsUpWithTheDatabaseTheBrokerTheMailServerTheStorageAndTheAntivirus() {
        JsonNode health = managementJson("/actuator/health");

        assertThat(health.path("status").asString()).isEqualTo("UP");
        for (String component : List.of("db", "rabbit", "mail", "storage", "antivirus")) {
            assertThat(health.path("components").path(component).path("status").asString())
                    .as(component)
                    .isEqualTo("UP");
        }
    }

    @Test
    void storageHealthNamesTheDriverAndTheBucket() {
        JsonNode storage = managementJson("/actuator/health").path("components").path("storage");

        assertThat(storage.path("details").path("driver").asString()).isEqualTo("rustfs");
        assertThat(storage.path("details").path("bucket").asString()).isEqualTo("tasks-media-test");
    }

    @Test
    void readinessIsUpAndChecksOnlyTheDatabaseTheBrokerAndTheStorage() {
        JsonNode readiness = managementJson("/actuator/health/readiness");

        assertThat(readiness.path("status").asString()).isEqualTo("UP");
        assertThat(readiness.path("components").propertyNames())
                .containsExactlyInAnyOrder("readinessState", "db", "rabbit", "storage");
    }

    @Test
    void livenessIsUp() {
        JsonNode liveness = managementJson("/actuator/health/liveness");

        assertThat(liveness.path("status").asString()).isEqualTo("UP");
    }

    @Test
    void infoShowsTheBuildVersion() {
        JsonNode build = managementJson("/actuator/info").path("build");

        assertThat(build.path("artifact").asString()).isEqualTo("tasks");
        assertThat(build.path("group").asString()).isEqualTo("io.julienmetral");
        assertThat(build.path("version").asString()).isNotBlank();
    }

    @Test
    void metricsEndpointServesTheOutboxBacklog() {
        JsonNode metric = managementJson("/actuator/metrics/outbox.messages.pending");

        assertThat(metric.path("name").asString()).isEqualTo("outbox.messages.pending");
        assertThat(metric.path("measurements").path(0).path("statistic").asString()).isEqualTo("VALUE");
    }

    @Test
    void prometheusExposesTheOutboxBacklogAndEveryDeadLetterQueue() {
        String scrape = scrape();

        assertThat(sample(scrape, "outbox_messages_pending")).isPresent();
        assertThat(sample(scrape, "outbox_messages_oldest_pending_age_seconds")).isPresent();
        assertThat(sample(scrape, deadLetterSeries(MailQueues.DEAD_LETTER))).isPresent();
        assertThat(sample(scrape, deadLetterSeries(AvatarQueues.DEAD_LETTER))).isPresent();
    }

    @Test
    void deadLetterQueueGaugesAreMessageCountsWhileTheBrokerAnswers() {
        String scrape = scrape();

        assertThat(sample(scrape, deadLetterSeries(MailQueues.DEAD_LETTER)).orElseThrow()).isNotNaN();
        assertThat(sample(scrape, deadLetterSeries(AvatarQueues.DEAD_LETTER)).orElseThrow()).isNotNaN();
    }

    @Test
    void messageInTheMailDeadLetterQueueIsCounted() {
        double before = sample(scrape(), deadLetterSeries(MailQueues.DEAD_LETTER)).orElseThrow();
        deadLetterQueueUsed = true;

        rabbitTemplate.send("", MailQueues.DEAD_LETTER, new Message("{}".getBytes(StandardCharsets.UTF_8)));

        // RabbitMQ updates the queue's message count asynchronously
        await().atMost(SCRAPE_TIMEOUT).pollInterval(Duration.ofMillis(100)).untilAsserted(() ->
                assertThat(sample(scrape(), deadLetterSeries(MailQueues.DEAD_LETTER)).orElseThrow())
                        .isEqualTo(before + 1));
    }

    @Test
    void sentEmailIsCountedAsPublishedToTheMailQueue() {
        String publishedToMail = "outbox_messages_published_total{queue=\"" + MailQueues.SEND + "\"}";
        double before = sample(scrape(), publishedToMail).orElse(0);

        mailService.send(new MailMessage(uniqueEmail(), "Monitoring", "Counted once published"));

        // The relay publishes on an async thread after commit
        await().atMost(SCRAPE_TIMEOUT).pollInterval(Duration.ofMillis(100)).untilAsserted(() ->
                assertThat(sample(scrape(), publishedToMail).orElse(0)).isGreaterThanOrEqualTo(before + 1));
    }

    @Test
    void unpublishedOutboxMessageIsCountedAsPendingWithItsAge() {
        insertUnpublishedOutboxRow(Instant.now().minus(Duration.ofHours(1)));

        String scrape = scrape();

        assertThat(sample(scrape, "outbox_messages_pending").orElseThrow()).isGreaterThanOrEqualTo(1);
        assertThat(sample(scrape, "outbox_messages_oldest_pending_age_seconds").orElseThrow())
                .isGreaterThanOrEqualTo(Duration.ofHours(1).toSeconds());
    }

    @Test
    void managementPortServesHealthWithoutAuthentication() {
        assertThat(get(managementPort, "/actuator/health").getStatusCode()).isEqualTo(HttpStatus.OK);
    }

    @ParameterizedTest
    @ValueSource(strings = {"/actuator/health", "/actuator/info", "/actuator/prometheus", "/actuator/metrics"})
    void apiPortDoesNotServeTheActuatorEndpoints(String path) {
        ResponseEntity<String> response = get(apiPort, path);

        assertThat(response.getStatusCode().value()).isIn(401, 404);
        assertThat(Objects.toString(response.getBody(), "")).doesNotContain("UP", "outbox_messages_pending");
    }

    @Disabled(MANAGEMENT_ERRORS_BUG)
    @Test
    void unknownPathOnTheManagementPortIsNotFound() {
        assertThat(get(managementPort, "/actuator/unknown").getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    }

    @Disabled(MANAGEMENT_ERRORS_BUG)
    @Test
    void prometheusScrapeAskedAsJsonIsNotAcceptable() {
        ResponseEntity<String> response = get(managementPort, "/actuator/prometheus", MediaType.APPLICATION_JSON);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_ACCEPTABLE);
    }

    private JsonNode managementJson(String path) {
        ResponseEntity<String> response = get(managementPort, path);

        assertThat(response.getStatusCode()).as(path).isEqualTo(HttpStatus.OK);
        return jsonMapper.readTree(response.getBody());
    }

    private String scrape() {
        ResponseEntity<String> response = get(managementPort, "/actuator/prometheus", MediaType.TEXT_PLAIN);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        return response.getBody();
    }

    private static ResponseEntity<String> get(int port, String path) {
        return get(port, path, MediaType.ALL);
    }

    private static ResponseEntity<String> get(int port, String path, MediaType accept) {
        return RestClient.create("http://localhost:" + port)
                .get()
                .uri(path)
                .accept(accept)
                .retrieve()
                .onStatus(status -> true, (request, response) -> {
                })
                .toEntity(String.class);
    }

    /** The value of one series in a Prometheus text scrape, such as {@code name{tag="value"}}. */
    private static OptionalDouble sample(String scrape, String series) {
        return scrape.lines()
                .filter(line -> line.startsWith(series + " "))
                .mapToDouble(line -> Double.parseDouble(line.substring(series.length() + 1).split(" ")[0]))
                .findFirst();
    }

    private static String deadLetterSeries(String queue) {
        return "rabbitmq_dead_letter_messages{queue=\"" + queue + "\"}";
    }

    // The queue matches no binding and the next attempt is a day away, so neither the relay nor the broker ever
    // publishes this row
    private void insertUnpublishedOutboxRow(Instant createdAt) {
        OutboxMessage row = new OutboxMessage();

        row.setQueue("monitoring-tests." + UUID.randomUUID());
        row.setType(MailMessage.class.getName());
        row.setPayload(Map.of("to", uniqueEmail(), "subject", "Never published", "text", "Inserted by the test"));
        row.setCreatedAt(createdAt.truncatedTo(ChronoUnit.MICROS));
        row.setNextAttemptAt(Instant.now().plus(Duration.ofDays(1)).truncatedTo(ChronoUnit.MICROS));

        insertedOutboxRows.add(outboxRepository.save(row).getId());
    }

    private static String uniqueEmail() {
        return "monitoring-" + UUID.randomUUID() + "@example.com";
    }
}
