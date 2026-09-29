package io.julienmetral.tasks.realtime.sse;

import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.time.Duration;
import java.util.UUID;

/**
 * An event waiting to be written to a stream. Immutable, unlike Spring's event builder, whose {@code build()} appends
 * to it: one event can go to several streams of the same user.
 */
record StreamEvent(String id, String name, String data, Duration reconnectDelay) {

    static final String RESYNC = "resync";

    static StreamEvent notification(UUID id, String type, String json) {
        return new StreamEvent(id.toString(), type, json, null);
    }

    // Browsers dispatch no event without data
    static StreamEvent resync() {
        return new StreamEvent(null, RESYNC, "{\"type\":\"" + RESYNC + "\"}", null);
    }

    static StreamEvent reconnectAfter(Duration delay) {
        return new StreamEvent(null, null, null, delay);
    }

    SseEmitter.SseEventBuilder toSse() {
        SseEmitter.SseEventBuilder event = SseEmitter.event();

        if (id != null) {
            event.id(id);
        }
        if (name != null) {
            event.name(name);
        }
        if (data != null) {
            event.data(data);
        }
        if (reconnectDelay != null) {
            event.reconnectTime(reconnectDelay.toMillis());
        }

        return event;
    }
}
