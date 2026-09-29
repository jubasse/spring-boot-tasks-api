package io.julienmetral.tasks.realtime.messaging;

import io.julienmetral.tasks.identity.events.AccountStateChanged;
import io.julienmetral.tasks.messaging.services.Outbox;
import io.julienmetral.tasks.notification.events.TaskNotificationCreated;
import io.julienmetral.tasks.realtime.RealtimeConfiguration;
import io.julienmetral.tasks.task.events.TaskDeleted;
import io.julienmetral.tasks.task.events.TaskEventRecorded;
import lombok.RequiredArgsConstructor;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Clock;

/**
 * Broadcasts to every instance, through the outbox and inside the transaction of the change: an event exists only if
 * the change commits, and leaves once it has.
 */
@Component
@RequiredArgsConstructor
public class RealtimeBroadcasts {

    private final Outbox outbox;
    private final TransactionTemplate transactionTemplate;
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
    public void onTaskEventRecorded(TaskEventRecorded event) {
        outbox.broadcast(RealtimeConfiguration.EXCHANGE, new TaskRoomEvent(
                event.taskId(), event.eventId(), event.type().name(), event.actorId(), event.occurredAt()
        ));
    }

    // A deletion writes no history row
    @EventListener
    public void onTaskDeleted(TaskDeleted event) {
        outbox.broadcast(RealtimeConfiguration.EXCHANGE, new TaskRoomEvent(
                event.taskId(), null, TaskRoomEvent.DELETED, event.actorId(), clock.instant()
        ));
    }

    // A change published outside a transaction is already done: its broadcast gets a transaction of its own. The
    // outbox refuses to run without one, which made such a publisher fail.
    @EventListener
    public void onAccountStateChanged(AccountStateChanged change) {
        AccountStatusChanged message = new AccountStatusChanged(change.userId());

        if (TransactionSynchronizationManager.isActualTransactionActive()) {
            outbox.broadcast(RealtimeConfiguration.EXCHANGE, message);
        } else {
            transactionTemplate.executeWithoutResult(status -> outbox.broadcast(RealtimeConfiguration.EXCHANGE, message));
        }
    }
}
