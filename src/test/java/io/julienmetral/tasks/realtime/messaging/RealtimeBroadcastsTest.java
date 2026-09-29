package io.julienmetral.tasks.realtime.messaging;

import io.julienmetral.tasks.identity.events.AccountStateChanged;
import io.julienmetral.tasks.messaging.services.Outbox;
import io.julienmetral.tasks.notification.entities.WebhookEvent;
import io.julienmetral.tasks.notification.events.TaskNotificationCreated;
import io.julienmetral.tasks.task.entities.TaskEventType;
import io.julienmetral.tasks.task.events.TaskDeleted;
import io.julienmetral.tasks.task.events.TaskEventRecorded;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.SimpleTransactionStatus;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Map;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class RealtimeBroadcastsTest {

    private static final Instant NOW = Instant.parse("2026-03-04T05:06:07Z");

    private static final UUID USER_ID = UUID.fromString("00000000-0000-0000-0000-000000000001");

    private static final UUID TASK_ID = UUID.fromString("00000000-0000-0000-0000-0000000000b1");

    @Mock
    private Outbox outbox;

    @Mock
    private PlatformTransactionManager transactionManager;

    private RealtimeBroadcasts broadcasts;

    @BeforeEach
    void createBroadcasts() {
        broadcasts = new RealtimeBroadcasts(outbox, new TransactionTemplate(transactionManager),
                Clock.fixed(NOW, ZoneOffset.UTC));
    }

    @AfterEach
    void leaveTheTransaction() {
        TransactionSynchronizationManager.setActualTransactionActive(false);
    }

    @Test
    void taskNotificationIsBroadcastToEveryInstanceWithItsTypeTheTimeAndItsData() {
        Map<String, Object> data = Map.of("task", Map.of("reference", "OPS-12"));

        broadcasts.onTaskNotification(new TaskNotificationCreated(WebhookEvent.TASK_DUE_SOON, USER_ID, data));

        verify(outbox).broadcast("tasks.realtime", new UserNotification(USER_ID, "task.due_soon", NOW, data));
    }

    @Test
    void recordedTaskEventIsBroadcastToTheTaskRoomWithItsRowTypeActorAndTime() {
        UUID eventId = UUID.fromString("00000000-0000-0000-0000-0000000000c1");
        Instant occurredAt = Instant.parse("2026-03-04T05:00:00Z");

        broadcasts.onTaskEventRecorded(
                new TaskEventRecorded(TASK_ID, eventId, TaskEventType.COMMENT_ADDED, USER_ID, occurredAt));

        verify(outbox).broadcast("tasks.realtime",
                new TaskRoomEvent(TASK_ID, eventId, "COMMENT_ADDED", USER_ID, occurredAt));
        verifyNoInteractions(transactionManager);
    }

    @Test
    void deletedTaskIsBroadcastToItsRoomWithoutAHistoryRowAtTheCurrentTime() {
        broadcasts.onTaskDeleted(new TaskDeleted(TASK_ID, "OPS-12", "Rotate the keys", UUID.randomUUID(), USER_ID));

        verify(outbox).broadcast("tasks.realtime", new TaskRoomEvent(TASK_ID, null, "DELETED", USER_ID, NOW));
        verifyNoInteractions(transactionManager);
    }

    @Test
    void accountChangeInsideATransactionIsBroadcastInThatTransaction() {
        TransactionSynchronizationManager.setActualTransactionActive(true);

        broadcasts.onAccountStateChanged(new AccountStateChanged(USER_ID));

        verify(outbox).broadcast("tasks.realtime", new AccountStatusChanged(USER_ID));
        verifyNoInteractions(transactionManager);
    }

    @Test
    void accountChangePublishedOutsideATransactionIsBroadcastInATransactionOfItsOwn() {
        SimpleTransactionStatus transaction = new SimpleTransactionStatus();
        when(transactionManager.getTransaction(any())).thenReturn(transaction);

        broadcasts.onAccountStateChanged(new AccountStateChanged(USER_ID));

        InOrder inOrder = inOrder(transactionManager, outbox);
        inOrder.verify(transactionManager).getTransaction(any());
        inOrder.verify(outbox).broadcast("tasks.realtime", new AccountStatusChanged(USER_ID));
        inOrder.verify(transactionManager).commit(transaction);
    }
}
