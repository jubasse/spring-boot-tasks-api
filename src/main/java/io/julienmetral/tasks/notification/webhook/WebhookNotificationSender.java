package io.julienmetral.tasks.notification.webhook;

import io.julienmetral.tasks.notification.entities.WebhookEndpoint;
import io.julienmetral.tasks.notification.entities.WebhookKind;
import io.julienmetral.tasks.notification.events.TaskNotificationCreated;
import io.julienmetral.tasks.notification.repositories.WebhookEndpointRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;
import tools.jackson.databind.json.JsonMapper;

import java.time.Clock;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Turns each task notification into webhook deliveries for the recipient's endpoints subscribed to its event; the
 * endpoint's events replace the email switches. Runs inside the publisher's transaction.
 * <p>
 * The payload follows the Standard Webhooks shape, {@code {"type", "timestamp", "data"}}, or is a Slack message for a
 * Slack endpoint.
 */
@Component
@RequiredArgsConstructor
public class WebhookNotificationSender {

    private final WebhookEndpointRepository endpointRepository;
    private final WebhookDeliveryService deliveryService;
    private final SlackMessages slackMessages;
    private final JsonMapper jsonMapper;
    private final Clock clock;

    @EventListener
    public void onNotification(TaskNotificationCreated notification) {
        List<WebhookEndpoint> endpoints =
                endpointRepository.findEnabledSubscribedTo(notification.recipientId(), notification.event());

        if (endpoints.isEmpty()) {
            return;
        }

        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("type", notification.event().type());
        payload.put("timestamp", Instant.now(clock).toString());
        payload.put("data", notification.data());

        String standardPayload = jsonMapper.writeValueAsString(payload);

        for (WebhookEndpoint endpoint : endpoints) {
            deliveryService.schedule(endpoint, notification.event(), endpoint.getKind() == WebhookKind.SLACK
                    ? slackMessages.render(notification.event(), notification.data())
                    : standardPayload);
        }
    }
}
