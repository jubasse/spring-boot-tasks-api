package io.julienmetral.tasks.realtime.stomp;

import io.julienmetral.tasks.realtime.messaging.TaskRoomEvent;
import lombok.RequiredArgsConstructor;
import org.springframework.messaging.simp.SimpMessageSendingOperations;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.UUID;

/** Sends room events to the clients subscribed on this instance to {@value #TOPIC_PREFIX}{task id}. */
@Component
@RequiredArgsConstructor
public class TaskRooms {

    public static final String TOPIC_PREFIX = "/topic/tasks/";

    // A STOMP header of every frame: the broadcast's id, the same on every instance, for clients to drop a duplicate
    static final String EVENT_ID_HEADER = "event-id";

    private final SimpMessageSendingOperations messagingTemplate;

    public void publish(UUID eventId, TaskRoomEvent event) {
        messagingTemplate.convertAndSend(
                TOPIC_PREFIX + event.taskId(),
                event,
                Map.of(EVENT_ID_HEADER, eventId.toString())
        );
    }
}
