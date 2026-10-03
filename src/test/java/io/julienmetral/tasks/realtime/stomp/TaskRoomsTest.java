package io.julienmetral.tasks.realtime.stomp;

import io.julienmetral.tasks.realtime.messaging.TaskRoomEvent;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.messaging.Message;
import org.springframework.messaging.converter.JacksonJsonMessageConverter;
import org.springframework.messaging.simp.SimpMessageHeaderAccessor;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class TaskRoomsTest {

    private static final UUID TASK_ID = UUID.fromString("00000000-0000-0000-0000-0000000000b1");

    private static final UUID EVENT_ID = UUID.fromString("00000000-0000-0000-0000-0000000000e1");

    private final JsonMapper jsonMapper = JsonMapper.builder().build();

    private final List<Message<?>> sent = new ArrayList<>();

    private TaskRooms taskRooms;

    @BeforeEach
    void createRooms() {
        SimpMessagingTemplate template = new SimpMessagingTemplate((message, timeout) -> sent.add(message));
        template.setMessageConverter(new JacksonJsonMessageConverter(jsonMapper));
        taskRooms = new TaskRooms(template);
    }

    @Test
    void eventGoesToTheRoomOfItsTaskWithTheBroadcastIdAsAStompHeader() {
        taskRooms.publish(EVENT_ID, new TaskRoomEvent(TASK_ID, UUID.randomUUID(), "UPDATED", UUID.randomUUID(),
                Instant.parse("2026-03-04T05:06:07Z")));

        assertThat(sent).singleElement().satisfies(message -> {
            SimpMessageHeaderAccessor headers = SimpMessageHeaderAccessor.wrap(message);
            assertThat(headers.getDestination()).isEqualTo("/topic/tasks/" + TASK_ID);
            assertThat(headers.getFirstNativeHeader("event-id")).isEqualTo(EVENT_ID.toString());
        });
    }

    @Test
    void eventIsSentAsJsonWithItsFields() {
        UUID historyRowId = UUID.randomUUID();
        UUID actorId = UUID.randomUUID();

        taskRooms.publish(EVENT_ID, new TaskRoomEvent(TASK_ID, historyRowId, "COMMENT_ADDED", actorId,
                Instant.parse("2026-03-04T05:06:07Z")));

        JsonNode json = jsonMapper.readTree((byte[]) sent.getFirst().getPayload());
        assertThat(json.propertyNames())
                .containsExactlyInAnyOrder("taskId", "eventId", "type", "actorId", "occurredAt");
        assertThat(json.path("taskId").asString()).isEqualTo(TASK_ID.toString());
        assertThat(json.path("eventId").asString()).isEqualTo(historyRowId.toString());
        assertThat(json.path("type").asString()).isEqualTo("COMMENT_ADDED");
        assertThat(json.path("actorId").asString()).isEqualTo(actorId.toString());
        assertThat(json.path("occurredAt").asString()).isEqualTo("2026-03-04T05:06:07Z");
    }

    @Test
    void deletionIsSentWithANullEventId() {
        taskRooms.publish(EVENT_ID, new TaskRoomEvent(TASK_ID, null, TaskRoomEvent.DELETED, UUID.randomUUID(),
                Instant.parse("2026-03-04T05:06:07Z")));

        JsonNode json = jsonMapper.readTree((byte[]) sent.getFirst().getPayload());
        assertThat(json.path("type").asString()).isEqualTo("DELETED");
        assertThat(json.get("eventId").isNull()).isTrue();
    }
}
