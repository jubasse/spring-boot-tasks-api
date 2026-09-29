package io.julienmetral.tasks.realtime.sse;

import org.junit.jupiter.api.Test;
import org.springframework.web.servlet.mvc.method.annotation.ResponseBodyEmitter;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.time.Duration;
import java.util.UUID;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

class StreamEventTest {

    private static final UUID ID = UUID.fromString("00000000-0000-0000-0000-0000000000e1");

    @Test
    void notificationIsWrittenWithItsIdItsTypeAsEventNameAndItsJson() {
        StreamEvent event = StreamEvent.notification(ID, "task.assigned", "{\"type\":\"task.assigned\"}");

        assertThat(text(event.toSse()))
                .isEqualTo("id:" + ID + "\nevent:task.assigned\ndata:{\"type\":\"task.assigned\"}\n\n");
    }

    // Browsers dispatch no event without data
    @Test
    void resyncHasAnEventNameAndDataButNoId() {
        assertThat(text(StreamEvent.resync().toSse())).isEqualTo("event:resync\ndata:{\"type\":\"resync\"}\n\n");
    }

    @Test
    void reconnectDelayIsWrittenAsRetryInMilliseconds() {
        assertThat(text(StreamEvent.reconnectAfter(Duration.ofMillis(4_321)).toSse())).isEqualTo("retry:4321\n\n");
    }

    @Test
    void eventBuiltTwiceIsWrittenTheSameEachTime() {
        StreamEvent event = StreamEvent.notification(ID, "task.assigned", "{}");

        assertThat(text(event.toSse())).isEqualTo(text(event.toSse()));
    }

    static String text(SseEmitter.SseEventBuilder builder) {
        return builder.build().stream()
                .map(ResponseBodyEmitter.DataWithMediaType::getData)
                .map(Object::toString)
                .collect(Collectors.joining());
    }
}
