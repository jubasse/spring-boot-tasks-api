package io.julienmetral.tasks.notification.webhook;

import com.standardwebhooks.Webhook;
import io.julienmetral.tasks.identity.entities.User;
import io.julienmetral.tasks.identity.entities.UserRole;
import io.julienmetral.tasks.identity.repositories.UserRepository;
import io.julienmetral.tasks.notification.entities.WebhookDelivery;
import io.julienmetral.tasks.notification.entities.WebhookDeliveryStatus;
import io.julienmetral.tasks.notification.repositories.WebhookDeliveryRepository;
import io.julienmetral.tasks.support.IntegrationTest;
import io.julienmetral.tasks.support.Mailpit;
import io.julienmetral.tasks.support.TestClock;
import io.julienmetral.tasks.task.services.TaskReminderService;
import mockwebserver3.MockResponse;
import mockwebserver3.MockWebServer;
import mockwebserver3.QueueDispatcher;
import mockwebserver3.RecordedRequest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.io.IOException;
import java.net.InetAddress;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.Arrays;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatNoException;
import static org.awaitility.Awaitility.await;
import static org.hamcrest.Matchers.nullValue;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Slack deliveries end to end, as {@link WebhookDeliveryTests} does for signed events. The URL policy accepts only
 * hooks.slack.com, so each Slack endpoint is declared with a Slack URL through the API, then its {@code url} column is
 * pointed at a MockWebServer on 127.0.0.1, encrypted with {@link WebhookSecrets} as the service stores it.
 */
@IntegrationTest
class SlackWebhookDeliveryTests {

    private static final String WEBHOOKS = "/api/v1/users/{id}/webhooks";

    private static final String WEBHOOK = WEBHOOKS + "/{webhookId}";

    private static final String REDELIVER = WEBHOOK + "/deliveries/{deliveryId}/redeliver";

    private static final String TEST_EVENT = WEBHOOK + "/test";

    private static final String TASKS = "/api/v1/tasks";

    private static final String TITLE = "Renew the TLS certificates";

    private static final String SLACK_PATH = "/services/T0001/B0002/";

    private static final String TEST_MESSAGE = "Test message from the Tasks API: this Slack webhook works.";

    private static final InetAddress LOOPBACK = loopback();

    private static final Duration DELIVERY_TIMEOUT = Duration.ofSeconds(10);

    // Far from the eras of the other webhook and reminder tests, so no other class claims or reminds these rows
    private static final Instant NOW = Instant.parse("2160-03-04T05:06:07Z");

    private static final Instant REMINDER_NOW = Instant.parse("2160-06-01T00:00:00Z");

    private static final Duration DISABLE_AFTER = Duration.ofDays(3);

    private static final String DISABLED_EMAIL = "failed every attempt for 3 days";

    // Emails leave through the outbox and RabbitMQ: "no email sent" can only be checked after a grace period
    private static final Duration NO_MAIL_GRACE_PERIOD = Duration.ofSeconds(1);

    private record SlackEndpoint(UUID id, String path, String token) {
    }

    private record StandardEndpoint(UUID id, String secret) {
    }

    private record CreatedTask(UUID id, String reference) {
    }

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private PasswordEncoder passwordEncoder;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private JsonMapper jsonMapper;

    @Autowired
    private WebhookSecrets webhookSecrets;

    @Autowired
    private TestClock clock;

    @Autowired
    private WebhookDeliveryService deliveryService;

    @Autowired
    private WebhookDeliveryRepository deliveryRepository;

    @Autowired
    private TaskReminderService reminderService;

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

    // Message of each event

    @Test
    void assignmentReachesTheDecryptedSlackUrlUnsignedAsTheRenderedMessage() throws Exception {
        User admin = createUser(UserRole.ADMIN);
        User assignee = createUser(UserRole.USER);
        SlackEndpoint endpoint = createSlackEndpoint(assignee, "task.assigned");
        answer(200);

        CreatedTask task = createTask(admin, assignee);

        RecordedRequest request = nextRequest();
        assertThat(request.getMethod()).isEqualTo("POST");
        assertThat(request.getUrl().encodedPath()).isEqualTo(endpoint.path());
        assertThat(request.getHeaders().get("Content-Type")).startsWith(MediaType.APPLICATION_JSON_VALUE);
        assertUnsigned(request);
        assertThat(slackText(request))
                .isEqualTo("*%s* assigned you *%s*: %s".formatted(admin.getDisplayName(), task.reference(), TITLE));
        WebhookDelivery delivered = awaitAttempts(onlyDeliveryId(endpoint.id()), 1);
        assertThat(delivered.getStatus()).isEqualTo(WebhookDeliveryStatus.DELIVERED);
        assertThat(delivered.getPayload()).isEqualTo(request.getBody().utf8());
    }

    @Test
    void unassignmentMessageGoesToThePreviousAssignee() throws Exception {
        User admin = createUser(UserRole.ADMIN);
        User previous = createUser(UserRole.USER);
        SlackEndpoint endpoint = createSlackEndpoint(previous, "task.unassigned");
        CreatedTask task = createTask(admin, previous);
        answer(200);

        mockMvc.perform(patch(TASKS + "/{id}/assign", task.id())
                        .with(asAdmin(admin))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"userId\": \"" + createUser(UserRole.USER).getId() + "\"}"))
                .andExpect(status().isOk());

        RecordedRequest request = nextRequest();
        assertUnsigned(request);
        assertThat(slackText(request)).isEqualTo(
                "*%s* assigned *%s*: %s to someone else".formatted(admin.getDisplayName(), task.reference(), TITLE));
        awaitAttempts(onlyDeliveryId(endpoint.id()), 1);
    }

    @Test
    void cancellationMessageGivesTheReason() throws Exception {
        User admin = createUser(UserRole.ADMIN);
        User assignee = createUser(UserRole.USER);
        SlackEndpoint endpoint = createSlackEndpoint(assignee, "task.cancelled");
        CreatedTask task = createTask(admin, assignee);
        answer(200);

        mockMvc.perform(post(TASKS + "/{id}/cancel", task.id())
                        .with(asAdmin(admin))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"reason\": \"Customer withdrew the request\"}"))
                .andExpect(status().isOk());

        assertThat(slackText(nextRequest())).isEqualTo("*%s* cancelled *%s*: %s\nReason: Customer withdrew the request"
                .formatted(admin.getDisplayName(), task.reference(), TITLE));
        awaitAttempts(onlyDeliveryId(endpoint.id()), 1);
    }

    @Test
    void cancellationByAStatusChangeHasNoReasonLine() throws Exception {
        User admin = createUser(UserRole.ADMIN);
        User assignee = createUser(UserRole.USER);
        SlackEndpoint endpoint = createSlackEndpoint(assignee, "task.cancelled");
        CreatedTask task = createTask(admin, assignee);
        answer(200);

        mockMvc.perform(patch(TASKS + "/{id}/status", task.id())
                        .with(asAdmin(admin))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"status\": \"CANCELLED\"}"))
                .andExpect(status().isOk());

        assertThat(slackText(nextRequest()))
                .isEqualTo("*%s* cancelled *%s*: %s".formatted(admin.getDisplayName(), task.reference(), TITLE));
        awaitAttempts(onlyDeliveryId(endpoint.id()), 1);
    }

    @Test
    void deletionMessageNamesTheActorAndTheTask() throws Exception {
        User admin = createUser(UserRole.ADMIN);
        User assignee = createUser(UserRole.USER);
        SlackEndpoint endpoint = createSlackEndpoint(assignee, "task.deleted");
        CreatedTask task = createTask(admin, assignee);
        answer(200);

        mockMvc.perform(delete(TASKS + "/{id}", task.id()).with(asAdmin(admin)))
                .andExpect(status().isNoContent());

        assertThat(slackText(nextRequest()))
                .isEqualTo("*%s* deleted *%s*: %s".formatted(admin.getDisplayName(), task.reference(), TITLE));
        awaitAttempts(onlyDeliveryId(endpoint.id()), 1);
    }

    @Test
    void commentMessageQuotesEveryLineOfTheComment() throws Exception {
        User admin = createUser(UserRole.ADMIN);
        User assignee = createUser(UserRole.USER);
        SlackEndpoint endpoint = createSlackEndpoint(assignee, "task.commented");
        CreatedTask task = createTask(admin, assignee);
        answer(200);

        comment(admin, task, "Looks good to me\nShip it on Monday");

        assertThat(slackText(nextRequest())).isEqualTo(
                "*%s* commented on *%s*: %s\n>Looks good to me\n>Ship it on Monday"
                        .formatted(admin.getDisplayName(), task.reference(), TITLE));
        awaitAttempts(onlyDeliveryId(endpoint.id()), 1);
    }

    @Test
    void mentionMessageQuotesTheCommentWithTheMentionRendered() throws Exception {
        User admin = createUser(UserRole.ADMIN);
        User mentioned = createUser(UserRole.USER);
        SlackEndpoint endpoint = createSlackEndpoint(mentioned, "task.mentioned");
        CreatedTask task = createTask(admin, null);
        answer(200);

        comment(admin, task, "Please review, <@" + mentioned.getId() + ">");

        assertThat(slackText(nextRequest())).isEqualTo("*%s* mentioned you on *%s*: %s\n>Please review, @%s"
                .formatted(admin.getDisplayName(), task.reference(), TITLE, mentioned.getDisplayName()));
        awaitAttempts(onlyDeliveryId(endpoint.id()), 1);
    }

    @Test
    void dueSoonMessageGivesTheDueDateInUtcWithoutAnActor() throws Exception {
        clock.set(REMINDER_NOW);
        User assignee = createUser(UserRole.USER);
        SlackEndpoint endpoint = createSlackEndpoint(assignee, "task.due_soon");
        CreatedTask task = createTask(createUser(UserRole.ADMIN), assignee, TITLE,
                REMINDER_NOW.plus(Duration.ofHours(12)).plusSeconds(42));
        answer(200);

        reminderService.sendDueReminders();

        assertThat(slackText(nextRequest()))
                .isEqualTo("*%s*: %s is due on 2160-06-01 12:00 UTC".formatted(task.reference(), TITLE));
        awaitAttempts(onlyDeliveryId(endpoint.id()), 1);
    }

    @Test
    void overdueMessageGivesTheDueDateInUtcWithoutAnActor() throws Exception {
        clock.set(REMINDER_NOW);
        User assignee = createUser(UserRole.USER);
        SlackEndpoint endpoint = createSlackEndpoint(assignee, "task.overdue");
        CreatedTask task = createTask(createUser(UserRole.ADMIN), assignee, TITLE,
                REMINDER_NOW.minus(Duration.ofDays(2)).plus(Duration.ofMinutes(30)));
        answer(200);

        reminderService.sendDueReminders();

        assertThat(slackText(nextRequest())).isEqualTo(
                "*%s*: %s was due on 2160-05-30 00:30 UTC and is not done yet".formatted(task.reference(), TITLE));
        awaitAttempts(onlyDeliveryId(endpoint.id()), 1);
    }

    @Test
    void titleThatWouldNotifyTheWholeChannelIsEscaped() throws Exception {
        User admin = createUser(UserRole.ADMIN);
        User assignee = createUser(UserRole.USER);
        SlackEndpoint endpoint = createSlackEndpoint(assignee, "task.assigned");
        answer(200);

        CreatedTask task = createTask(admin, assignee, "Ship it <!channel> & <https://evil.example.com|celebrate>",
                Instant.parse("2170-01-01T10:00:00Z"));

        assertThat(slackText(nextRequest())).isEqualTo(
                "*%s* assigned you *%s*: Ship it &lt;!channel&gt; &amp; &lt;https://evil.example.com|celebrate&gt;"
                        .formatted(admin.getDisplayName(), task.reference()));
        awaitAttempts(onlyDeliveryId(endpoint.id()), 1);
    }

    // Answers of Slack

    @ParameterizedTest
    @ValueSource(ints = {403, 404, 410})
    void answerOfARevokedSlackWebhookDisablesTheEndpointAsGoneAndFailsTheDelivery(int answer) throws Exception {
        clock.set(NOW);
        User admin = createUser(UserRole.ADMIN);
        User assignee = createUser(UserRole.USER);
        SlackEndpoint endpoint = createSlackEndpoint(assignee, "task.assigned");
        answer(answer);

        createTask(admin, assignee);

        WebhookDelivery failed = awaitAttempts(onlyDeliveryId(endpoint.id()), 1);
        assertThat(failed.getStatus()).isEqualTo(WebhookDeliveryStatus.FAILED);
        assertThat(failed.getAttempts()).isOne();
        assertThat(failed.getLastStatusCode()).isEqualTo(answer);
        assertThat(failed.getLastError()).isNull();
        assertThat(failed.getNextAttemptAt()).isNull();
        assertThat(failingSince(endpoint.id())).isNull();
        mockMvc.perform(get(WEBHOOK, assignee.getId(), endpoint.id()).with(asUser(assignee)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.enabled").value(false))
                .andExpect(jsonPath("$.disabledReason").value("GONE"))
                .andExpect(jsonPath("$.disabledAt").value(NOW.toString()));

        createTask(admin, assignee);

        assertThat(deliveryIds(endpoint.id())).hasSize(1);
        assertThat(receiver.getRequestCount()).isOne();
    }

    @Test
    void badRequestFromSlackFailsTheDeliveryAtOnceWithoutMarkingTheEndpointFailing() throws Exception {
        clock.set(NOW);
        User assignee = createUser(UserRole.USER);
        SlackEndpoint endpoint = createSlackEndpoint(assignee, "task.assigned");
        answer(400);

        createTask(createUser(UserRole.ADMIN), assignee);

        WebhookDelivery failed = awaitAttempts(onlyDeliveryId(endpoint.id()), 1);
        assertThat(failed.getStatus()).isEqualTo(WebhookDeliveryStatus.FAILED);
        assertThat(failed.getAttempts()).isOne();
        assertThat(failed.getLastStatusCode()).isEqualTo(400);
        assertThat(failed.getLastError()).isNull();
        assertThat(failed.getNextAttemptAt()).isNull();
        assertThat(failingSince(endpoint.id())).isNull();
        mockMvc.perform(get(WEBHOOK, assignee.getId(), endpoint.id()).with(asUser(assignee)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.enabled").value(true))
                .andExpect(jsonPath("$.disabledReason").value(nullValue()));
        assertThat(receiver.getRequestCount()).isOne();
    }

    @Test
    void badRequestFromSlackDoesNotDisableAnEndpointFailingForMoreThanThreeDays() throws Exception {
        clock.set(NOW);
        User assignee = createUser(UserRole.USER);
        SlackEndpoint endpoint = createSlackEndpoint(assignee, "task.assigned");
        Instant failingSince = NOW.minus(DISABLE_AFTER).minus(Duration.ofDays(1));
        markFailingSince(endpoint.id(), failingSince);
        answer(400);

        createTask(createUser(UserRole.ADMIN), assignee);

        awaitFinished(onlyDeliveryId(endpoint.id()));
        assertThat(failingSince(endpoint.id())).isEqualTo(failingSince);
        mockMvc.perform(get(WEBHOOK, assignee.getId(), endpoint.id()).with(asUser(assignee)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.enabled").value(true));
        Thread.sleep(NO_MAIL_GRACE_PERIOD);
        assertThat(mailpit.countTo(assignee.getEmail(), DISABLED_EMAIL)).isZero();
    }

    @Test
    void serverErrorFromSlackIsRetriedWithTheSameMessageAndCountsAsAFailure() throws Exception {
        clock.set(NOW);
        User assignee = createUser(UserRole.USER);
        SlackEndpoint endpoint = createSlackEndpoint(assignee, "task.assigned");
        answer(500);
        answer(200);
        createTask(createUser(UserRole.ADMIN), assignee);
        UUID deliveryId = onlyDeliveryId(endpoint.id());
        RecordedRequest first = nextRequest();

        WebhookDelivery pending = awaitAttempts(deliveryId, 1);

        assertThat(pending.getStatus()).isEqualTo(WebhookDeliveryStatus.PENDING);
        assertThat(pending.getLastStatusCode()).isEqualTo(500);
        assertThat(Duration.between(NOW, pending.getNextAttemptAt()))
                .isBetween(Duration.ofMillis(4_000), Duration.ofMillis(6_000));
        assertThat(failingSince(endpoint.id())).isEqualTo(NOW);

        deliveryService.deliver(deliveryId);

        RecordedRequest retry = nextRequest();
        assertThat(retry.getUrl().encodedPath()).isEqualTo(endpoint.path());
        assertThat(retry.getBody()).isEqualTo(first.getBody());
        assertUnsigned(retry);
        assertThat(awaitAttempts(deliveryId, 2).getStatus()).isEqualTo(WebhookDeliveryStatus.DELIVERED);
        assertThat(failingSince(endpoint.id())).isNull();
    }

    @Test
    void slackEndpointFailingForThreeDaysIsDisabledAndItsOwnerEmailedOnlyTheHostOfItsUrl() throws Exception {
        clock.set(NOW);
        User owner = createUser(UserRole.USER);
        SlackEndpoint endpoint = createSlackEndpoint(owner, "task.assigned");
        markFailingSince(endpoint.id(), NOW.minus(DISABLE_AFTER));
        answer(500);

        createTask(createUser(UserRole.ADMIN), owner);

        awaitAttempts(onlyDeliveryId(endpoint.id()), 1);
        mockMvc.perform(get(WEBHOOK, owner.getId(), endpoint.id()).with(asUser(owner)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.enabled").value(false))
                .andExpect(jsonPath("$.disabledReason").value("FAILING"));
        assertThat(mailpit.latestTextTo(owner.getEmail(), DISABLED_EMAIL))
                .contains("Your webhook to 127.0.0.1 failed every attempt for 3 days, so it was disabled")
                .doesNotContain(endpoint.token())
                .doesNotContain("/services/")
                .doesNotContain(":" + receiver.getPort())
                .doesNotContain("v1:");
    }

    @ParameterizedTest
    @ValueSource(ints = {400, 403, 404})
    void standardWebhookStillRetriesAnAnswerThatDisablesOrFailsASlackOne(int answer) throws Exception {
        clock.set(NOW);
        User assignee = createUser(UserRole.USER);
        StandardEndpoint endpoint = createStandardEndpoint(assignee, "task.assigned");
        answer(answer);

        createTask(createUser(UserRole.ADMIN), assignee);

        WebhookDelivery pending = awaitAttempts(onlyDeliveryId(endpoint.id()), 1);
        assertThat(pending.getStatus()).isEqualTo(WebhookDeliveryStatus.PENDING);
        assertThat(pending.getLastStatusCode()).isEqualTo(answer);
        assertThat(pending.getNextAttemptAt()).isNotNull();
        assertThat(failingSince(endpoint.id())).isEqualTo(NOW);
        mockMvc.perform(get(WEBHOOK, assignee.getId(), endpoint.id()).with(asUser(assignee)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.enabled").value(true));
    }

    // Test events

    @Test
    void testEventReachesTheSlackUrlUnsignedAsTheTestMessageAndRecordsNoDelivery() throws Exception {
        User owner = createUser(UserRole.USER);
        SlackEndpoint endpoint = createSlackEndpoint(owner, "task.assigned");
        answer(200);

        sendTestEvent(owner, endpoint)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.delivered").value(true))
                .andExpect(jsonPath("$.statusCode").value(200))
                .andExpect(jsonPath("$.error").value(nullValue()));

        RecordedRequest request = nextRequest();
        assertThat(request.getMethod()).isEqualTo("POST");
        assertThat(request.getUrl().encodedPath()).isEqualTo(endpoint.path());
        assertUnsigned(request);
        assertThat(slackText(request)).isEqualTo(TEST_MESSAGE);
        assertThat(deliveryIds(endpoint.id())).isEmpty();
    }

    @Test
    void revokedAnswerToASlackTestEventIsReportedAndLeavesTheEndpointEnabled() throws Exception {
        User owner = createUser(UserRole.USER);
        SlackEndpoint endpoint = createSlackEndpoint(owner, "task.assigned");
        answer(404);

        sendTestEvent(owner, endpoint)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.delivered").value(false))
                .andExpect(jsonPath("$.statusCode").value(404));

        nextRequest();
        mockMvc.perform(get(WEBHOOK, owner.getId(), endpoint.id()).with(asUser(owner)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.enabled").value(true))
                .andExpect(jsonPath("$.disabledReason").value(nullValue()));
        assertThat(failingSince(endpoint.id())).isNull();
    }

    // Slack next to a standard webhook

    @Test
    void standardWebhookOfTheSameUserStillGetsTheSignedStandardPayloadOfTheSameEvent() throws Exception {
        User admin = createUser(UserRole.ADMIN);
        User assignee = createUser(UserRole.USER);
        SlackEndpoint slack = createSlackEndpoint(assignee, "task.assigned");
        StandardEndpoint standard = createStandardEndpoint(assignee, "task.assigned");
        answer(200);
        answer(204);

        CreatedTask task = createTask(admin, assignee);

        Map<String, RecordedRequest> byPath = List.of(nextRequest(), nextRequest()).stream()
                .collect(Collectors.toMap(request -> request.getUrl().encodedPath(), Function.identity()));
        assertThat(byPath).containsOnlyKeys(slack.path(), "/hooks");
        RecordedRequest slackRequest = byPath.get(slack.path());
        assertUnsigned(slackRequest);
        assertThat(slackText(slackRequest))
                .isEqualTo("*%s* assigned you *%s*: %s".formatted(admin.getDisplayName(), task.reference(), TITLE));
        RecordedRequest standardRequest = byPath.get("/hooks");
        UUID standardDelivery = onlyDeliveryId(standard.id());
        assertThat(standardRequest.getHeaders().get("webhook-id")).isEqualTo(standardDelivery.toString());
        assertThatNoException().isThrownBy(() -> new Webhook(standard.secret())
                .verify(standardRequest.getBody().utf8(), standardRequest.getHeaders().toMultimap()));
        JsonNode payload = jsonMapper.readTree(standardRequest.getBody().utf8());
        assertThat(payload.propertyNames()).containsExactly("type", "timestamp", "data");
        assertThat(payload.get("type").asString()).isEqualTo("task.assigned");
        assertThat(payload.get("data").get("task").get("reference").asString()).isEqualTo(task.reference());
        assertThat(payload.get("data").get("actor").get("displayName").asString()).isEqualTo(admin.getDisplayName());
        assertThat(awaitAttempts(onlyDeliveryId(slack.id()), 1).getStatus()).isEqualTo(WebhookDeliveryStatus.DELIVERED);
        assertThat(awaitAttempts(standardDelivery, 1).getStatus()).isEqualTo(WebhookDeliveryStatus.DELIVERED);
    }

    // Redelivery

    @Test
    void redeliveringASlackDeliverySendsTheSameMessageAgainUnsigned() throws Exception {
        User owner = createUser(UserRole.USER);
        SlackEndpoint endpoint = createSlackEndpoint(owner, "task.assigned");
        answer(400);
        createTask(createUser(UserRole.ADMIN), owner);
        UUID deliveryId = onlyDeliveryId(endpoint.id());
        RecordedRequest first = nextRequest();
        assertThat(awaitFinished(deliveryId).getStatus()).isEqualTo(WebhookDeliveryStatus.FAILED);
        answer(200);

        mockMvc.perform(post(REDELIVER, owner.getId(), endpoint.id(), deliveryId).with(asUser(owner)))
                .andExpect(status().isAccepted());

        RecordedRequest again = nextRequest();
        assertThat(again.getUrl().encodedPath()).isEqualTo(endpoint.path());
        assertThat(again.getBody()).isEqualTo(first.getBody());
        assertUnsigned(again);
        assertThat(awaitAttempts(deliveryId, 1).getStatus()).isEqualTo(WebhookDeliveryStatus.DELIVERED);
    }

    private void answer(int status) {
        receiver.enqueue(new MockResponse.Builder().code(status).build());
    }

    private String receiverUrl(String path) {
        return "http://127.0.0.1:" + receiver.getPort() + path;
    }

    private RecordedRequest nextRequest() throws InterruptedException {
        RecordedRequest request = receiver.takeRequest(DELIVERY_TIMEOUT.toSeconds(), TimeUnit.SECONDS);
        assertThat(request).as("request received by the Slack receiver").isNotNull();
        return request;
    }

    private static void assertUnsigned(RecordedRequest request) {
        assertThat(request.getHeaders().names())
                .as("headers of the request")
                .noneMatch(name -> name.toLowerCase(Locale.ROOT).startsWith("webhook-"));
    }

    private String slackText(RecordedRequest request) {
        JsonNode body = jsonMapper.readTree(request.getBody().utf8());

        assertThat(body.propertyNames()).containsExactly("text");
        return body.get("text").asString();
    }

    private ResultActions sendTestEvent(User owner, SlackEndpoint endpoint) throws Exception {
        return mockMvc.perform(post(TEST_EVENT, owner.getId(), endpoint.id()).with(asUser(owner)));
    }

    private SlackEndpoint createSlackEndpoint(User owner, String... events) throws Exception {
        String token = "token-" + UUID.randomUUID();
        JsonNode created = json(mockMvc.perform(post(WEBHOOKS, owner.getId())
                        .with(asUser(owner))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"kind\": \"SLACK\", \"url\": \"https://hooks.slack.com%s%s\", \"events\": %s}"
                                .formatted(SLACK_PATH, token, eventArray(events))))
                .andExpect(status().isCreated()));
        UUID id = UUID.fromString(created.get("id").asString());
        String path = SLACK_PATH + token;

        jdbcTemplate.update("UPDATE webhook_endpoints SET url = ? WHERE id = ?",
                webhookSecrets.encrypt(receiverUrl(path)), id);

        return new SlackEndpoint(id, path, token);
    }

    private StandardEndpoint createStandardEndpoint(User owner, String... events) throws Exception {
        JsonNode created = json(mockMvc.perform(post(WEBHOOKS, owner.getId())
                        .with(asUser(owner))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"url\": \"%s\", \"events\": %s}".formatted(receiverUrl("/hooks"),
                                eventArray(events))))
                .andExpect(status().isCreated()));

        return new StandardEndpoint(UUID.fromString(created.get("id").asString()), created.get("secret").asString());
    }

    private static String eventArray(String... events) {
        return Arrays.stream(events)
                .map(event -> "\"" + event + "\"")
                .collect(Collectors.joining(", ", "[", "]"));
    }

    private void markFailingSince(UUID endpointId, Instant failingSince) {
        jdbcTemplate.update("UPDATE webhook_endpoints SET failing_since = ? WHERE id = ?",
                Timestamp.from(failingSince), endpointId);
    }

    private Instant failingSince(UUID endpointId) {
        Timestamp failingSince = jdbcTemplate.queryForObject(
                "SELECT failing_since FROM webhook_endpoints WHERE id = ?", Timestamp.class, endpointId);
        return failingSince == null ? null : failingSince.toInstant();
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

    private List<UUID> deliveryIds(UUID endpointId) {
        return jdbcTemplate.queryForList(
                "SELECT id FROM webhook_deliveries WHERE endpoint_id = ? ORDER BY created_at, id",
                UUID.class,
                endpointId
        );
    }

    private UUID onlyDeliveryId(UUID endpointId) {
        List<UUID> ids = deliveryIds(endpointId);
        assertThat(ids).hasSize(1);
        return ids.getFirst();
    }

    private CreatedTask createTask(User admin, User assignee) throws Exception {
        return createTask(admin, assignee, TITLE, Instant.parse("2170-01-01T10:00:00Z"));
    }

    private CreatedTask createTask(User admin, User assignee, String title, Instant dueAt) throws Exception {
        String reference = "SL-" + UUID.randomUUID().toString().substring(0, 8);
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("reference", reference);
        body.put("title", title);
        body.put("dueAt", dueAt.toString());
        body.put("assignedTo", assignee == null ? null : assignee.getId());

        JsonNode created = json(mockMvc.perform(post(TASKS)
                        .with(asAdmin(admin))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(jsonMapper.writeValueAsString(body)))
                .andExpect(status().isCreated()));

        return new CreatedTask(UUID.fromString(created.get("id").asString()), reference);
    }

    private void comment(User author, CreatedTask task, String body) throws Exception {
        mockMvc.perform(post(TASKS + "/{id}/comments", task.id())
                        .with(asUser(author))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(jsonMapper.writeValueAsString(Map.of("body", body))))
                .andExpect(status().isCreated());
    }

    private JsonNode json(ResultActions result) throws Exception {
        return jsonMapper.readTree(result.andReturn().getResponse().getContentAsString());
    }

    private User createUser(UserRole role) {
        User user = new User();

        user.setEmail("slack-delivery-" + UUID.randomUUID() + "@example.com");
        user.setPasswordHash(passwordEncoder.encode("password"));
        user.setEmailVerifiedAt(Instant.now());
        user.setDisplayName("Slack " + role + " " + UUID.randomUUID().toString().substring(0, 6));
        user.setRoles(EnumSet.of(UserRole.USER, role));

        return userRepository.saveAndFlush(user);
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

    private static InetAddress loopback() {
        try {
            return InetAddress.getByAddress(new byte[]{127, 0, 0, 1});
        } catch (IOException invalid) {
            throw new IllegalArgumentException(invalid);
        }
    }
}
