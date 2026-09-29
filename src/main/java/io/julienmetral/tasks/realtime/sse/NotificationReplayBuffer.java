package io.julienmetral.tasks.realtime.sse;

import io.julienmetral.tasks.realtime.RealtimeProperties;

import java.time.Clock;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * The notifications this instance relayed recently, for a client that reconnects with the id of the last one it
 * received. Every instance receives every notification, so a client may reconnect to another instance. The order is
 * the order of arrival here, which can differ slightly between instances: a replay may repeat an event, and clients
 * drop the ids they already have.
 */
class NotificationReplayBuffer {

    private record Entry(UUID recipientId, Instant receivedAt, StreamEvent event) {
    }

    private final ArrayDeque<Entry> entries = new ArrayDeque<>();
    private final RealtimeProperties.Streams properties;
    private final Clock clock;

    NotificationReplayBuffer(RealtimeProperties.Streams properties, Clock clock) {
        this.properties = properties;
        this.clock = clock;
    }

    synchronized void add(UUID recipientId, StreamEvent event) {
        entries.addLast(new Entry(recipientId, clock.instant(), event));
        dropExpired();
    }

    /**
     * @return the recipient's events that arrived after {@code lastEventId}, or empty when this instance no longer
     * holds that id (too old, or never seen): the client must then reload what it shows
     */
    synchronized Optional<List<StreamEvent>> eventsAfter(String lastEventId, UUID recipientId) {
        dropExpired();

        Iterator<Entry> iterator = entries.iterator();

        while (iterator.hasNext()) {
            if (lastEventId.equals(iterator.next().event().id())) {
                List<StreamEvent> missed = new ArrayList<>();

                iterator.forEachRemaining(entry -> {
                    if (entry.recipientId().equals(recipientId)) {
                        missed.add(entry.event());
                    }
                });

                return Optional.of(missed);
            }
        }

        return Optional.empty();
    }

    synchronized void clear() {
        entries.clear();
    }

    private void dropExpired() {
        Instant oldest = clock.instant().minus(properties.replayWindow());

        while (!entries.isEmpty()
                && (entries.size() > properties.replaySize() || entries.peekFirst().receivedAt().isBefore(oldest))) {
            entries.removeFirst();
        }
    }
}
