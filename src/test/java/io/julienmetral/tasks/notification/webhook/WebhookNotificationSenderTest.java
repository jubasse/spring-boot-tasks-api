package io.julienmetral.tasks.notification.webhook;

import io.julienmetral.tasks.notification.entities.WebhookEndpoint;
import io.julienmetral.tasks.notification.entities.WebhookEvent;
import io.julienmetral.tasks.notification.entities.WebhookKind;
import io.julienmetral.tasks.notification.events.TaskNotificationCreated;
import io.julienmetral.tasks.notification.repositories.WebhookEndpointRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class WebhookNotificationSenderTest {

    private static final Instant NOW = Instant.parse("2026-03-04T05:06:07Z");
    private static final UUID RECIPIENT_ID = UUID.fromString("00000000-0000-0000-0000-000000000001");
    private static final UUID ACTOR_ID = UUID.fromString("00000000-0000-0000-0000-000000000002");
    private static final UUID TASK_ID = UUID.fromString("00000000-0000-0000-0000-00000000000a");

    private final JsonMapper jsonMapper = JsonMapper.builder().build();

    @Mock
    private WebhookEndpointRepository endpointRepository;

    @Mock
    private WebhookDeliveryService deliveryService;

    private WebhookNotificationSender sender;

    @BeforeEach
    void createSender() {
        sender = new WebhookNotificationSender(endpointRepository, deliveryService, new SlackMessages(jsonMapper),
                jsonMapper, Clock.fixed(NOW, ZoneOffset.UTC));
    }

    @Test
    void recipientWithoutAnEndpointSubscribedToTheEventGetsNoDelivery() {
        when(endpointRepository.findEnabledSubscribedTo(RECIPIENT_ID, WebhookEvent.TASK_ASSIGNED)).thenReturn(List.of());

        sender.onNotification(assigned(data()));

        verifyNoInteractions(deliveryService);
    }

    @Test
    void webhookEndpointGetsAStandardWebhooksPayloadOfTheTypeTheTimeAndTheData() {
        WebhookEndpoint endpoint = endpoint(WebhookKind.WEBHOOK);
        subscribed(endpoint);

        sender.onNotification(assigned(data()));

        JsonNode payload = jsonMapper.readTree(scheduledPayload(endpoint));
        assertThat(payload.propertyNames()).containsExactly("type", "timestamp", "data");
        assertThat(payload).isEqualTo(jsonMapper.valueToTree(Map.of(
                "type", "task.assigned",
                "timestamp", "2026-03-04T05:06:07Z",
                "data", Map.of(
                        "task", Map.of("id", TASK_ID.toString(), "reference", "OPS-12", "title", "Renew certificates"),
                        "actor", Map.of("id", ACTOR_ID.toString(), "displayName", "Ada")
                )
        )));
    }

    @Test
    void dataIsSentInTheOrderAndWithTheNullsTheNotificationHas() {
        WebhookEndpoint endpoint = endpoint(WebhookKind.WEBHOOK);
        when(endpointRepository.findEnabledSubscribedTo(RECIPIENT_ID, WebhookEvent.TASK_OVERDUE))
                .thenReturn(List.of(endpoint));
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("task", Map.of("id", TASK_ID, "reference", "OPS-12", "title", "Renew certificates"));
        data.put("actor", null);
        data.put("dueAt", "2026-03-01T09:00:00Z");

        sender.onNotification(new TaskNotificationCreated(WebhookEvent.TASK_OVERDUE, RECIPIENT_ID, data));

        JsonNode sentData = jsonMapper.readTree(scheduledPayload(endpoint, WebhookEvent.TASK_OVERDUE)).get("data");
        assertThat(sentData.propertyNames()).containsExactly("task", "actor", "dueAt");
        assertThat(sentData.get("actor").isNull()).isTrue();
    }

    @Test
    void everyWebhookEndpointOfTheRecipientGetsTheSamePayload() {
        WebhookEndpoint first = endpoint(WebhookKind.WEBHOOK);
        WebhookEndpoint second = endpoint(WebhookKind.WEBHOOK);
        subscribed(first, second);

        sender.onNotification(assigned(data()));

        assertThat(scheduledPayload(first)).isEqualTo(scheduledPayload(second));
    }

    @Test
    void slackEndpointGetsASlackMessageBuiltFromTheSameData() {
        WebhookEndpoint slack = endpoint(WebhookKind.SLACK);
        subscribed(slack);

        sender.onNotification(assigned(data()));

        assertThat(scheduledPayload(slack))
                .isEqualTo(new SlackMessages(jsonMapper).render(WebhookEvent.TASK_ASSIGNED, data()))
                .isEqualTo("{\"text\":\"*Ada* assigned you *OPS-12*: Renew certificates\"}");
    }

    @Test
    void slackAndWebhookEndpointsOfOneRecipientEachGetTheirOwnKindOfBody() {
        WebhookEndpoint webhook = endpoint(WebhookKind.WEBHOOK);
        WebhookEndpoint slack = endpoint(WebhookKind.SLACK);
        subscribed(webhook, slack);

        sender.onNotification(assigned(data()));

        assertThat(jsonMapper.readTree(scheduledPayload(webhook)).get("type").asString()).isEqualTo("task.assigned");
        assertThat(jsonMapper.readTree(scheduledPayload(slack)).propertyNames()).containsExactly("text");
        verify(deliveryService, times(2)).schedule(any(), eq(WebhookEvent.TASK_ASSIGNED), anyString());
    }

    private static TaskNotificationCreated assigned(Map<String, Object> data) {
        return new TaskNotificationCreated(WebhookEvent.TASK_ASSIGNED, RECIPIENT_ID, data);
    }

    // The shape TaskNotificationPublisher builds: task, then actor, then the details of the event
    private static Map<String, Object> data() {
        Map<String, Object> task = new LinkedHashMap<>();
        task.put("id", TASK_ID);
        task.put("reference", "OPS-12");
        task.put("title", "Renew certificates");

        Map<String, Object> actor = new LinkedHashMap<>();
        actor.put("id", ACTOR_ID);
        actor.put("displayName", "Ada");

        Map<String, Object> data = new LinkedHashMap<>();
        data.put("task", task);
        data.put("actor", actor);
        return data;
    }

    private static WebhookEndpoint endpoint(WebhookKind kind) {
        WebhookEndpoint endpoint = new WebhookEndpoint();
        endpoint.setId(UUID.randomUUID());
        endpoint.setKind(kind);
        return endpoint;
    }

    private void subscribed(WebhookEndpoint... endpoints) {
        when(endpointRepository.findEnabledSubscribedTo(RECIPIENT_ID, WebhookEvent.TASK_ASSIGNED))
                .thenReturn(List.of(endpoints));
    }

    private String scheduledPayload(WebhookEndpoint endpoint) {
        return scheduledPayload(endpoint, WebhookEvent.TASK_ASSIGNED);
    }

    private String scheduledPayload(WebhookEndpoint endpoint, WebhookEvent event) {
        ArgumentCaptor<String> payload = ArgumentCaptor.forClass(String.class);
        verify(deliveryService).schedule(eq(endpoint), eq(event), payload.capture());
        return payload.getValue();
    }
}
