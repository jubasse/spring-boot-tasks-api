package io.julienmetral.tasks.realtime.messaging;

import io.julienmetral.tasks.identity.events.AccountStateChanged;
import io.julienmetral.tasks.messaging.services.Outbox;
import io.julienmetral.tasks.notification.events.TaskNotificationCreated;
import io.julienmetral.tasks.realtime.RealtimeConfiguration;
import lombok.RequiredArgsConstructor;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

import java.time.Clock;

/**
 * Broadcasts to every instance, through the outbox and inside the transaction of the change: an event exists only if
 * the change commits, and leaves once it has.
 */
@Component
@RequiredArgsConstructor
public class RealtimeBroadcasts {

    private final Outbox outbox;
    private final Clock clock;

    @EventListener
    public void onTaskNotification(TaskNotificationCreated notification) {
        outbox.broadcast(RealtimeConfiguration.EXCHANGE, new UserNotification(
                notification.recipientId(),
                notification.event().type(),
                clock.instant(),
                notification.data()
        ));
    }

    @EventListener
    public void onAccountStateChanged(AccountStateChanged change) {
        outbox.broadcast(RealtimeConfiguration.EXCHANGE, new AccountStatusChanged(change.userId()));
    }
}
