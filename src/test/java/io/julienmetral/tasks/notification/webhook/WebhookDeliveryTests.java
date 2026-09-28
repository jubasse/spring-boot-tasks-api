package io.julienmetral.tasks.notification.webhook;

import com.standardwebhooks.Webhook;
import com.standardwebhooks.exceptions.WebhookVerificationException;
import io.julienmetral.tasks.identity.entities.User;
import io.julienmetral.tasks.identity.entities.UserRole;
import io.julienmetral.tasks.identity.repositories.UserRepository;
import io.julienmetral.tasks.identity.repositories.UserRetentionQueries;
import io.julienmetral.tasks.identity.services.UserService;
import io.julienmetral.tasks.notification.entities.WebhookDelivery;
import io.julienmetral.tasks.notification.entities.WebhookDeliveryStatus;
import io.julienmetral.tasks.notification.entities.WebhookEvent;
import io.julienmetral.tasks.notification.repositories.WebhookDeliveryRepository;
import io.julienmetral.tasks.support.IntegrationTest;
import io.julienmetral.tasks.support.Mailpit;
import io.julienmetral.tasks.support.TestClock;
import io.julienmetral.tasks.task.services.TaskReminderService;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Tag;
import mockwebserver3.Dispatcher;
import mockwebserver3.MockResponse;
import mockwebserver3.MockWebServer;
import mockwebserver3.QueueDispatcher;
import mockwebserver3.RecordedRequest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.io.IOException;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.Arrays;
import java.util.EnumSet;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import java.util.stream.Collectors;

import static io.julienmetral.tasks.support.Problems.untypedProblem;
import static io.julienmetral.tasks.support.Problems.validationError;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;
import static org.assertj.core.api.Assertions.assertThatNoException;
import static org.awaitility.Awaitility.await;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.nullValue;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Task events delivered end to end: the API call writes the delivery and its outbox message, RabbitMQ carries it to
 * {@link WebhookDeliveryListener}, and a MockWebServer on 127.0.0.1 receives the signed request through the real
 * HTTP client, address filter and timeouts.
 */
@IntegrationTest
class WebhookDeliveryTests {

    private static final String WEBHOOKS = "/api/v1/users/{id}/webhooks";

    private static final String WEBHOOK = WEBHOOKS + "/{webhookId}";

    private static final String DELIVERIES = WEBHOOK + "/deliveries";

    private static final String REDELIVER = DELIVERIES + "/{deliveryId}/redeliver";

    private static final String TEST_EVENT = WEBHOOK + "/test";

    private static final String TASKS = "/api/v1/tasks";

    private static final String TITLE = "Renew the TLS certificates";

    private static final InetAddress LOOPBACK = address(127, 0, 0, 1);

    private static final Duration DELIVERY_TIMEOUT = Duration.ofSeconds(10);

    // enqueueDue claims every due delivery of the shared database: in 2000, only those of this class are due
    private static final Instant NOW = Instant.parse("2000-03-04T05:06:07Z");

    private static final Instant PINNED_ERA_END = Instant.parse("2001-01-01T00:00:00Z");

    // Far from the era of the reminder tests, whose runs would otherwise remind the tasks of this class
    private static final Instant REMINDER_NOW = Instant.parse("2150-06-01T00:00:00Z");

    private static final String LOST_PAYLOAD = "{\"type\":\"task.assigned\",\"data\":{}}";

    private static final List<Duration> RETRY_SCHEDULE = List.of(
            Duration.ofSeconds(5),
            Duration.ofMinutes(5),
            Duration.ofMinutes(30),
            Duration.ofHours(2),
            Duration.ofHours(5),
            Duration.ofHours(10)
    );

    private static final Duration DISABLE_AFTER = Duration.ofDays(3);

    private static final Duration DELIVERY_RETENTION = Duration.ofDays(30);

    // spring.http.serviceclient.webhooks.read-timeout in the test application.yaml
    private static final Duration READ_TIMEOUT = Duration.ofSeconds(2);

    private static final String DISABLED_EMAIL = "failed every attempt for 3 days";

    // Emails leave through the outbox and RabbitMQ: "no email sent" can only be checked after a grace period
    private static final Duration NO_MAIL_GRACE_PERIOD = Duration.ofSeconds(1);

    private record Endpoint(UUID id, String secret, String url, String[] events) {
    }

    private record CreatedTask(UUID id, String reference) {
    }

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private UserService userService;

    @Autowired
    private UserRetentionQueries retentionQueries;

    @Autowired
    private PasswordEncoder passwordEncoder;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private JsonMapper jsonMapper;

    @Autowired
    private TransactionTemplate transactionTemplate;

    @Autowired
    private TestClock clock;

    @Autowired
    private WebhookDeliveryService deliveryService;

    @Autowired
    private WebhookDeliveryRepository deliveryRepository;

    @Autowired
    private TaskReminderService reminderService;

    @Autowired
    private MeterRegistry meterRegistry;

    @Autowired
    private Mailpit mailpit;

    private final MockWebServer receiver = new MockWebServer();

    // An unexpected request gets an answer at once instead of waiting for the read timeout
    @BeforeEach
    void startReceiver() throws IOException {
        QueueDispatcher dispatcher = new QueueDispatcher();
        dispatcher.setFailFast(new MockResponse.Builder().code(418).build());
        receiver.setDispatcher(dispatcher);
        receiver.start(LOOPBACK, 0);
    }

    @AfterEach
    void stopReceiver() {
        receiver.close();
    }

    // A delivery an earlier test or run left pending in the pinned era would be claimed by this test's enqueueDue
    @BeforeEach
    void finishDeliveriesLeftPendingInThePinnedEra() {
        jdbcTemplate.update(
                "UPDATE webhook_deliveries SET status = 'FAILED', next_attempt_at = NULL "
                        + "WHERE status = 'PENDING' AND next_attempt_at < ?",
                Timestamp.from(PINNED_ERA_END)
        );
    }

    // Signature and headers

    @Test
    void assignmentByAnotherUserReachesTheAssigneesEndpointSignedWithItsSecret() throws Exception {
        User admin = createUser(UserRole.ADMIN);
        User assignee = createUser(UserRole.USER);
        Endpoint endpoint = createEndpoint(assignee, "task.assigned");
        answer(204);

        createTask(admin, assignee);

        RecordedRequest request = nextRequest();
        UUID deliveryId = onlyDeliveryId(endpoint);
        assertThat(request.getMethod()).isEqualTo("POST");
        assertThat(request.getUrl().encodedPath()).isEqualTo("/hooks");
        assertThat(request.getHeaders().get("Content-Type")).startsWith(MediaType.APPLICATION_JSON_VALUE);
        assertThat(request.getHeaders().get("webhook-id")).isEqualTo(deliveryId.toString());
        assertThat(request.getHeaders().get("webhook-signature")).matches("v1,[A-Za-z0-9+/]{43}=");
        assertThatNoException().isThrownBy(() -> verify(endpoint.secret(), request));
        assertThat(json(request).get("type").asString()).isEqualTo("task.assigned");
        assertThat(awaitAttempts(deliveryId, 1).getStatus()).isEqualTo(WebhookDeliveryStatus.DELIVERED);
    }

    @Test
    void signatureIsRejectedWithAnotherSecretOrATamperedBody() throws Exception {
        User admin = createUser(UserRole.ADMIN);
        User assignee = createUser(UserRole.USER);
        Endpoint endpoint = createEndpoint(assignee, "task.assigned");
        Endpoint other = createEndpoint(createUser(UserRole.USER), "task.assigned");
        answer(204);

        createTask(admin, assignee);

        RecordedRequest request = nextRequest();
        String body = request.getBody().utf8();
        Map<String, List<String>> headers = request.getHeaders().toMultimap();
        assertThatExceptionOfType(WebhookVerificationException.class)
                .isThrownBy(() -> new Webhook(other.secret()).verify(body, headers));
        assertThatExceptionOfType(WebhookVerificationException.class)
                .isThrownBy(() -> new Webhook(endpoint.secret()).verify(body.replace(TITLE, "Other"), headers));
        awaitAttempts(onlyDeliveryId(endpoint), 1);
    }

    @Test
    void everySubscribedEndpointGetsItsOwnDeliverySignedWithItsOwnSecret() throws Exception {
        User admin = createUser(UserRole.ADMIN);
        User assignee = createUser(UserRole.USER);
        Endpoint first = createEndpoint(assignee, "task.assigned");
        Endpoint second = createEndpoint(assignee, "task.assigned", "task.overdue");
        answer(204);
        answer(204);

        createTask(admin, assignee);

        List<RecordedRequest> requests = List.of(nextRequest(), nextRequest());
        UUID firstDelivery = onlyDeliveryId(first);
        UUID secondDelivery = onlyDeliveryId(second);
        Map<String, RecordedRequest> byId = requests.stream()
                .collect(Collectors.toMap(request -> request.getHeaders().get("webhook-id"), request -> request));
        assertThat(byId).containsOnlyKeys(firstDelivery.toString(), secondDelivery.toString());
        assertThatNoException().isThrownBy(() -> verify(first.secret(), byId.get(firstDelivery.toString())));
        assertThatNoException().isThrownBy(() -> verify(second.secret(), byId.get(secondDelivery.toString())));
        assertThatExceptionOfType(WebhookVerificationException.class)
                .isThrownBy(() -> verify(second.secret(), byId.get(firstDelivery.toString())));
        awaitAttempts(firstDelivery, 1);
        awaitAttempts(secondDelivery, 1);
    }

    @Test
    void duringASecretRotationBothSecretsSign() throws Exception {
        User admin = createUser(UserRole.ADMIN);
        User assignee = createUser(UserRole.USER);
        Endpoint endpoint = createEndpoint(assignee, "task.assigned");
        String rotated = rotate(assignee, endpoint);
        answer(204);

        createTask(admin, assignee);

        RecordedRequest request = nextRequest();
        assertThat(request.getHeaders().get("webhook-signature").split(" "))
                .hasSize(2)
                .allMatch(signature -> signature.matches("v1,[A-Za-z0-9+/]{43}="));
        assertThatNoException().isThrownBy(() -> verify(rotated, request));
        assertThatNoException().isThrownBy(() -> verify(endpoint.secret(), request));
        awaitAttempts(onlyDeliveryId(endpoint), 1);
    }

    @Test
    void oncePreviousSecretExpiredOnlyTheNewOneSigns() throws Exception {
        User admin = createUser(UserRole.ADMIN);
        User assignee = createUser(UserRole.USER);
        Endpoint endpoint = createEndpoint(assignee, "task.assigned");
        String rotated = rotate(assignee, endpoint);
        jdbcTemplate.update("UPDATE webhook_endpoints SET previous_secret_expires_at = ? WHERE id = ?",
                Timestamp.from(Instant.now().minusSeconds(1)), endpoint.id());
        answer(204);

        createTask(admin, assignee);

        RecordedRequest request = nextRequest();
        assertThat(request.getHeaders().get("webhook-signature").split(" ")).hasSize(1);
        assertThatNoException().isThrownBy(() -> verify(rotated, request));
        assertThatExceptionOfType(WebhookVerificationException.class)
                .isThrownBy(() -> verify(endpoint.secret(), request));
        awaitAttempts(onlyDeliveryId(endpoint), 1);
    }

    // Payload of each event

    @Test
    void assignedPayloadNamesTheTaskAndTheActor() throws Exception {
        clock.set(NOW);
        User admin = createUser(UserRole.ADMIN);
        User assignee = createUser(UserRole.USER);
        Endpoint endpoint = createEndpoint(assignee, "task.assigned");
        answer(204);

        CreatedTask task = createTask(admin, assignee);

        JsonNode payload = json(nextRequest());
        assertThat(payload.propertyNames()).containsExactly("type", "timestamp", "data");
        assertThat(payload).isEqualTo(payload("task.assigned", NOW, task, admin, Map.of()));
        awaitAttempts(onlyDeliveryId(endpoint), 1);
    }

    @Test
    void unassignedPayloadGoesToThePreviousAssignee() throws Exception {
        clock.set(NOW);
        User admin = createUser(UserRole.ADMIN);
        User previous = createUser(UserRole.USER);
        Endpoint endpoint = createEndpoint(previous, "task.unassigned");
        CreatedTask task = createTask(admin, previous);
        answer(204);

        assign(admin, task, createUser(UserRole.USER));

        assertThat(json(nextRequest())).isEqualTo(payload("task.unassigned", NOW, task, admin, Map.of()));
        awaitAttempts(onlyDeliveryId(endpoint), 1);
    }

    @Test
    void cancelledPayloadCarriesTheReason() throws Exception {
        clock.set(NOW);
        User admin = createUser(UserRole.ADMIN);
        User assignee = createUser(UserRole.USER);
        Endpoint endpoint = createEndpoint(assignee, "task.cancelled");
        CreatedTask task = createTask(admin, assignee);
        answer(204);

        mockMvc.perform(post(TASKS + "/{id}/cancel", task.id())
                        .with(asAdmin(admin))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"reason\": \"Customer withdrew the request\"}"))
                .andExpect(status().isOk());

        assertThat(json(nextRequest())).isEqualTo(payload("task.cancelled", NOW, task, admin,
                Map.of("reason", "Customer withdrew the request")));
        awaitAttempts(onlyDeliveryId(endpoint), 1);
    }

    @Test
    void cancelledByAStatusChangePayloadHasANullReason() throws Exception {
        clock.set(NOW);
        User admin = createUser(UserRole.ADMIN);
        User assignee = createUser(UserRole.USER);
        Endpoint endpoint = createEndpoint(assignee, "task.cancelled");
        CreatedTask task = createTask(admin, assignee);
        answer(204);

        mockMvc.perform(patch(TASKS + "/{id}/status", task.id())
                        .with(asAdmin(admin))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"status\": \"CANCELLED\"}"))
                .andExpect(status().isOk());

        Map<String, Object> details = new LinkedHashMap<>();
        details.put("reason", null);
        assertThat(json(nextRequest())).isEqualTo(payload("task.cancelled", NOW, task, admin, details));
        awaitAttempts(onlyDeliveryId(endpoint), 1);
    }

    @Test
    void deletedPayloadNamesTheTaskAndTheActor() throws Exception {
        clock.set(NOW);
        User admin = createUser(UserRole.ADMIN);
        User assignee = createUser(UserRole.USER);
        Endpoint endpoint = createEndpoint(assignee, "task.deleted");
        CreatedTask task = createTask(admin, assignee);
        answer(204);

        mockMvc.perform(delete(TASKS + "/{id}", task.id()).with(asAdmin(admin)))
                .andExpect(status().isNoContent());

        assertThat(json(nextRequest())).isEqualTo(payload("task.deleted", NOW, task, admin, Map.of()));
        awaitAttempts(onlyDeliveryId(endpoint), 1);
    }

    @Test
    void commentedPayloadCarriesTheCommentExcerpt() throws Exception {
        clock.set(NOW);
        User admin = createUser(UserRole.ADMIN);
        User assignee = createUser(UserRole.USER);
        Endpoint endpoint = createEndpoint(assignee, "task.commented");
        CreatedTask task = createTask(admin, assignee);
        answer(204);

        UUID commentId = comment(admin, task, "Looks good to me");

        assertThat(json(nextRequest())).isEqualTo(payload("task.commented", NOW, task, admin,
                Map.of("comment", Map.of("id", commentId.toString(), "excerpt", "Looks good to me"))));
        awaitAttempts(onlyDeliveryId(endpoint), 1);
    }

    @Test
    void mentionedPayloadGoesToTheMentionedUserWithTheMentionRendered() throws Exception {
        clock.set(NOW);
        User admin = createUser(UserRole.ADMIN);
        User mentioned = createUser(UserRole.USER);
        Endpoint endpoint = createEndpoint(mentioned, "task.mentioned");
        CreatedTask task = createTask(admin, null);
        answer(204);

        UUID commentId = comment(admin, task, "Please review, <@" + mentioned.getId() + ">");

        assertThat(json(nextRequest())).isEqualTo(payload("task.mentioned", NOW, task, admin,
                Map.of("comment", Map.of(
                        "id", commentId.toString(),
                        "excerpt", "Please review, @" + mentioned.getDisplayName()))));
        awaitAttempts(onlyDeliveryId(endpoint), 1);
    }

    @Test
    void assigneeWhoIsAlsoMentionedGetsBothTheCommentAndTheMention() throws Exception {
        User admin = createUser(UserRole.ADMIN);
        User assignee = createUser(UserRole.USER);
        Endpoint endpoint = createEndpoint(assignee, "task.commented", "task.mentioned");
        CreatedTask task = createTask(admin, assignee);
        answer(204);
        answer(204);

        comment(admin, task, "<@" + assignee.getId() + "> over to you");

        List<String> types = List.of(json(nextRequest()).get("type").asString(),
                json(nextRequest()).get("type").asString());
        assertThat(types).containsExactlyInAnyOrder("task.commented", "task.mentioned");
        assertThat(deliveryIds(endpoint)).hasSize(2).allSatisfy(id -> awaitAttempts(id, 1));
    }

    @Test
    void dueSoonPayloadHasNoActorAndCarriesTheDueDate() throws Exception {
        clock.set(REMINDER_NOW);
        User assignee = createUser(UserRole.USER);
        Endpoint endpoint = createEndpoint(assignee, "task.due_soon");
        Instant dueAt = REMINDER_NOW.plus(Duration.ofHours(12));
        CreatedTask task = createTask(createUser(UserRole.ADMIN), assignee, dueAt);
        answer(204);

        reminderService.sendDueReminders();

        assertThat(json(nextRequest())).isEqualTo(payload("task.due_soon", REMINDER_NOW, task, null,
                Map.of("dueAt", dueAt.toString())));
        awaitAttempts(onlyDeliveryId(endpoint), 1);
    }

    @Test
    void overduePayloadHasNoActorAndCarriesTheDueDate() throws Exception {
        clock.set(REMINDER_NOW);
        User assignee = createUser(UserRole.USER);
        Endpoint endpoint = createEndpoint(assignee, "task.overdue");
        Instant dueAt = REMINDER_NOW.minus(Duration.ofDays(2));
        CreatedTask task = createTask(createUser(UserRole.ADMIN), assignee, dueAt);
        answer(204);

        reminderService.sendDueReminders();

        assertThat(json(nextRequest())).isEqualTo(payload("task.overdue", REMINDER_NOW, task, null,
                Map.of("dueAt", dueAt.toString())));
        awaitAttempts(onlyDeliveryId(endpoint), 1);
    }

    // Who receives nothing

    @Test
    void nobodyReceivesTheirOwnAction() throws Exception {
        User admin = createUser(UserRole.ADMIN);
        Endpoint endpoint = createEndpoint(admin, "task.assigned");

        createTask(admin, admin);

        assertThat(deliveryIds(endpoint)).isEmpty();
        assertThat(receiver.getRequestCount()).isZero();
    }

    @Test
    void disabledRecipientReceivesNothing() throws Exception {
        User admin = createUser(UserRole.ADMIN);
        User assignee = createUser(UserRole.USER);
        Endpoint endpoint = createEndpoint(assignee, "task.cancelled");
        CreatedTask task = createTask(admin, assignee);
        updateUser(assignee, user -> user.setEnabled(false));

        cancel(admin, task);

        assertThat(deliveryIds(endpoint)).isEmpty();
        assertThat(receiver.getRequestCount()).isZero();
    }

    @Test
    void unverifiedRecipientReceivesNothing() throws Exception {
        User admin = createUser(UserRole.ADMIN);
        User assignee = createUser(UserRole.USER);
        Endpoint endpoint = createEndpoint(assignee, "task.cancelled");
        CreatedTask task = createTask(admin, assignee);
        updateUser(assignee, user -> user.setEmailVerifiedAt(null));

        cancel(admin, task);

        assertThat(deliveryIds(endpoint)).isEmpty();
        assertThat(receiver.getRequestCount()).isZero();
    }

    @Test
    void onlyTheEndpointsSubscribedToTheEventReceiveIt() throws Exception {
        User admin = createUser(UserRole.ADMIN);
        User assignee = createUser(UserRole.USER);
        Endpoint unsubscribed = createEndpoint(assignee, "task.overdue", "task.unassigned");
        Endpoint subscribed = createEndpoint(assignee, "task.assigned");
        answer(204);

        createTask(admin, assignee);

        assertThat(deliveryIds(unsubscribed)).isEmpty();
        UUID deliveryId = onlyDeliveryId(subscribed);
        assertThat(nextRequest().getHeaders().get("webhook-id")).isEqualTo(deliveryId.toString());
        awaitAttempts(deliveryId, 1);
    }

    @Test
    void pausedEndpointReceivesNothing() throws Exception {
        User admin = createUser(UserRole.ADMIN);
        User assignee = createUser(UserRole.USER);
        Endpoint paused = createEndpoint(assignee, "task.assigned");
        Endpoint enabled = createEndpoint(assignee, "task.assigned");
        pause(assignee, paused);
        answer(204);

        createTask(admin, assignee);

        assertThat(deliveryIds(paused)).isEmpty();
        UUID deliveryId = onlyDeliveryId(enabled);
        assertThat(nextRequest().getHeaders().get("webhook-id")).isEqualTo(deliveryId.toString());
        awaitAttempts(deliveryId, 1);
    }

    // Outcome of an attempt

    @Test
    void successfulAttemptMarksTheDeliveryDelivered() throws Exception {
        clock.set(NOW);
        User assignee = createUser(UserRole.USER);
        Endpoint endpoint = createEndpoint(assignee, "task.assigned");
        answer(204);

        createTask(createUser(UserRole.ADMIN), assignee);

        WebhookDelivery delivered = awaitAttempts(onlyDeliveryId(endpoint), 1);
        assertThat(delivered.getStatus()).isEqualTo(WebhookDeliveryStatus.DELIVERED);
        assertThat(delivered.getEvent()).isEqualTo(WebhookEvent.TASK_ASSIGNED);
        assertThat(delivered.getLastStatusCode()).isEqualTo(204);
        assertThat(delivered.getLastError()).isNull();
        assertThat(delivered.getLastAttemptAt()).isEqualTo(NOW);
        assertThat(delivered.getDeliveredAt()).isEqualTo(NOW);
        assertThat(delivered.getNextAttemptAt()).isNull();
        assertThat(delivered.getCreatedAt()).isEqualTo(NOW);
    }

    @Test
    void storedPayloadIsExactlyTheSignedBody() throws Exception {
        User assignee = createUser(UserRole.USER);
        Endpoint endpoint = createEndpoint(assignee, "task.assigned");
        answer(204);

        createTask(createUser(UserRole.ADMIN), assignee);

        String body = nextRequest().getBody().utf8();
        assertThat(awaitAttempts(onlyDeliveryId(endpoint), 1).getPayload()).isEqualTo(body);
    }

    @Test
    void serverErrorLeavesTheDeliveryPendingForARetryAboutFiveSecondsLater() throws Exception {
        clock.set(NOW);
        User assignee = createUser(UserRole.USER);
        Endpoint endpoint = createEndpoint(assignee, "task.assigned");
        answer(500);

        createTask(createUser(UserRole.ADMIN), assignee);

        WebhookDelivery pending = awaitAttempts(onlyDeliveryId(endpoint), 1);
        assertThat(pending.getStatus()).isEqualTo(WebhookDeliveryStatus.PENDING);
        assertThat(pending.getLastStatusCode()).isEqualTo(500);
        assertThat(pending.getLastError()).isNull();
        assertThat(pending.getLastAttemptAt()).isEqualTo(NOW);
        assertThat(pending.getDeliveredAt()).isNull();
        assertJittered(Duration.between(NOW, pending.getNextAttemptAt()), RETRY_SCHEDULE.getFirst());
    }

    @Test
    void dueRetryIsSentAgainWithTheSameWebhookIdAndANewTimestamp() throws Exception {
        clock.set(NOW);
        User assignee = createUser(UserRole.USER);
        Endpoint endpoint = createEndpoint(assignee, "task.assigned");
        answer(500);
        answer(204);
        createTask(createUser(UserRole.ADMIN), assignee);
        UUID deliveryId = onlyDeliveryId(endpoint);
        RecordedRequest first = nextRequest();
        Instant retryAt = awaitAttempts(deliveryId, 1).getNextAttemptAt();
        clock.set(retryAt);

        assertThat(deliveryService.enqueueDue()).isPositive();

        RecordedRequest retry = nextRequest();
        assertThat(retry.getHeaders().get("webhook-id")).isEqualTo(deliveryId.toString());
        assertThat(first.getHeaders().get("webhook-id")).isEqualTo(deliveryId.toString());
        assertThat(first.getHeaders().get("webhook-timestamp")).isEqualTo(String.valueOf(NOW.getEpochSecond()));
        assertThat(retry.getHeaders().get("webhook-timestamp"))
                .isEqualTo(String.valueOf(retryAt.getEpochSecond()));
        assertThat(retry.getHeaders().get("webhook-signature"))
                .isNotEqualTo(first.getHeaders().get("webhook-signature"));
        assertThat(retry.getBody()).isEqualTo(first.getBody());
        WebhookDelivery delivered = awaitAttempts(deliveryId, 2);
        assertThat(delivered.getStatus()).isEqualTo(WebhookDeliveryStatus.DELIVERED);
        assertThat(delivered.getDeliveredAt()).isEqualTo(retryAt);
        assertThat(delivered.getLastStatusCode()).isEqualTo(204);
    }

    @Test
    void retryIsNotClaimedBeforeItIsDue() throws Exception {
        clock.set(NOW);
        User assignee = createUser(UserRole.USER);
        Endpoint endpoint = createEndpoint(assignee, "task.assigned");
        answer(500);
        createTask(createUser(UserRole.ADMIN), assignee);
        UUID deliveryId = onlyDeliveryId(endpoint);
        Instant retryAt = awaitAttempts(deliveryId, 1).getNextAttemptAt();
        clock.set(retryAt.minusMillis(1));

        deliveryService.enqueueDue();

        assertThat(delivery(deliveryId).getNextAttemptAt()).isEqualTo(retryAt);
        assertThat(receiver.getRequestCount()).isOne();
    }

    @Test
    void deliveryWhoseMessageWasLostIsSentOnceItsLeaseEnds() throws Exception {
        clock.set(NOW);
        Endpoint endpoint = createEndpoint(createUser(UserRole.USER), "task.assigned");
        UUID deliveryId = insertPendingDelivery(endpoint, NOW.plus(Duration.ofMinutes(5)));
        answer(204);

        clock.set(NOW.plus(Duration.ofMinutes(5)).minusMillis(1));
        deliveryService.enqueueDue();
        assertThat(delivery(deliveryId).getNextAttemptAt()).isEqualTo(NOW.plus(Duration.ofMinutes(5)));
        clock.set(NOW.plus(Duration.ofMinutes(5)));
        deliveryService.enqueueDue();

        RecordedRequest request = nextRequest();
        assertThat(request.getHeaders().get("webhook-id")).isEqualTo(deliveryId.toString());
        assertThat(request.getBody().utf8()).isEqualTo(LOST_PAYLOAD);
        assertThat(awaitAttempts(deliveryId, 1).getStatus()).isEqualTo(WebhookDeliveryStatus.DELIVERED);
    }

    @Test
    void claimedDeliveryIsNotClaimedAgainWhileItsAttemptRuns() throws Exception {
        clock.set(NOW);
        Endpoint endpoint = createEndpoint(createUser(UserRole.USER), "task.assigned");
        UUID deliveryId = insertPendingDelivery(endpoint, NOW);
        receiver.enqueue(new MockResponse.Builder().code(204).headersDelay(1, TimeUnit.SECONDS).build());
        answer(204);

        deliveryService.enqueueDue();
        assertThat(delivery(deliveryId).getNextAttemptAt()).isEqualTo(NOW.plus(Duration.ofMinutes(5)));
        deliveryService.enqueueDue();

        assertThat(awaitAttempts(deliveryId, 1).getStatus()).isEqualTo(WebhookDeliveryStatus.DELIVERED);
        nextRequest();
        assertThat(receiver.takeRequest(1, TimeUnit.SECONDS)).isNull();
        assertThat(delivery(deliveryId).getAttempts()).isOne();
    }

    @Test
    void deliveryFailsAfterSixRetriesSpacedByTheSchedule() throws Exception {
        clock.set(NOW);
        User assignee = createUser(UserRole.USER);
        Endpoint endpoint = createEndpoint(assignee, "task.assigned");
        for (int i = 0; i < 8; i++) {
            answer(500);
        }
        createTask(createUser(UserRole.ADMIN), assignee);
        UUID deliveryId = onlyDeliveryId(endpoint);

        for (int attempts = 1; attempts <= RETRY_SCHEDULE.size(); attempts++) {
            WebhookDelivery pending = awaitAttempts(deliveryId, attempts);
            assertThat(pending.getStatus()).isEqualTo(WebhookDeliveryStatus.PENDING);
            assertJittered(Duration.between(pending.getLastAttemptAt(), pending.getNextAttemptAt()),
                    RETRY_SCHEDULE.get(attempts - 1));
            clock.set(pending.getNextAttemptAt());
            assertThat(deliveryService.enqueueDue()).isPositive();
        }

        WebhookDelivery failed = awaitAttempts(deliveryId, 7);
        assertThat(failed.getStatus()).isEqualTo(WebhookDeliveryStatus.FAILED);
        assertThat(failed.getAttempts()).isEqualTo(7);
        assertThat(failed.getLastStatusCode()).isEqualTo(500);
        assertThat(failed.getNextAttemptAt()).isNull();
        assertThat(failed.getDeliveredAt()).isNull();
        Set<String> webhookIds = new HashSet<>();
        for (int i = 0; i < 7; i++) {
            webhookIds.add(nextRequest().getHeaders().get("webhook-id"));
        }
        assertThat(webhookIds).containsExactly(deliveryId.toString());

        clock.advance(Duration.ofDays(1));
        deliveryService.enqueueDue();
        assertThat(receiver.takeRequest(1, TimeUnit.SECONDS)).isNull();
        assertThat(delivery(deliveryId).getAttempts()).isEqualTo(7);
    }

    @Test
    void goneDisablesTheEndpointAndFailsTheDelivery() throws Exception {
        clock.set(NOW);
        User admin = createUser(UserRole.ADMIN);
        User assignee = createUser(UserRole.USER);
        Endpoint endpoint = createEndpoint(assignee, "task.assigned");
        answer(410);

        createTask(admin, assignee);

        WebhookDelivery failed = awaitAttempts(onlyDeliveryId(endpoint), 1);
        assertThat(failed.getStatus()).isEqualTo(WebhookDeliveryStatus.FAILED);
        assertThat(failed.getLastStatusCode()).isEqualTo(410);
        assertThat(failed.getLastError()).isNull();
        assertThat(failed.getNextAttemptAt()).isNull();
        mockMvc.perform(get(WEBHOOK, assignee.getId(), endpoint.id()).with(asUser(assignee)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.enabled").value(false))
                .andExpect(jsonPath("$.disabledReason").value("GONE"))
                .andExpect(jsonPath("$.disabledAt").value(NOW.toString()));

        createTask(admin, assignee);

        assertThat(deliveryIds(endpoint)).hasSize(1);
    }

    @Test
    void pendingDeliveryOfAnEndpointPausedSinceFailsWithoutARequest() throws Exception {
        clock.set(NOW);
        User assignee = createUser(UserRole.USER);
        Endpoint endpoint = createEndpoint(assignee, "task.assigned");
        answer(500);
        createTask(createUser(UserRole.ADMIN), assignee);
        UUID deliveryId = onlyDeliveryId(endpoint);
        clock.set(awaitAttempts(deliveryId, 1).getNextAttemptAt());
        pause(assignee, endpoint);

        assertThat(deliveryService.enqueueDue()).isPositive();

        WebhookDelivery failed = awaitFinished(deliveryId);
        assertThat(failed.getStatus()).isEqualTo(WebhookDeliveryStatus.FAILED);
        assertThat(failed.getLastError()).isEqualTo("EndpointDisabled");
        assertThat(failed.getAttempts()).isOne();
        assertThat(failed.getNextAttemptAt()).isNull();
        assertThat(receiver.getRequestCount()).isOne();
    }

    @Test
    void pendingDeliveryToAnAccountDisabledSinceFailsWithoutARequest() throws Exception {
        clock.set(NOW);
        User assignee = createUser(UserRole.USER);
        User admin = createUser(UserRole.ADMIN);
        Endpoint endpoint = createEndpoint(assignee, "task.assigned");
        answer(500);
        createTask(admin, assignee);
        UUID deliveryId = onlyDeliveryId(endpoint);
        clock.set(awaitAttempts(deliveryId, 1).getNextAttemptAt());
        mockMvc.perform(post("/api/v1/users/{id}/disable", assignee.getId()).with(asAdmin(admin)))
                .andExpect(status().isNoContent());

        assertThat(deliveryService.enqueueDue()).isPositive();

        WebhookDelivery failed = awaitFinished(deliveryId);
        assertThat(failed.getStatus()).isEqualTo(WebhookDeliveryStatus.FAILED);
        assertThat(failed.getLastError()).isEqualTo("AccountNotActive");
        assertThat(failed.getAttempts()).isOne();
        assertThat(receiver.getRequestCount()).isOne();
    }

    @ParameterizedTest
    @CsvSource(delimiter = '|', value = {
            "429 | 120                           | PT2M  | PT2M",
            "503 | 86400                         | PT10H | PT10H",
            "503 | 1                             | PT4S  | PT6S",
            "429 | Sat, 04 Mar 2000 05:00:00 GMT | PT4S  | PT6S",
            "503 | Sat, 04 Mar 2000 07:06:07 GMT | PT2H  | PT2H",
            "429 | Wed, 21 Oct 2015 07:28:00 GMT | PT10H | PT10H"
    })
    void retryAfterPostponesTheRetryOnlyWhenLongerThanTheScheduledDelay(
            int status,
            String retryAfter,
            Duration earliest,
            Duration latest
    ) throws Exception {
        clock.set(NOW);
        User assignee = createUser(UserRole.USER);
        Endpoint endpoint = createEndpoint(assignee, "task.assigned");
        receiver.enqueue(new MockResponse.Builder().code(status).setHeader("Retry-After", retryAfter).build());

        createTask(createUser(UserRole.ADMIN), assignee);

        WebhookDelivery pending = awaitAttempts(onlyDeliveryId(endpoint), 1);
        assertThat(pending.getStatus()).isEqualTo(WebhookDeliveryStatus.PENDING);
        assertThat(pending.getLastStatusCode()).isEqualTo(status);
        assertThat(Duration.between(NOW, pending.getNextAttemptAt())).isBetween(earliest, latest);
    }

    @Test
    void serviceUnavailableWithRetryAfterIsSentOnlyOnce() throws Exception {
        User assignee = createUser(UserRole.USER);
        Endpoint endpoint = createEndpoint(assignee, "task.assigned");
        receiver.enqueue(new MockResponse.Builder().code(503).setHeader("Retry-After", "1").build());
        answer(204);

        createTask(createUser(UserRole.ADMIN), assignee);

        WebhookDelivery pending = awaitAttempts(onlyDeliveryId(endpoint), 1);
        assertThat(pending.getStatus()).isEqualTo(WebhookDeliveryStatus.PENDING);
        assertThat(pending.getLastStatusCode()).isEqualTo(503);
        assertThat(receiver.getRequestCount()).isOne();
    }

    @Test
    void redirectIsNotFollowedAndCountsAsAFailedAttempt() throws Exception {
        User assignee = createUser(UserRole.USER);
        Endpoint endpoint = createEndpoint(assignee, "task.assigned");
        receiver.enqueue(new MockResponse.Builder()
                .code(307)
                .setHeader("Location", receiverUrl("/moved"))
                .build());
        answer(204);

        createTask(createUser(UserRole.ADMIN), assignee);

        WebhookDelivery pending = awaitAttempts(onlyDeliveryId(endpoint), 1);
        assertThat(pending.getStatus()).isEqualTo(WebhookDeliveryStatus.PENDING);
        assertThat(pending.getLastStatusCode()).isEqualTo(307);
        assertThat(receiver.getRequestCount()).isOne();
    }

    @Test
    void cookieSetByTheReceiverIsNotSentBack() throws Exception {
        clock.set(NOW);
        User assignee = createUser(UserRole.USER);
        Endpoint endpoint = createEndpoint(assignee, "task.assigned");
        receiver.enqueue(new MockResponse.Builder()
                .code(500)
                .setHeader("Set-Cookie", "session=steer-the-next-call; Path=/")
                .build());
        answer(204);
        createTask(createUser(UserRole.ADMIN), assignee);
        UUID deliveryId = onlyDeliveryId(endpoint);
        nextRequest();
        clock.set(awaitAttempts(deliveryId, 1).getNextAttemptAt());

        deliveryService.enqueueDue();

        assertThat(nextRequest().getHeaders().get("Cookie")).isNull();
        awaitAttempts(deliveryId, 2);
    }

    @Test
    void privateDestinationWrittenInTheDatabaseFailsAtOnceWithoutARequest() throws Exception {
        User assignee = createUser(UserRole.USER);
        Endpoint endpoint = createEndpoint(assignee, "task.assigned");
        // 127.0.0.2 is loopback but outside the allowed 127.0.0.1/32: the URL policy would refuse it on the API
        try (MockWebServer privateReceiver = new MockWebServer()) {
            privateReceiver.start(address(127, 0, 0, 2), 0);
            jdbcTemplate.update("UPDATE webhook_endpoints SET url = ? WHERE id = ?",
                    "http://127.0.0.2:" + privateReceiver.getPort() + "/hooks", endpoint.id());

            createTask(createUser(UserRole.ADMIN), assignee);

            WebhookDelivery failed = awaitFinished(onlyDeliveryId(endpoint));
            assertThat(failed.getStatus()).isEqualTo(WebhookDeliveryStatus.FAILED);
            assertThat(failed.getLastError()).isEqualTo("DestinationNotAllowed");
            assertThat(failed.getAttempts()).isOne();
            assertThat(failed.getLastStatusCode()).isNull();
            assertThat(privateReceiver.getRequestCount()).isZero();
        }
    }

    @Test
    void receiverSlowerThanTheReadTimeoutIsATimeout() throws Exception {
        User assignee = createUser(UserRole.USER);
        Endpoint endpoint = createEndpoint(assignee, "task.assigned");
        receiver.enqueue(new MockResponse.Builder().code(204).headersDelay(5, TimeUnit.SECONDS).build());

        createTask(createUser(UserRole.ADMIN), assignee);

        WebhookDelivery pending = awaitAttempts(onlyDeliveryId(endpoint), 1);
        assertThat(pending.getStatus()).isEqualTo(WebhookDeliveryStatus.PENDING);
        assertThat(pending.getLastError()).isEqualTo("Timeout");
        assertThat(pending.getLastStatusCode()).isNull();
        assertThat(receiver.getRequestCount()).isOne();
    }

    @Test
    void receiverThatRefusesTheConnectionIsAConnectionFailure() throws Exception {
        User assignee = createUser(UserRole.USER);
        int closedPort;
        try (ServerSocket socket = new ServerSocket(0, 1, LOOPBACK)) {
            closedPort = socket.getLocalPort();
        }
        Endpoint endpoint = createEndpointAt(assignee, "http://127.0.0.1:" + closedPort + "/hooks", "task.assigned");

        createTask(createUser(UserRole.ADMIN), assignee);

        WebhookDelivery pending = awaitAttempts(onlyDeliveryId(endpoint), 1);
        assertThat(pending.getStatus()).isEqualTo(WebhookDeliveryStatus.PENDING);
        assertThat(pending.getLastError()).isEqualTo("ConnectionFailed");
        assertThat(pending.getLastStatusCode()).isNull();
    }

    @Test
    void statusWithoutASpringConstantIsRecordedAsAFailedAttempt() throws Exception {
        User assignee = createUser(UserRole.USER);
        Endpoint endpoint = createEndpoint(assignee, "task.assigned");
        // 522 is what Cloudflare answers when it cannot reach the origin
        answer(522);

        createTask(createUser(UserRole.ADMIN), assignee);

        WebhookDelivery pending = awaitAttempts(onlyDeliveryId(endpoint), 1);
        assertThat(pending.getStatus()).isEqualTo(WebhookDeliveryStatus.PENDING);
        assertThat(pending.getLastStatusCode()).isEqualTo(522);
    }

    @Test
    void duplicateMessageForADeliveredDeliverySendsNothing() throws Exception {
        User assignee = createUser(UserRole.USER);
        Endpoint endpoint = createEndpoint(assignee, "task.assigned");
        answer(204);
        createTask(createUser(UserRole.ADMIN), assignee);
        UUID deliveryId = onlyDeliveryId(endpoint);
        awaitAttempts(deliveryId, 1);

        deliveryService.deliver(deliveryId);

        assertThat(receiver.getRequestCount()).isOne();
        assertThat(delivery(deliveryId).getAttempts()).isOne();
    }

    @Test
    void deliveriesAreCountedByOutcome() throws Exception {
        User admin = createUser(UserRole.ADMIN);
        User assignee = createUser(UserRole.USER);
        Endpoint endpoint = createEndpoint(assignee, "task.assigned");
        double delivered = deliveryCount("delivered");
        double retried = deliveryCount("retried");
        double failed = deliveryCount("failed");

        for (int status : new int[]{204, 500, 410}) {
            answer(status);
            createTask(admin, assignee);
            awaitAttempts(deliveryIds(endpoint).getLast(), 1);
        }

        assertThat(deliveryCount("delivered") - delivered).isEqualTo(1);
        assertThat(deliveryCount("retried") - retried).isEqualTo(1);
        assertThat(deliveryCount("failed") - failed).isEqualTo(1);
    }

    @Test
    void webhookCallsAreMeasuredWithoutTheirDestination() throws Exception {
        User assignee = createUser(UserRole.USER);
        Endpoint endpoint = createEndpoint(assignee, "task.assigned");
        answer(204);

        createTask(createUser(UserRole.ADMIN), assignee);

        awaitAttempts(onlyDeliveryId(endpoint), 1);
        assertThat(meterRegistry.find("http.client.requests").tag("client.name", "webhook").timers()).isNotEmpty();
        assertThat(meterRegistry.find("http.client.requests").meters())
                .flatExtracting(meter -> meter.getId().getTags())
                .extracting(Tag::getValue)
                .noneMatch(value -> value.contains("127.0.0.1") || value.contains("/hooks"));
    }

    // Deliveries in the API

    @Test
    void deliveriesArePagedNewestFirst() throws Exception {
        User admin = createUser(UserRole.ADMIN);
        User assignee = createUser(UserRole.USER);
        Endpoint endpoint = createEndpoint(assignee, "task.assigned");
        for (int minute = 0; minute < 3; minute++) {
            answer(204);
            clock.set(NOW.plus(Duration.ofMinutes(minute)));
            createTask(admin, assignee);
        }
        List<UUID> oldestFirst = deliveryIds(endpoint);
        oldestFirst.forEach(id -> awaitAttempts(id, 1));

        mockMvc.perform(get(DELIVERIES, assignee.getId(), endpoint.id())
                        .param("size", "2")
                        .with(asUser(assignee)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content[*].id")
                        .value(contains(oldestFirst.get(2).toString(), oldestFirst.get(1).toString())))
                .andExpect(jsonPath("$.page.size").value(2))
                .andExpect(jsonPath("$.page.number").value(0))
                .andExpect(jsonPath("$.page.totalElements").value(3))
                .andExpect(jsonPath("$.page.totalPages").value(2));
        mockMvc.perform(get(DELIVERIES, assignee.getId(), endpoint.id())
                        .param("size", "2")
                        .param("page", "1")
                        .with(asUser(assignee)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content[*].id").value(contains(oldestFirst.getFirst().toString())));
    }

    @Test
    void deliveredDeliveryShowsItsAttemptButNoNextAttempt() throws Exception {
        clock.set(NOW);
        User assignee = createUser(UserRole.USER);
        Endpoint endpoint = createEndpoint(assignee, "task.assigned");
        answer(204);
        createTask(createUser(UserRole.ADMIN), assignee);
        UUID deliveryId = onlyDeliveryId(endpoint);
        awaitAttempts(deliveryId, 1);

        mockMvc.perform(get(DELIVERIES, assignee.getId(), endpoint.id()).with(asUser(assignee)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content[0].id").value(deliveryId.toString()))
                .andExpect(jsonPath("$.content[0].event").value("task.assigned"))
                .andExpect(jsonPath("$.content[0].status").value("DELIVERED"))
                .andExpect(jsonPath("$.content[0].attempts").value(1))
                .andExpect(jsonPath("$.content[0].lastStatusCode").value(204))
                .andExpect(jsonPath("$.content[0].lastError").value(nullValue()))
                .andExpect(jsonPath("$.content[0].nextAttemptAt").value(nullValue()))
                .andExpect(jsonPath("$.content[0].lastAttemptAt").value(NOW.toString()))
                .andExpect(jsonPath("$.content[0].deliveredAt").value(NOW.toString()))
                .andExpect(jsonPath("$.content[0].createdAt").value(NOW.toString()))
                .andExpect(jsonPath("$.content[0].payload").doesNotExist());
    }

    @Test
    void pendingDeliveryShowsWhenItIsRetried() throws Exception {
        clock.set(NOW);
        User assignee = createUser(UserRole.USER);
        Endpoint endpoint = createEndpoint(assignee, "task.assigned");
        answer(503);
        createTask(createUser(UserRole.ADMIN), assignee);
        WebhookDelivery pending = awaitAttempts(onlyDeliveryId(endpoint), 1);

        mockMvc.perform(get(DELIVERIES, assignee.getId(), endpoint.id()).with(asUser(assignee)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content[0].status").value("PENDING"))
                .andExpect(jsonPath("$.content[0].lastStatusCode").value(503))
                .andExpect(jsonPath("$.content[0].nextAttemptAt").value(pending.getNextAttemptAt().toString()))
                .andExpect(jsonPath("$.content[0].deliveredAt").value(nullValue()));
    }

    @Test
    void adminListsTheDeliveriesOfAnotherUsersWebhook() throws Exception {
        User admin = createUser(UserRole.ADMIN);
        User assignee = createUser(UserRole.USER);
        Endpoint endpoint = createEndpoint(assignee, "task.assigned");
        answer(204);
        createTask(admin, assignee);
        UUID deliveryId = onlyDeliveryId(endpoint);
        awaitAttempts(deliveryId, 1);

        mockMvc.perform(get(DELIVERIES, assignee.getId(), endpoint.id()).with(asAdmin(admin)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content[*].id").value(contains(deliveryId.toString())));
    }

    @Test
    void userCannotListTheDeliveriesOfAnotherUsersWebhook() throws Exception {
        User owner = createUser(UserRole.USER);
        User intruder = createUser(UserRole.USER);
        Endpoint endpoint = createEndpoint(owner, "task.assigned");

        mockMvc.perform(get(DELIVERIES, owner.getId(), endpoint.id()).with(asUser(intruder)))
                .andExpect(status().isForbidden());
        mockMvc.perform(get(DELIVERIES, intruder.getId(), endpoint.id()).with(asUser(intruder)))
                .andExpect(untypedProblem(404, "Not Found"))
                .andExpect(jsonPath("$.detail").value("Webhook not found with id: " + endpoint.id()));
    }

    @Test
    void sortByAnUnknownPropertyIsAValidationErrorOnTheSortParameter() throws Exception {
        User user = createUser(UserRole.USER);
        Endpoint endpoint = createEndpoint(user, "task.assigned");

        mockMvc.perform(get(DELIVERIES, user.getId(), endpoint.id())
                        .param("sort", "nonExistent,asc")
                        .with(asUser(user)))
                .andExpect(validationError())
                .andExpect(jsonPath("$.errors[0].parameter").value("sort"));
    }

    @Test
    void deliveriesOfAnUnknownWebhookAreNotFound() throws Exception {
        User user = createUser(UserRole.USER);
        UUID unknown = UUID.randomUUID();

        mockMvc.perform(get(DELIVERIES, user.getId(), unknown).with(asUser(user)))
                .andExpect(untypedProblem(404, "Not Found"))
                .andExpect(jsonPath("$.detail").value("Webhook not found with id: " + unknown));
    }

    // Test events

    @Test
    void testEventReachesTheReceiverSignedWithTheEndpointsSecret() throws Exception {
        User owner = createUser(UserRole.USER);
        Endpoint endpoint = createEndpoint(owner, "task.assigned");
        answer(204);

        sendTestEvent(owner, endpoint).andExpect(status().isOk());

        RecordedRequest request = nextRequest();
        assertThat(request.getMethod()).isEqualTo("POST");
        assertThat(request.getUrl().encodedPath()).isEqualTo("/hooks");
        assertThat(request.getHeaders().get("Content-Type")).startsWith(MediaType.APPLICATION_JSON_VALUE);
        assertThat(request.getHeaders().get("webhook-signature")).matches("v1,[A-Za-z0-9+/]{43}=");
        assertThatNoException().isThrownBy(() -> verify(endpoint.secret(), request));
    }

    @Test
    void testEventPayloadNamesItsTypeTheTimeAndTheWebhook() throws Exception {
        clock.set(NOW);
        User owner = createUser(UserRole.USER);
        Endpoint endpoint = createEndpoint(owner, "task.assigned");
        answer(204);

        sendTestEvent(owner, endpoint).andExpect(status().isOk());

        RecordedRequest request = nextRequest();
        JsonNode payload = json(request);
        assertThat(payload.propertyNames()).containsExactly("type", "timestamp", "data");
        assertThat(payload).isEqualTo(jsonMapper.valueToTree(Map.of(
                "type", "webhook.test",
                "timestamp", NOW.toString(),
                "data", Map.of("webhookId", endpoint.id().toString()))));
        assertThat(request.getHeaders().get("webhook-timestamp")).isEqualTo(String.valueOf(NOW.getEpochSecond()));
    }

    @Test
    void everyTestEventHasAWebhookIdOfItsOwn() throws Exception {
        User owner = createUser(UserRole.USER);
        Endpoint endpoint = createEndpoint(owner, "task.assigned");
        answer(204);
        answer(204);

        sendTestEvent(owner, endpoint).andExpect(status().isOk());
        sendTestEvent(owner, endpoint).andExpect(status().isOk());

        UUID first = UUID.fromString(nextRequest().getHeaders().get("webhook-id"));
        UUID second = UUID.fromString(nextRequest().getHeaders().get("webhook-id"));
        assertThat(first).isNotEqualTo(second);
    }

    @Test
    void acceptedTestEventIsReportedDeliveredWithItsStatusAndDurationAndRecordsNoDelivery() throws Exception {
        User owner = createUser(UserRole.USER);
        Endpoint endpoint = createEndpoint(owner, "task.assigned");
        receiver.enqueue(new MockResponse.Builder().code(202).headersDelay(300, TimeUnit.MILLISECONDS).build());

        JsonNode result = json(sendTestEvent(owner, endpoint)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.delivered").value(true))
                .andExpect(jsonPath("$.statusCode").value(202))
                .andExpect(jsonPath("$.error").value(nullValue())));

        assertThat(result.get("durationMillis").asLong()).isBetween(300L, READ_TIMEOUT.toMillis());
        nextRequest();
        assertThat(deliveryIds(endpoint)).isEmpty();
    }

    @Test
    void testEventReachesAPausedEndpointAndLeavesItPaused() throws Exception {
        User owner = createUser(UserRole.USER);
        Endpoint endpoint = createEndpoint(owner, "task.assigned");
        pause(owner, endpoint);
        answer(200);

        sendTestEvent(owner, endpoint)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.delivered").value(true))
                .andExpect(jsonPath("$.statusCode").value(200));

        RecordedRequest request = nextRequest();
        assertThatNoException().isThrownBy(() -> verify(endpoint.secret(), request));
        mockMvc.perform(get(WEBHOOK, owner.getId(), endpoint.id()).with(asUser(owner)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.enabled").value(false))
                .andExpect(jsonPath("$.disabledReason").value("OWNER"));
    }

    @Test
    void testEventDuringASecretRotationIsSignedWithBothSecrets() throws Exception {
        User owner = createUser(UserRole.USER);
        Endpoint endpoint = createEndpoint(owner, "task.assigned");
        String rotated = rotate(owner, endpoint);
        answer(204);

        sendTestEvent(owner, endpoint).andExpect(status().isOk());

        RecordedRequest request = nextRequest();
        assertThat(request.getHeaders().get("webhook-signature").split(" ")).hasSize(2);
        assertThatNoException().isThrownBy(() -> verify(rotated, request));
        assertThatNoException().isThrownBy(() -> verify(endpoint.secret(), request));
    }

    @Test
    void rejectedTestEventIsReportedWithItsStatusAndDoesNotMarkTheEndpointFailing() throws Exception {
        User owner = createUser(UserRole.USER);
        Endpoint endpoint = createEndpoint(owner, "task.assigned");
        answer(500);

        sendTestEvent(owner, endpoint)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.delivered").value(false))
                .andExpect(jsonPath("$.statusCode").value(500))
                .andExpect(jsonPath("$.error").value(nullValue()));

        nextRequest();
        assertThat(deliveryIds(endpoint)).isEmpty();
        assertThat(failingSince(endpoint)).isNull();
    }

    @Test
    void goneAnswerToATestEventLeavesTheEndpointEnabled() throws Exception {
        User owner = createUser(UserRole.USER);
        Endpoint endpoint = createEndpoint(owner, "task.assigned");
        answer(410);

        sendTestEvent(owner, endpoint)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.delivered").value(false))
                .andExpect(jsonPath("$.statusCode").value(410));

        mockMvc.perform(get(WEBHOOK, owner.getId(), endpoint.id()).with(asUser(owner)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.enabled").value(true))
                .andExpect(jsonPath("$.disabledReason").value(nullValue()));
    }

    @Test
    void testEventToASlowReceiverIsReportedAsATimeoutWithoutAStatus() throws Exception {
        User owner = createUser(UserRole.USER);
        Endpoint endpoint = createEndpoint(owner, "task.assigned");
        receiver.enqueue(new MockResponse.Builder().code(204).headersDelay(5, TimeUnit.SECONDS).build());

        JsonNode result = json(sendTestEvent(owner, endpoint)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.delivered").value(false))
                .andExpect(jsonPath("$.statusCode").value(nullValue()))
                .andExpect(jsonPath("$.error").value("Timeout")));

        assertThat(result.get("durationMillis").asLong()).isBetween(READ_TIMEOUT.toMillis(), 4_999L);
    }

    @Test
    void testEventToAPrivateDestinationWrittenInTheDatabaseIsNotSent() throws Exception {
        User owner = createUser(UserRole.USER);
        Endpoint endpoint = createEndpoint(owner, "task.assigned");
        // 127.0.0.2 is loopback but outside the allowed 127.0.0.1/32: the URL policy would refuse it on the API
        try (MockWebServer privateReceiver = new MockWebServer()) {
            privateReceiver.start(address(127, 0, 0, 2), 0);
            jdbcTemplate.update("UPDATE webhook_endpoints SET url = ? WHERE id = ?",
                    "http://127.0.0.2:" + privateReceiver.getPort() + "/hooks", endpoint.id());

            sendTestEvent(owner, endpoint)
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.delivered").value(false))
                    .andExpect(jsonPath("$.statusCode").value(nullValue()))
                    .andExpect(jsonPath("$.error").value("DestinationNotAllowed"));

            assertThat(privateReceiver.getRequestCount()).isZero();
        }
    }

    @Test
    void testEventToTheWebhookOfAnotherUserIsNotFoundAndSendsNothing() throws Exception {
        User owner = createUser(UserRole.USER);
        User intruder = createUser(UserRole.USER);
        Endpoint endpoint = createEndpoint(owner, "task.assigned");

        mockMvc.perform(post(TEST_EVENT, intruder.getId(), endpoint.id()).with(asUser(intruder)))
                .andExpect(untypedProblem(404, "Not Found"))
                .andExpect(jsonPath("$.detail").value("Webhook not found with id: " + endpoint.id()));

        assertThat(receiver.getRequestCount()).isZero();
    }

    // Redelivery

    @Test
    void redeliveringADeliveredDeliverySendsTheSameBodyAgainUnderTheSameWebhookId() throws Exception {
        clock.set(NOW);
        User owner = createUser(UserRole.USER);
        Endpoint endpoint = createEndpoint(owner, "task.assigned");
        answer(204);
        answer(204);
        createTask(createUser(UserRole.ADMIN), owner);
        UUID deliveryId = onlyDeliveryId(endpoint);
        RecordedRequest first = nextRequest();
        awaitAttempts(deliveryId, 1);
        Instant later = NOW.plus(Duration.ofHours(1));
        clock.set(later);

        redeliver(owner, endpoint, deliveryId)
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.id").value(deliveryId.toString()))
                .andExpect(jsonPath("$.status").value("PENDING"))
                .andExpect(jsonPath("$.attempts").value(0))
                .andExpect(jsonPath("$.nextAttemptAt").value(later.plus(Duration.ofMinutes(5)).toString()))
                .andExpect(jsonPath("$.deliveredAt").value(nullValue()));

        RecordedRequest again = nextRequest();
        assertThat(again.getHeaders().get("webhook-id")).isEqualTo(deliveryId.toString());
        assertThat(again.getHeaders().get("webhook-timestamp")).isEqualTo(String.valueOf(later.getEpochSecond()));
        assertThat(again.getBody()).isEqualTo(first.getBody());
        WebhookDelivery delivered = awaitAttempts(deliveryId, 1);
        assertThat(delivered.getStatus()).isEqualTo(WebhookDeliveryStatus.DELIVERED);
        assertThat(delivered.getDeliveredAt()).isEqualTo(later);
        assertThat(deliveryIds(endpoint)).containsExactly(deliveryId);
    }

    @Test
    void redeliveringADeliveryThatFailedStartsTheRetryScheduleOverUnderTheSameWebhookId() throws Exception {
        clock.set(NOW);
        User owner = createUser(UserRole.USER);
        Endpoint endpoint = createEndpoint(owner, "task.assigned");
        answer(410);
        createTask(createUser(UserRole.ADMIN), owner);
        UUID deliveryId = onlyDeliveryId(endpoint);
        RecordedRequest first = nextRequest();
        assertThat(awaitFinished(deliveryId).getStatus()).isEqualTo(WebhookDeliveryStatus.FAILED);
        enable(owner, endpoint);
        answer(500);

        redeliver(owner, endpoint, deliveryId)
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.status").value("PENDING"))
                .andExpect(jsonPath("$.attempts").value(0))
                .andExpect(jsonPath("$.lastStatusCode").value(nullValue()))
                .andExpect(jsonPath("$.lastError").value(nullValue()))
                .andExpect(jsonPath("$.lastAttemptAt").value(nullValue()));

        RecordedRequest again = nextRequest();
        assertThat(again.getHeaders().get("webhook-id")).isEqualTo(deliveryId.toString());
        assertThat(again.getBody()).isEqualTo(first.getBody());
        WebhookDelivery pending = awaitAttempts(deliveryId, 1);
        assertThat(pending.getStatus()).isEqualTo(WebhookDeliveryStatus.PENDING);
        assertThat(pending.getAttempts()).isOne();
        assertThat(pending.getLastStatusCode()).isEqualTo(500);
        assertJittered(Duration.between(NOW, pending.getNextAttemptAt()), RETRY_SCHEDULE.getFirst());
    }

    @Test
    void redeliveringAPendingDeliverySendsItWithoutWaitingForItsRetry() throws Exception {
        clock.set(NOW);
        User owner = createUser(UserRole.USER);
        Endpoint endpoint = createEndpoint(owner, "task.assigned");
        answer(503);
        answer(204);
        createTask(createUser(UserRole.ADMIN), owner);
        UUID deliveryId = onlyDeliveryId(endpoint);
        nextRequest();
        assertThat(awaitAttempts(deliveryId, 1).getStatus()).isEqualTo(WebhookDeliveryStatus.PENDING);

        redeliver(owner, endpoint, deliveryId).andExpect(status().isAccepted());

        assertThat(nextRequest().getHeaders().get("webhook-id")).isEqualTo(deliveryId.toString());
        WebhookDelivery delivered = awaitAttempts(deliveryId, 1);
        assertThat(delivered.getStatus()).isEqualTo(WebhookDeliveryStatus.DELIVERED);
        assertThat(delivered.getAttempts()).isOne();
    }

    @Test
    void redeliveryToAPausedEndpointFailsWithoutARequest() throws Exception {
        clock.set(NOW);
        User owner = createUser(UserRole.USER);
        Endpoint endpoint = createEndpoint(owner, "task.assigned");
        answer(204);
        createTask(createUser(UserRole.ADMIN), owner);
        UUID deliveryId = onlyDeliveryId(endpoint);
        nextRequest();
        awaitAttempts(deliveryId, 1);
        pause(owner, endpoint);

        redeliver(owner, endpoint, deliveryId).andExpect(status().isAccepted());

        WebhookDelivery failed = awaitFinished(deliveryId);
        assertThat(failed.getStatus()).isEqualTo(WebhookDeliveryStatus.FAILED);
        assertThat(failed.getLastError()).isEqualTo("EndpointDisabled");
        assertThat(failed.getAttempts()).isZero();
        assertThat(receiver.getRequestCount()).isOne();
    }

    @Test
    void redeliveringADeliveryOfAnotherWebhookIsNotFoundAndLeavesItAlone() throws Exception {
        clock.set(NOW);
        User owner = createUser(UserRole.USER);
        Endpoint endpoint = createEndpoint(owner, "task.assigned");
        Endpoint other = createEndpoint(owner, "task.assigned");
        UUID deliveryId = insertDelivery(other, WebhookDeliveryStatus.DELIVERED, NOW);

        redeliver(owner, endpoint, deliveryId)
                .andExpect(untypedProblem(404, "Not Found"))
                .andExpect(jsonPath("$.detail").value("Webhook delivery not found with id: " + deliveryId));

        assertThat(delivery(deliveryId).getStatus()).isEqualTo(WebhookDeliveryStatus.DELIVERED);
    }

    @Test
    void redeliveringADeliveryOfAnotherUserUnderOnesOwnWebhookIsNotFound() throws Exception {
        clock.set(NOW);
        User owner = createUser(UserRole.USER);
        User intruder = createUser(UserRole.USER);
        UUID deliveryId = insertDelivery(createEndpoint(owner, "task.assigned"), WebhookDeliveryStatus.FAILED, NOW);
        Endpoint intrudersEndpoint = createEndpoint(intruder, "task.assigned");

        redeliver(intruder, intrudersEndpoint, deliveryId)
                .andExpect(untypedProblem(404, "Not Found"))
                .andExpect(jsonPath("$.detail").value("Webhook delivery not found with id: " + deliveryId));

        assertThat(delivery(deliveryId).getStatus()).isEqualTo(WebhookDeliveryStatus.FAILED);
    }

    @Test
    void redeliveringAnUnknownDeliveryIsNotFound() throws Exception {
        User owner = createUser(UserRole.USER);
        Endpoint endpoint = createEndpoint(owner, "task.assigned");
        UUID unknown = UUID.randomUUID();

        redeliver(owner, endpoint, unknown)
                .andExpect(untypedProblem(404, "Not Found"))
                .andExpect(jsonPath("$.detail").value("Webhook delivery not found with id: " + unknown));
    }

    // Automatic disabling

    @Test
    void firstFailedAttemptMarksTheEndpointFailingAndLaterFailuresKeepThatTime() throws Exception {
        User admin = createUser(UserRole.ADMIN);
        User owner = createUser(UserRole.USER);
        Endpoint endpoint = createEndpoint(owner, "task.assigned");

        attemptAt(NOW, 500, admin, owner, endpoint);
        attemptAt(NOW.plus(Duration.ofDays(1)), 500, admin, owner, endpoint);

        assertThat(failingSince(endpoint)).isEqualTo(NOW);
    }

    @Test
    void endpointFailingForJustUnderThreeDaysStaysEnabled() throws Exception {
        User admin = createUser(UserRole.ADMIN);
        User owner = createUser(UserRole.USER);
        Endpoint endpoint = createEndpoint(owner, "task.assigned");

        attemptAt(NOW, 500, admin, owner, endpoint);
        attemptAt(NOW.plus(DISABLE_AFTER).minusMillis(1), 500, admin, owner, endpoint);

        mockMvc.perform(get(WEBHOOK, owner.getId(), endpoint.id()).with(asUser(owner)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.enabled").value(true))
                .andExpect(jsonPath("$.disabledReason").value(nullValue()));
    }

    @Test
    void endpointWhoseAttemptsAllFailedForThreeDaysIsDisabledForFailing() throws Exception {
        User admin = createUser(UserRole.ADMIN);
        User owner = createUser(UserRole.USER);
        Endpoint endpoint = createEndpoint(owner, "task.assigned");

        attemptAt(NOW, 500, admin, owner, endpoint);
        attemptAt(NOW.plus(DISABLE_AFTER), 500, admin, owner, endpoint);

        mockMvc.perform(get(WEBHOOK, owner.getId(), endpoint.id()).with(asUser(owner)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.enabled").value(false))
                .andExpect(jsonPath("$.disabledReason").value("FAILING"))
                .andExpect(jsonPath("$.disabledAt").value(NOW.plus(DISABLE_AFTER).toString()));
        createTask(admin, owner);
        assertThat(deliveryIds(endpoint)).hasSize(2);
    }

    @Test
    void ownerOfAnEndpointDisabledForFailingIsEmailedItsHostButNeitherItsPathNorItsQuery() throws Exception {
        User admin = createUser(UserRole.ADMIN);
        User owner = createUser(UserRole.USER);
        Endpoint endpoint = createEndpointAt(owner,
                receiverUrl("/hooks/T0001/B0002/path-secret?token=query-secret"), "task.assigned");

        attemptAt(NOW, 500, admin, owner, endpoint);
        attemptAt(NOW.plus(DISABLE_AFTER), 500, admin, owner, endpoint);

        assertThat(mailpit.latestTextTo(owner.getEmail(), DISABLED_EMAIL))
                .contains("Hello " + owner.getDisplayName())
                .contains("Your webhook to 127.0.0.1 failed every attempt for 3 days, so it was disabled")
                .doesNotContain(":" + receiver.getPort())
                .doesNotContain("/hooks")
                .doesNotContain("path-secret")
                .doesNotContain("query-secret");
    }

    @Test
    void attemptThatFailsOnceTheEndpointIsDisabledSendsNoSecondEmail() throws Exception {
        User admin = createUser(UserRole.ADMIN);
        User owner = createUser(UserRole.USER);
        Endpoint endpoint = createEndpoint(owner, "task.assigned");
        attemptAt(NOW, 500, admin, owner, endpoint);
        clock.set(NOW.plus(DISABLE_AFTER));
        // The first attempt waits for its answer while the second fails at once and disables the endpoint
        receiver.enqueue(new MockResponse.Builder().code(500).headersDelay(1500, TimeUnit.MILLISECONDS).build());
        createTask(admin, owner);
        UUID slow = deliveryIds(endpoint).getLast();
        nextRequest();
        answer(500);

        createTask(admin, owner);

        awaitAttempts(deliveryIds(endpoint).getLast(), 1);
        assertThat(delivery(slow).getAttempts()).as("attempts of the delivery still waiting for its answer").isZero();
        assertThat(awaitAttempts(slow, 1).getLastStatusCode()).isEqualTo(500);
        mailpit.latestTextTo(owner.getEmail(), DISABLED_EMAIL);
        Thread.sleep(NO_MAIL_GRACE_PERIOD);
        assertThat(mailpit.countTo(owner.getEmail(), DISABLED_EMAIL)).isOne();
    }

    @Test
    void failuresRecordedAtTheSameTimeEmailTheOwnerOnce() throws Exception {
        User admin = createUser(UserRole.ADMIN);
        User owner = createUser(UserRole.USER);
        Endpoint endpoint = createEndpoint(owner, "task.assigned");
        attemptAt(NOW, 500, admin, owner, endpoint);
        clock.set(NOW.plus(DISABLE_AFTER));
        // Each request is answered once both arrived, within the read timeout, so both failures are recorded together
        CountDownLatch bothArrived = new CountDownLatch(2);
        receiver.setDispatcher(new Dispatcher() {
            @Override
            public MockResponse dispatch(RecordedRequest request) throws InterruptedException {
                bothArrived.countDown();
                bothArrived.await(1500, TimeUnit.MILLISECONDS);
                return new MockResponse.Builder().code(500).build();
            }
        });

        createTask(admin, owner);
        createTask(admin, owner);

        deliveryIds(endpoint).forEach(id -> awaitAttempts(id, 1));
        assertThat(bothArrived.getCount()).isZero();
        mailpit.latestTextTo(owner.getEmail(), DISABLED_EMAIL);
        Thread.sleep(NO_MAIL_GRACE_PERIOD);
        assertThat(mailpit.countTo(owner.getEmail(), DISABLED_EMAIL)).isOne();
    }

    @Test
    void successInBetweenStartsTheThreeDaysOver() throws Exception {
        User admin = createUser(UserRole.ADMIN);
        User owner = createUser(UserRole.USER);
        Endpoint endpoint = createEndpoint(owner, "task.assigned");
        attemptAt(NOW, 500, admin, owner, endpoint);

        attemptAt(NOW.plus(Duration.ofDays(2)), 204, admin, owner, endpoint);

        assertThat(failingSince(endpoint)).isNull();
        attemptAt(NOW.plus(DISABLE_AFTER), 500, admin, owner, endpoint);
        attemptAt(NOW.plus(DISABLE_AFTER).plus(Duration.ofDays(1)), 500, admin, owner, endpoint);
        assertThat(failingSince(endpoint)).isEqualTo(NOW.plus(DISABLE_AFTER));
        mockMvc.perform(get(WEBHOOK, owner.getId(), endpoint.id()).with(asUser(owner)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.enabled").value(true));
    }

    @Test
    void enablingAnEndpointDisabledForFailingStartsTheThreeDaysOver() throws Exception {
        User admin = createUser(UserRole.ADMIN);
        User owner = createUser(UserRole.USER);
        Endpoint endpoint = createEndpoint(owner, "task.assigned");
        attemptAt(NOW, 500, admin, owner, endpoint);
        attemptAt(NOW.plus(DISABLE_AFTER), 500, admin, owner, endpoint);
        clock.set(NOW.plus(DISABLE_AFTER).plus(Duration.ofHours(1)));

        enable(owner, endpoint);

        assertThat(failingSince(endpoint)).isNull();
        Instant failedAgainAt = NOW.plus(DISABLE_AFTER).plus(Duration.ofHours(2));
        attemptAt(failedAgainAt, 500, admin, owner, endpoint);
        assertThat(failingSince(endpoint)).isEqualTo(failedAgainAt);
        mockMvc.perform(get(WEBHOOK, owner.getId(), endpoint.id()).with(asUser(owner)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.enabled").value(true))
                .andExpect(jsonPath("$.disabledReason").value(nullValue()));
    }

    @Test
    void replacingAnEnabledEndpointKeepsTheDaysAlreadyFailing() throws Exception {
        User admin = createUser(UserRole.ADMIN);
        User owner = createUser(UserRole.USER);
        Endpoint endpoint = createEndpoint(owner, "task.assigned");
        attemptAt(NOW, 500, admin, owner, endpoint);
        clock.set(NOW.plus(Duration.ofHours(1)));

        enable(owner, endpoint);

        assertThat(failingSince(endpoint)).isEqualTo(NOW);
    }

    @Test
    void goneAfterThreeDaysOfFailuresDisablesTheEndpointAsGoneWithoutAnEmail() throws Exception {
        User admin = createUser(UserRole.ADMIN);
        User owner = createUser(UserRole.USER);
        Endpoint endpoint = createEndpoint(owner, "task.assigned");
        attemptAt(NOW, 500, admin, owner, endpoint);

        attemptAt(NOW.plus(DISABLE_AFTER), 410, admin, owner, endpoint);

        mockMvc.perform(get(WEBHOOK, owner.getId(), endpoint.id()).with(asUser(owner)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.enabled").value(false))
                .andExpect(jsonPath("$.disabledReason").value("GONE"));
        Thread.sleep(NO_MAIL_GRACE_PERIOD);
        assertThat(mailpit.countTo(owner.getEmail(), DISABLED_EMAIL)).isZero();
    }

    // Purge

    @Test
    void purgeDeletesTheDeliveriesCreatedBeforeTheRetentionWhateverTheirState() throws Exception {
        clock.set(NOW);
        Endpoint endpoint = createEndpoint(createUser(UserRole.USER), "task.assigned");
        Instant cutoff = NOW.minus(DELIVERY_RETENTION);
        insertDelivery(endpoint, WebhookDeliveryStatus.FAILED, cutoff.minus(Duration.ofDays(10)));
        insertDelivery(endpoint, WebhookDeliveryStatus.PENDING, cutoff.minusSeconds(1));
        insertDelivery(endpoint, WebhookDeliveryStatus.DELIVERED, cutoff.minusMillis(1));
        UUID atTheCutoff = insertDelivery(endpoint, WebhookDeliveryStatus.DELIVERED, cutoff);
        UUID recent = insertDelivery(endpoint, WebhookDeliveryStatus.FAILED, NOW.minus(Duration.ofDays(1)));

        int deleted = deliveryService.purge();

        assertThat(deleted).isGreaterThanOrEqualTo(3);
        assertThat(deliveryIds(endpoint)).containsExactly(atTheCutoff, recent);
    }

    // Deletion and erasure

    @Test
    void deletingAnEndpointDeletesItsDeliveriesOnly() throws Exception {
        User assignee = createUser(UserRole.USER);
        Endpoint deleted = createEndpoint(assignee, "task.assigned");
        Endpoint kept = createEndpoint(assignee, "task.assigned");
        answer(204);
        answer(204);
        createTask(createUser(UserRole.ADMIN), assignee);
        awaitAttempts(onlyDeliveryId(deleted), 1);
        awaitAttempts(onlyDeliveryId(kept), 1);

        mockMvc.perform(delete(WEBHOOK, assignee.getId(), deleted.id()).with(asUser(assignee)))
                .andExpect(status().isNoContent());

        assertThat(deliveryIds(deleted)).isEmpty();
        assertThat(deliveryIds(kept)).hasSize(1);
    }

    @Test
    void deletingAnEndpointWithAPendingDeliveryDeletesIt() throws Exception {
        clock.set(NOW);
        User assignee = createUser(UserRole.USER);
        Endpoint endpoint = createEndpoint(assignee, "task.assigned");
        answer(500);
        createTask(createUser(UserRole.ADMIN), assignee);
        UUID deliveryId = onlyDeliveryId(endpoint);
        awaitAttempts(deliveryId, 1);

        mockMvc.perform(delete(WEBHOOK, assignee.getId(), endpoint.id()).with(asUser(assignee)))
                .andExpect(status().isNoContent());

        assertThat(deliveryRepository.findById(deliveryId)).isEmpty();
    }

    @Test
    void erasingAUserDeletesTheDeliveriesOfTheirEndpoints() throws Exception {
        User admin = createUser(UserRole.ADMIN);
        User erased = createUser(UserRole.USER);
        User other = createUser(UserRole.USER);
        Endpoint erasedEndpoint = createEndpoint(erased, "task.assigned");
        Endpoint otherEndpoint = createEndpoint(other, "task.assigned");
        answer(204);
        answer(204);
        createTask(admin, erased);
        createTask(admin, other);
        awaitAttempts(onlyDeliveryId(erasedEndpoint), 1);
        awaitAttempts(onlyDeliveryId(otherEndpoint), 1);
        userService.delete(erased.getId());
        // Dated long before anything the other test classes delete, so the erasure below reaches only this user
        Instant deletedAt = Instant.parse("1990-01-01T00:00:00Z");
        jdbcTemplate.update("UPDATE users SET deleted_at = ? WHERE id = ?", Timestamp.from(deletedAt), erased.getId());

        List<UUID> erasedIds = retentionQueries.anonymizeUsersDeletedBefore(deletedAt.plusSeconds(1), clock.instant());

        assertThat(erasedIds).contains(erased.getId());
        assertThat(deliveryIds(erasedEndpoint)).isEmpty();
        assertThat(deliveryIds(otherEndpoint)).hasSize(1);
    }

    private void answer(int status) {
        receiver.enqueue(new MockResponse.Builder().code(status).build());
    }

    private String receiverUrl(String path) {
        return "http://127.0.0.1:" + receiver.getPort() + path;
    }

    private RecordedRequest nextRequest() throws InterruptedException {
        RecordedRequest request = receiver.takeRequest(DELIVERY_TIMEOUT.toSeconds(), TimeUnit.SECONDS);
        assertThat(request).as("request received by the webhook receiver").isNotNull();
        return request;
    }

    private static void verify(String secret, RecordedRequest request) throws Exception {
        new Webhook(secret).verify(request.getBody().utf8(), request.getHeaders().toMultimap());
    }

    private JsonNode json(RecordedRequest request) {
        return jsonMapper.readTree(request.getBody().utf8());
    }

    private JsonNode payload(
            String type,
            Instant timestamp,
            CreatedTask task,
            User actor,
            Map<String, Object> details
    ) {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("task", Map.of("id", task.id().toString(), "reference", task.reference(), "title", TITLE));
        data.put("actor", actor == null
                ? null
                : Map.of("id", actor.getId().toString(), "displayName", actor.getDisplayName()));
        data.putAll(details);

        return jsonMapper.valueToTree(Map.of("type", type, "timestamp", timestamp.toString(), "data", data));
    }

    private ResultActions sendTestEvent(User owner, Endpoint endpoint) throws Exception {
        return mockMvc.perform(post(TEST_EVENT, owner.getId(), endpoint.id()).with(asUser(owner)));
    }

    private ResultActions redeliver(User owner, Endpoint endpoint, UUID deliveryId) throws Exception {
        return mockMvc.perform(post(REDELIVER, owner.getId(), endpoint.id(), deliveryId).with(asUser(owner)));
    }

    /** Assigns a new task to the owner at the given time, and waits until the receiver's answer is recorded. */
    private UUID attemptAt(Instant at, int answer, User admin, User owner, Endpoint endpoint) throws Exception {
        clock.set(at);
        answer(answer);
        createTask(admin, owner);
        UUID deliveryId = deliveryIds(endpoint).getLast();
        nextRequest();
        awaitAttempts(deliveryId, 1);
        return deliveryId;
    }

    private Instant failingSince(Endpoint endpoint) {
        Timestamp failingSince = jdbcTemplate.queryForObject(
                "SELECT failing_since FROM webhook_endpoints WHERE id = ?", Timestamp.class, endpoint.id());
        return failingSince == null ? null : failingSince.toInstant();
    }

    private UUID insertDelivery(Endpoint endpoint, WebhookDeliveryStatus status, Instant createdAt) {
        return jdbcTemplate.queryForObject(
                """
                        INSERT INTO webhook_deliveries (endpoint_id, event, payload, status, attempts, created_at)
                        VALUES (?, 'TASK_ASSIGNED', ?, ?, 1, ?)
                        RETURNING id
                        """,
                UUID.class,
                endpoint.id(),
                LOST_PAYLOAD,
                status.name(),
                Timestamp.from(createdAt)
        );
    }

    // A delivery without its outbox message, as if the message had been lost
    private UUID insertPendingDelivery(Endpoint endpoint, Instant nextAttemptAt) {
        return jdbcTemplate.queryForObject(
                """
                        INSERT INTO webhook_deliveries
                            (endpoint_id, event, payload, status, attempts, next_attempt_at, created_at)
                        VALUES (?, 'TASK_ASSIGNED', ?, 'PENDING', 0, ?, ?)
                        RETURNING id
                        """,
                UUID.class,
                endpoint.id(),
                LOST_PAYLOAD,
                Timestamp.from(nextAttemptAt),
                Timestamp.from(clock.instant())
        );
    }

    private WebhookDelivery delivery(UUID deliveryId) {
        return deliveryRepository.findById(deliveryId).orElseThrow();
    }

    private WebhookDelivery awaitAttempts(UUID deliveryId, int attempts) {
        return await().atMost(DELIVERY_TIMEOUT).pollInterval(Duration.ofMillis(50))
                .until(() -> delivery(deliveryId), delivery -> delivery.getAttempts() >= attempts);
    }

    private WebhookDelivery awaitFinished(UUID deliveryId) {
        return await().atMost(DELIVERY_TIMEOUT).pollInterval(Duration.ofMillis(50))
                .until(() -> delivery(deliveryId), delivery -> delivery.getStatus() != WebhookDeliveryStatus.PENDING);
    }

    private List<UUID> deliveryIds(Endpoint endpoint) {
        return jdbcTemplate.queryForList(
                "SELECT id FROM webhook_deliveries WHERE endpoint_id = ? ORDER BY created_at, id",
                UUID.class,
                endpoint.id()
        );
    }

    private UUID onlyDeliveryId(Endpoint endpoint) {
        List<UUID> ids = deliveryIds(endpoint);
        assertThat(ids).hasSize(1);
        return ids.getFirst();
    }

    private double deliveryCount(String outcome) {
        Counter counter = meterRegistry.find("webhook.deliveries").tag("outcome", outcome).counter();
        return counter == null ? 0 : counter.count();
    }

    private static void assertJittered(Duration delay, Duration scheduled) {
        assertThat(delay).isBetween(
                Duration.ofMillis((long) (scheduled.toMillis() * 0.8)),
                Duration.ofMillis((long) (scheduled.toMillis() * 1.2))
        );
    }

    private Endpoint createEndpoint(User owner, String... events) throws Exception {
        return createEndpointAt(owner, receiverUrl("/hooks"), events);
    }

    private Endpoint createEndpointAt(User owner, String url, String... events) throws Exception {
        JsonNode created = json(mockMvc.perform(post(WEBHOOKS, owner.getId())
                        .with(asUser(owner))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"url\": \"%s\", \"events\": %s}".formatted(url, eventArray(events))))
                .andExpect(status().isCreated()));

        return new Endpoint(
                UUID.fromString(created.get("id").asString()),
                created.get("secret").asString(),
                url,
                events
        );
    }

    private void pause(User owner, Endpoint endpoint) throws Exception {
        mockMvc.perform(put(WEBHOOK, owner.getId(), endpoint.id())
                        .with(asUser(owner))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"url\": \"%s\", \"events\": %s, \"enabled\": false}"
                                .formatted(endpoint.url(), eventArray(endpoint.events()))))
                .andExpect(status().isOk());
    }

    private void enable(User owner, Endpoint endpoint) throws Exception {
        mockMvc.perform(put(WEBHOOK, owner.getId(), endpoint.id())
                        .with(asUser(owner))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"url\": \"%s\", \"events\": %s, \"enabled\": true}"
                                .formatted(endpoint.url(), eventArray(endpoint.events()))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.enabled").value(true));
    }

    private String rotate(User owner, Endpoint endpoint) throws Exception {
        return json(mockMvc.perform(post(WEBHOOK + "/secret", owner.getId(), endpoint.id()).with(asUser(owner)))
                .andExpect(status().isOk()))
                .get("secret").asString();
    }

    private static String eventArray(String... events) {
        return Arrays.stream(events)
                .map(event -> "\"" + event + "\"")
                .collect(Collectors.joining(", ", "[", "]"));
    }

    private CreatedTask createTask(User admin, User assignee) throws Exception {
        return createTask(admin, assignee, Instant.parse("2030-01-01T10:00:00Z"));
    }

    private CreatedTask createTask(User admin, User assignee, Instant dueAt) throws Exception {
        String reference = "WH-" + UUID.randomUUID().toString().substring(0, 8);
        String assigned = assignee == null ? "null" : "\"" + assignee.getId() + "\"";

        JsonNode created = json(mockMvc.perform(post(TASKS)
                        .with(asAdmin(admin))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"reference": "%s", "title": "%s", "dueAt": "%s", "assignedTo": %s}
                                """.formatted(reference, TITLE, dueAt, assigned)))
                .andExpect(status().isCreated()));

        return new CreatedTask(UUID.fromString(created.get("id").asString()), reference);
    }

    private void assign(User admin, CreatedTask task, User assignee) throws Exception {
        mockMvc.perform(patch(TASKS + "/{id}/assign", task.id())
                        .with(asAdmin(admin))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"userId\": \"" + assignee.getId() + "\"}"))
                .andExpect(status().isOk());
    }

    private void cancel(User admin, CreatedTask task) throws Exception {
        mockMvc.perform(post(TASKS + "/{id}/cancel", task.id())
                        .with(asAdmin(admin))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"reason\": \"Obsolete\"}"))
                .andExpect(status().isOk());
    }

    private UUID comment(User author, CreatedTask task, String body) throws Exception {
        JsonNode created = json(mockMvc.perform(post(TASKS + "/{id}/comments", task.id())
                        .with(asUser(author))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(jsonMapper.writeValueAsString(Map.of("body", body))))
                .andExpect(status().isCreated()));

        return UUID.fromString(created.get("id").asString());
    }

    private JsonNode json(ResultActions result) throws Exception {
        return jsonMapper.readTree(result.andReturn().getResponse().getContentAsString());
    }

    private User createUser(UserRole role) {
        User user = new User();

        user.setEmail("webhook-delivery-" + UUID.randomUUID() + "@example.com");
        user.setPasswordHash(passwordEncoder.encode("password"));
        user.setEmailVerifiedAt(Instant.now());
        user.setDisplayName("Receiver " + role + " " + UUID.randomUUID().toString().substring(0, 6));
        user.setRoles(EnumSet.of(UserRole.USER, role));

        return userRepository.saveAndFlush(user);
    }

    // On a managed entity, as a service does: saving a detached User would not carry the change to its profile
    private void updateUser(User user, Consumer<User> change) {
        transactionTemplate.executeWithoutResult(
                status -> change.accept(userRepository.findById(user.getId()).orElseThrow()));
    }

    private static RequestPostProcessor asUser(User user) {
        return as(user, UserRole.USER);
    }

    private static RequestPostProcessor asAdmin(User user) {
        return as(user, UserRole.ADMIN);
    }

    private static RequestPostProcessor as(User user, UserRole role) {
        return jwt()
                .jwt(token -> token.claim("uid", user.getId().toString()))
                .authorities(new SimpleGrantedAuthority("ROLE_" + role.name()));
    }

    private static InetAddress address(int... bytes) {
        byte[] address = new byte[bytes.length];
        for (int i = 0; i < bytes.length; i++) {
            address[i] = (byte) bytes[i];
        }
        try {
            return InetAddress.getByAddress(address);
        } catch (IOException invalid) {
            throw new IllegalArgumentException(invalid);
        }
    }
}
