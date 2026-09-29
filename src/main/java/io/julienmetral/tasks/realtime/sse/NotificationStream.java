package io.julienmetral.tasks.realtime.sse;

import lombok.Getter;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.time.Duration;
import java.util.UUID;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

/**
 * One open stream. Events wait in a bounded buffer, and a virtual thread of its own writes them, so a slow client
 * never holds up the listener that hands events to every stream; a client too slow to empty its buffer is
 * disconnected, and replays what it missed when it reconnects.
 */
@Slf4j
class NotificationStream {

    // Never written: it only wakes the writer so that it sees the stream closed
    private static final StreamEvent WAKE_UP = new StreamEvent(null, null, null, null);

    @Getter
    private final UUID userId;
    private final SseEmitter emitter;
    private final BlockingQueue<StreamEvent> buffer;
    private final Duration heartbeat;
    private final Consumer<NotificationStream> onClose;
    private final AtomicBoolean closed = new AtomicBoolean();

    NotificationStream(UUID userId, SseEmitter emitter, int bufferSize, Duration heartbeat,
                       Consumer<NotificationStream> onClose) {
        this.userId = userId;
        this.emitter = emitter;
        this.buffer = new ArrayBlockingQueue<>(bufferSize);
        this.heartbeat = heartbeat;
        this.onClose = onClose;

        // Spring calls these on its own threads: when the client leaves, the stream times out, or it completes
        emitter.onCompletion(this::release);
        emitter.onError(error -> release());
        emitter.onTimeout(this::close);
    }

    void start() {
        Thread.ofVirtual().name("notification-stream").start(this::writeEvents);
    }

    void send(StreamEvent event) {
        if (!closed.get() && !buffer.offer(event)) {
            log.debug("Notification stream of {} closed: its client did not keep up", userId);
            close();
        }
    }

    /** Ends the response; the client reconnects unless it chose to stop. */
    void close() {
        if (release()) {
            try {
                emitter.complete();
            } catch (IllegalStateException alreadyCompleted) {
                // The client left at the same moment
            }
        }
    }

    boolean isClosed() {
        return closed.get();
    }

    // true for the first caller only. Wakes the writer with an event rather than an interrupt: a write by an
    // interrupted thread closes the connection's NIO channel, an interruptible channel, under Tomcat.
    private boolean release() {
        if (!closed.compareAndSet(false, true)) {
            return false;
        }

        buffer.clear();
        buffer.offer(WAKE_UP);
        onClose.accept(this);
        return true;
    }

    private void writeEvents() {
        try {
            while (true) {
                StreamEvent event = buffer.poll(heartbeat.toMillis(), TimeUnit.MILLISECONDS);

                if (closed.get()) {
                    return;
                }

                emitter.send(event != null ? event.toSse() : SseEmitter.event().comment("heartbeat"));
            }
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
        } catch (IOException | IllegalStateException clientGone) {
            // Spring completes the response itself after a failed write; only this side needs releasing
            release();
        }
    }
}
