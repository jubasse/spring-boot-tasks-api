package io.julienmetral.tasks.realtime.sse;

import io.julienmetral.tasks.realtime.RealtimeProperties;
import io.julienmetral.tasks.support.TestClock;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class NotificationReplayBufferTest {

    private static final Instant NOW = Instant.parse("2026-03-04T05:06:07Z");
    private static final Duration REPLAY_WINDOW = Duration.ofMinutes(5);
    private static final int REPLAY_SIZE = 4;

    private static final UUID ALICE = UUID.fromString("00000000-0000-0000-0000-000000000001");
    private static final UUID BOB = UUID.fromString("00000000-0000-0000-0000-000000000002");

    private final TestClock clock = new TestClock();

    private NotificationReplayBuffer buffer;

    @BeforeEach
    void createBuffer() {
        clock.set(NOW);
        buffer = new NotificationReplayBuffer(new RealtimeProperties.Streams(
                Duration.ofSeconds(20), Duration.ofMinutes(15), 5, Duration.ofSeconds(3),
                REPLAY_WINDOW, REPLAY_SIZE, 100), clock);
    }

    @Test
    void knownIdReplaysTheRecipientsEventsThatFollowItInTheirOrder() {
        StreamEvent first = add(ALICE);
        StreamEvent second = add(ALICE);
        StreamEvent third = add(ALICE);

        assertThat(buffer.eventsAfter(first.id(), ALICE)).hasValueSatisfying(missed ->
                assertThat(missed).containsExactly(second, third));
    }

    @Test
    void eventsOfOtherRecipientsAreNotReplayed() {
        StreamEvent first = add(ALICE);
        add(BOB);
        StreamEvent forAlice = add(ALICE);
        add(BOB);

        assertThat(buffer.eventsAfter(first.id(), ALICE)).hasValueSatisfying(missed ->
                assertThat(missed).containsExactly(forAlice));
    }

    @Test
    void idOfTheLatestEventReplaysNothing() {
        add(ALICE);
        StreamEvent latest = add(ALICE);

        assertThat(buffer.eventsAfter(latest.id(), ALICE)).hasValueSatisfying(missed -> assertThat(missed).isEmpty());
    }

    @Test
    void unknownIdIsNotReplayed() {
        add(ALICE);

        assertThat(buffer.eventsAfter(UUID.randomUUID().toString(), ALICE)).isEmpty();
    }

    @Test
    void eventOlderThanTheReplayWindowIsForgotten() {
        StreamEvent old = add(ALICE);
        clock.advance(REPLAY_WINDOW.plusMillis(1));

        assertThat(buffer.eventsAfter(old.id(), ALICE)).isEmpty();
    }

    @Test
    void eventExactlyAsOldAsTheReplayWindowIsKept() {
        StreamEvent old = add(ALICE);
        StreamEvent next = add(ALICE);
        clock.advance(REPLAY_WINDOW);

        assertThat(buffer.eventsAfter(old.id(), ALICE)).hasValueSatisfying(missed ->
                assertThat(missed).containsExactly(next));
    }

    @Test
    void onlyTheNewestEventsUpToTheReplaySizeAreKept() {
        StreamEvent dropped = add(ALICE);
        StreamEvent oldestKept = add(ALICE);
        add(ALICE);
        add(ALICE);
        add(ALICE);

        assertThat(buffer.eventsAfter(dropped.id(), ALICE)).isEmpty();
        assertThat(buffer.eventsAfter(oldestKept.id(), ALICE)).hasValueSatisfying(missed ->
                assertThat(missed).hasSize(REPLAY_SIZE - 1));
    }

    @Test
    void clearForgetsEveryEvent() {
        StreamEvent first = add(ALICE);
        add(ALICE);

        buffer.clear();

        assertThat(buffer.eventsAfter(first.id(), ALICE)).isEmpty();
    }

    private StreamEvent add(UUID recipientId) {
        StreamEvent event = StreamEvent.notification(UUID.randomUUID(), "task.assigned", "{}");
        buffer.add(recipientId, event);
        return event;
    }
}
