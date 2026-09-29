package io.julienmetral.tasks.realtime.messaging;

import io.julienmetral.tasks.identity.security.UserStatusCacheEviction;
import io.julienmetral.tasks.realtime.sse.NotificationStreams;
import io.julienmetral.tasks.realtime.stomp.StompSessions;
import io.julienmetral.tasks.realtime.stomp.TaskRooms;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.amqp.rabbit.listener.AsyncConsumerRestartedEvent;
import org.springframework.amqp.rabbit.listener.SimpleMessageListenerContainer;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.verifyNoMoreInteractions;

@ExtendWith(MockitoExtension.class)
class RealtimeListenerTest {

    private static final UUID USER_ID = UUID.fromString("00000000-0000-0000-0000-000000000001");

    @Mock
    private NotificationStreams notificationStreams;

    @Mock
    private TaskRooms taskRooms;

    @Mock
    private StompSessions stompSessions;

    @Mock
    private UserStatusCacheEviction userStatusCacheEviction;

    private RealtimeListener listener;

    @BeforeEach
    void createListener() {
        listener = new RealtimeListener(notificationStreams, taskRooms, stompSessions, userStatusCacheEviction);
    }

    @Test
    void notificationGoesToTheStreamsUnderItsMessageId() {
        UUID messageId = UUID.fromString("00000000-0000-0000-0000-0000000000e1");
        UserNotification notification = new UserNotification(USER_ID, "task.assigned", Instant.now(), Map.of());

        listener.onNotification(notification, messageId.toString());

        verify(notificationStreams).deliver(messageId, notification);
        verifyNoInteractions(userStatusCacheEviction, taskRooms);
    }

    @Test
    void taskRoomEventGoesToTheRoomsUnderItsMessageId() {
        UUID messageId = UUID.fromString("00000000-0000-0000-0000-0000000000e2");
        TaskRoomEvent event = new TaskRoomEvent(UUID.randomUUID(), UUID.randomUUID(), "UPDATED", USER_ID,
                Instant.parse("2026-03-04T05:06:07Z"));

        listener.onTaskRoomEvent(event, messageId.toString());

        verify(taskRooms).publish(messageId, event);
        verifyNoInteractions(notificationStreams, stompSessions, userStatusCacheEviction);
    }

    @Test
    void accountChangeEvictsTheCachedStatusThenClosesTheStreamsAndSessionsItNoLongerAllows() {
        listener.onAccountStatusChanged(new AccountStatusChanged(USER_ID));

        InOrder inOrder = inOrder(userStatusCacheEviction, notificationStreams);
        inOrder.verify(userStatusCacheEviction).evict(USER_ID);
        inOrder.verify(notificationStreams).closeIfNoLongerActive(USER_ID);
        verify(stompSessions).closeIfNoLongerActive(USER_ID);
        verifyNoMoreInteractions(notificationStreams, stompSessions);
        verifyNoInteractions(taskRooms);
    }

    @Test
    void restartOfTheRealtimeConsumerResyncsEveryStream() {
        listener.onConsumerRestarted(restartOf("realtime"));

        verify(notificationStreams).resyncAll();
    }

    @Test
    void restartOfAnotherListenersConsumerChangesNothing() {
        listener.onConsumerRestarted(restartOf("mail"));

        verifyNoInteractions(notificationStreams);
    }

    private static AsyncConsumerRestartedEvent restartOf(String listenerId) {
        SimpleMessageListenerContainer container = new SimpleMessageListenerContainer();
        container.setListenerId(listenerId);

        return new AsyncConsumerRestartedEvent(container, new Object(), new Object());
    }
}
