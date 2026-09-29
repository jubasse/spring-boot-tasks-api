package io.julienmetral.tasks.realtime.messaging;

import io.julienmetral.tasks.identity.security.UserStatusCacheEviction;
import io.julienmetral.tasks.realtime.sse.NotificationStreams;
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
    private UserStatusCacheEviction userStatusCacheEviction;

    private RealtimeListener listener;

    @BeforeEach
    void createListener() {
        listener = new RealtimeListener(notificationStreams, userStatusCacheEviction);
    }

    @Test
    void notificationGoesToTheStreamsUnderItsMessageId() {
        UUID messageId = UUID.fromString("00000000-0000-0000-0000-0000000000e1");
        UserNotification notification = new UserNotification(USER_ID, "task.assigned", Instant.now(), Map.of());

        listener.onNotification(notification, messageId.toString());

        verify(notificationStreams).deliver(messageId, notification);
        verifyNoInteractions(userStatusCacheEviction);
    }

    @Test
    void accountChangeEvictsTheCachedStatusThenClosesTheStreamsItNoLongerAllows() {
        listener.onAccountStatusChanged(new AccountStatusChanged(USER_ID));

        InOrder inOrder = inOrder(userStatusCacheEviction, notificationStreams);
        inOrder.verify(userStatusCacheEviction).evict(USER_ID);
        inOrder.verify(notificationStreams).closeIfNoLongerActive(USER_ID);
        verifyNoMoreInteractions(notificationStreams);
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
