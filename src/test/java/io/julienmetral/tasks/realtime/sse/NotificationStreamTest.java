package io.julienmetral.tasks.realtime.sse;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Captor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter.SseEventBuilder;

import java.io.IOException;
import java.time.Duration;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

import static io.julienmetral.tasks.realtime.sse.StreamEventTest.text;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatNoException;
import static org.awaitility.Awaitility.await;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

@ExtendWith(MockitoExtension.class)
class NotificationStreamTest {

    private static final UUID USER_ID = UUID.fromString("00000000-0000-0000-0000-000000000001");

    // Long enough that no heartbeat is written during a test
    private static final Duration NO_HEARTBEAT = Duration.ofMinutes(10);

    private static final Duration SHORT_HEARTBEAT = Duration.ofMillis(50);

    private static final Duration WRITE_TIMEOUT = Duration.ofSeconds(5);

    // "Nothing written" can only be checked over a grace period, here several short heartbeats
    private static final Duration NOTHING_WRITTEN_GRACE_PERIOD = Duration.ofMillis(300);

    @Mock
    private SseEmitter emitter;

    @Captor
    private ArgumentCaptor<Runnable> completionCallback;

    @Captor
    private ArgumentCaptor<Consumer<Throwable>> errorCallback;

    @Captor
    private ArgumentCaptor<Runnable> timeoutCallback;

    private final BlockingQueue<String> written = new LinkedBlockingQueue<>();

    private final List<NotificationStream> released = new CopyOnWriteArrayList<>();

    // Writing

    @Test
    void eventSentToAStartedStreamIsWrittenByItsWriter() throws Exception {
        emitterRecordsWrites();
        NotificationStream stream = stream(10, NO_HEARTBEAT);
        stream.start();

        stream.send(StreamEvent.notification(UUID.fromString("00000000-0000-0000-0000-0000000000e1"),
                "task.assigned", "{}"));

        assertThat(nextWrite()).isEqualTo("id:00000000-0000-0000-0000-0000000000e1\nevent:task.assigned\ndata:{}\n\n");
    }

    @Test
    void eventsAreWrittenInTheOrderTheyWereSent() throws Exception {
        emitterRecordsWrites();
        NotificationStream stream = stream(10, NO_HEARTBEAT);
        List<StreamEvent> events = List.of(notification(), notification(), notification());
        events.forEach(stream::send);

        stream.start();

        for (StreamEvent event : events) {
            assertThat(nextWrite()).startsWith("id:" + event.id() + "\n");
        }
    }

    @Test
    void heartbeatCommentIsWrittenAfterTheHeartbeatOfSilence() throws Exception {
        emitterRecordsWrites();
        NotificationStream stream = stream(10, SHORT_HEARTBEAT);

        stream.start();

        assertThat(nextWrite()).isEqualTo(":heartbeat\n\n");
    }

    // Closing

    @Test
    void clientThatDoesNotKeepUpIsDisconnectedOnceItsBufferIsFull() {
        NotificationStream stream = stream(2, NO_HEARTBEAT);
        stream.send(notification());
        stream.send(notification());
        assertThat(stream.isClosed()).isFalse();

        stream.send(notification());

        assertThat(stream.isClosed()).isTrue();
        verify(emitter).complete();
        assertThat(released).containsExactly(stream);
    }

    @Test
    void closingCompletesTheResponseAndReleasesTheStreamOnce() {
        NotificationStream stream = stream(10, NO_HEARTBEAT);

        stream.close();
        stream.close();

        verify(emitter).complete();
        assertThat(released).containsExactly(stream);
    }

    @Test
    void writerStopsOnceTheStreamIsClosed() throws Exception {
        emitterRecordsWrites();
        NotificationStream stream = stream(10, SHORT_HEARTBEAT);
        stream.start();
        assertThat(nextWrite()).isEqualTo(":heartbeat\n\n");

        stream.close();
        // A heartbeat already past the writer's closed check when close() ran may still land
        Thread.sleep(SHORT_HEARTBEAT);
        written.clear();
        stream.send(notification());

        assertThat(written.poll(NOTHING_WRITTEN_GRACE_PERIOD.toMillis(), TimeUnit.MILLISECONDS)).isNull();
    }

    @Test
    void closingWakesTheWaitingWriterWithoutWritingAnything() throws Exception {
        NotificationStream stream = stream(10, NO_HEARTBEAT);
        stream.start();

        stream.close();
        stream.send(notification());

        Thread.sleep(NOTHING_WRITTEN_GRACE_PERIOD);
        verify(emitter, never()).send(any(SseEventBuilder.class));
    }

    @Test
    void failedWriteReleasesTheStreamWithoutCompletingTheResponse() throws Exception {
        doThrow(new IOException("Broken pipe")).when(emitter).send(any(SseEventBuilder.class));
        NotificationStream stream = stream(10, NO_HEARTBEAT);
        stream.start();

        stream.send(notification());

        await().atMost(WRITE_TIMEOUT).until(stream::isClosed);
        assertThat(released).containsExactly(stream);
        verify(emitter, never()).complete();
    }

    @Test
    void writeToAResponseAlreadyCompletedReleasesTheStream() throws Exception {
        doThrow(new IllegalStateException("ResponseBodyEmitter has already completed"))
                .when(emitter).send(any(SseEventBuilder.class));
        NotificationStream stream = stream(10, NO_HEARTBEAT);
        stream.start();

        stream.send(notification());

        await().atMost(WRITE_TIMEOUT).until(stream::isClosed);
        assertThat(released).containsExactly(stream);
    }

    @Test
    void clientLeavingReleasesTheStreamWithoutCompletingTheResponse() {
        NotificationStream stream = stream(10, NO_HEARTBEAT);
        verify(emitter).onCompletion(completionCallback.capture());

        completionCallback.getValue().run();

        assertThat(stream.isClosed()).isTrue();
        assertThat(released).containsExactly(stream);
        verify(emitter, never()).complete();
    }

    @Test
    void errorOfTheResponseReleasesTheStream() {
        NotificationStream stream = stream(10, NO_HEARTBEAT);
        verify(emitter).onError(errorCallback.capture());

        errorCallback.getValue().accept(new IOException("Connection reset"));

        assertThat(stream.isClosed()).isTrue();
        assertThat(released).containsExactly(stream);
        verify(emitter, never()).complete();
    }

    @Test
    void timeoutClosesTheStreamAndCompletesTheResponse() {
        NotificationStream stream = stream(10, NO_HEARTBEAT);
        verify(emitter).onTimeout(timeoutCallback.capture());

        timeoutCallback.getValue().run();

        assertThat(stream.isClosed()).isTrue();
        assertThat(released).containsExactly(stream);
        verify(emitter).complete();
    }

    @Test
    void completingAResponseTheClientLeftAtTheSameMomentIsNotAnError() {
        doThrow(new IllegalStateException("Already completed")).when(emitter).complete();
        NotificationStream stream = stream(10, NO_HEARTBEAT);

        assertThatNoException().isThrownBy(stream::close);
        assertThat(released).containsExactly(stream);
    }

    private NotificationStream stream(int bufferSize, Duration heartbeat) {
        return new NotificationStream(USER_ID, emitter, bufferSize, heartbeat, released::add);
    }

    private static StreamEvent notification() {
        return StreamEvent.notification(UUID.randomUUID(), "task.assigned", "{}");
    }

    private void emitterRecordsWrites() throws IOException {
        doAnswer(invocation -> {
            written.add(text(invocation.getArgument(0)));
            return null;
        }).when(emitter).send(any(SseEventBuilder.class));
    }

    private String nextWrite() throws InterruptedException {
        String write = written.poll(WRITE_TIMEOUT.toMillis(), TimeUnit.MILLISECONDS);
        assertThat(write).as("an event written within " + WRITE_TIMEOUT).isNotNull();
        return write;
    }
}
