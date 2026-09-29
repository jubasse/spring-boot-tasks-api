package io.julienmetral.tasks.realtime.messaging;

import io.julienmetral.tasks.identity.events.AccountStateChanged;
import io.julienmetral.tasks.messaging.services.Outbox;
import io.julienmetral.tasks.notification.entities.WebhookEvent;
import io.julienmetral.tasks.notification.events.TaskNotificationCreated;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Map;
import java.util.UUID;

import static org.mockito.Mockito.verify;

@ExtendWith(MockitoExtension.class)
class RealtimeBroadcastsTest {

    private static final Instant NOW = Instant.parse("2026-03-04T05:06:07Z");

    private static final UUID USER_ID = UUID.fromString("00000000-0000-0000-0000-000000000001");

    @Mock
    private Outbox outbox;

    private RealtimeBroadcasts broadcasts;

    @BeforeEach
    void createBroadcasts() {
        broadcasts = new RealtimeBroadcasts(outbox, Clock.fixed(NOW, ZoneOffset.UTC));
    }

    @Test
    void taskNotificationIsBroadcastToEveryInstanceWithItsTypeTheTimeAndItsData() {
        Map<String, Object> data = Map.of("task", Map.of("reference", "OPS-12"));

        broadcasts.onTaskNotification(new TaskNotificationCreated(WebhookEvent.TASK_DUE_SOON, USER_ID, data));

        verify(outbox).broadcast("tasks.realtime", new UserNotification(USER_ID, "task.due_soon", NOW, data));
    }

    @Test
    void accountChangeIsBroadcastToEveryInstance() {
        broadcasts.onAccountStateChanged(new AccountStateChanged(USER_ID));

        verify(outbox).broadcast("tasks.realtime", new AccountStatusChanged(USER_ID));
    }
}
