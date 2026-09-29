package io.julienmetral.tasks.realtime.messaging;

import io.julienmetral.tasks.identity.security.UserStatusCacheEviction;
import io.julienmetral.tasks.realtime.RealtimeConfiguration;
import io.julienmetral.tasks.realtime.sse.NotificationStreams;
import io.julienmetral.tasks.realtime.stomp.StompSessions;
import io.julienmetral.tasks.realtime.stomp.TaskRooms;
import lombok.RequiredArgsConstructor;
import org.springframework.amqp.rabbit.annotation.RabbitHandler;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.amqp.rabbit.listener.AbstractMessageListenerContainer;
import org.springframework.amqp.rabbit.listener.AsyncConsumerRestartedEvent;
import org.springframework.amqp.support.AmqpHeaders;
import org.springframework.context.event.EventListener;
import org.springframework.messaging.handler.annotation.Header;
import org.springframework.stereotype.Component;

import java.util.UUID;

/** Receives the real-time events on this instance's own queue and hands them to the local streams and rooms. */
@Component
@RequiredArgsConstructor
@RabbitListener(
        id = RealtimeListener.ID,
        queues = "#{" + RealtimeConfiguration.QUEUE_BEAN + ".name}",
        containerFactory = RealtimeConfiguration.LISTENER_FACTORY
)
public class RealtimeListener {

    static final String ID = "realtime";

    private final NotificationStreams notificationStreams;
    private final TaskRooms taskRooms;
    private final StompSessions stompSessions;
    private final UserStatusCacheEviction userStatusCacheEviction;

    // The message id is the outbox row's, the same on every instance, so it serves as the event id
    @RabbitHandler
    public void onNotification(UserNotification notification, @Header(AmqpHeaders.MESSAGE_ID) String messageId) {
        notificationStreams.deliver(UUID.fromString(messageId), notification);
    }

    @RabbitHandler
    public void onTaskRoomEvent(TaskRoomEvent event, @Header(AmqpHeaders.MESSAGE_ID) String messageId) {
        taskRooms.publish(UUID.fromString(messageId), event);
    }

    // The instance that made the change evicted its own entry after the commit; this reaches the others, whose
    // entries would otherwise last until their time to live
    @RabbitHandler
    public void onAccountStatusChanged(AccountStatusChanged change) {
        userStatusCacheEviction.evict(change.userId());
        notificationStreams.closeIfNoLongerActive(change.userId());
        stompSessions.closeIfNoLongerActive(change.userId());
    }

    // The queue went with the lost connection, and the events published meanwhile with it
    @EventListener
    public void onConsumerRestarted(AsyncConsumerRestartedEvent event) {
        if (event.getSource() instanceof AbstractMessageListenerContainer container
                && ID.equals(container.getListenerId())) {
            notificationStreams.resyncAll();
        }
    }
}
