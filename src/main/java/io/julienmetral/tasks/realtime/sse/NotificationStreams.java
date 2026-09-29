package io.julienmetral.tasks.realtime.sse;

import io.julienmetral.tasks.identity.entities.UserStatus;
import io.julienmetral.tasks.identity.repositories.AccountState;
import io.julienmetral.tasks.identity.repositories.UserRepository;
import io.julienmetral.tasks.realtime.RealtimeProperties;
import io.julienmetral.tasks.realtime.exceptions.TooManyNotificationStreamsException;
import io.julienmetral.tasks.realtime.messaging.UserNotification;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.context.event.ContextClosedEvent;
import org.springframework.context.event.EventListener;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;
import tools.jackson.databind.json.JsonMapper;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.ThreadLocalRandom;

/** The notification streams open on this instance, by user. */
@Component
public class NotificationStreams {

    private final ConcurrentMap<UUID, Set<NotificationStream>> streamsByUser = new ConcurrentHashMap<>();
    private final RealtimeProperties.Streams properties;
    private final NotificationReplayBuffer replayBuffer;
    private final UserRepository userRepository;
    private final JsonMapper jsonMapper;
    private final Clock clock;

    public NotificationStreams(
            RealtimeProperties properties,
            UserRepository userRepository,
            JsonMapper jsonMapper,
            Clock clock,
            MeterRegistry meterRegistry
    ) {
        this.properties = properties.streams();
        this.replayBuffer = new NotificationReplayBuffer(properties.streams(), clock);
        this.userRepository = userRepository;
        this.jsonMapper = jsonMapper;
        this.clock = clock;

        Gauge.builder("notification.streams.open", this, NotificationStreams::openStreams)
                .description("Notification streams open on this instance")
                .register(meterRegistry);
    }

    /**
     * Opens a stream of the user's task notifications. It ends when the access token expires, or after
     * {@code max-duration}: the client reconnects with a fresh token and the id of the last event it received, then
     * gets the events it missed, or a {@code resync} event when this instance no longer has them.
     *
     * @throws TooManyNotificationStreamsException when the user already has {@code max-per-user} streams here
     * @throws AccessDeniedException               when the account is no longer active
     */
    public SseEmitter open(UUID userId, Instant tokenExpiresAt, String lastEventId) {
        Duration untilExpiry = tokenExpiresAt == null
                ? properties.maxDuration()
                : Duration.between(clock.instant(), tokenExpiresAt);
        Duration lifetime = untilExpiry.compareTo(properties.maxDuration()) < 0 ? untilExpiry : properties.maxDuration();
        SseEmitter emitter = new SseEmitter(Math.max(1, lifetime.toMillis()));
        NotificationStream stream = new NotificationStream(
                userId, emitter, properties.bufferSize(), properties.heartbeat(), this::forget);

        register(stream);

        // Read after registering, from the account rather than the status cache: a change committed after this read
        // is broadcast, and closes the stream once it arrives. A failed read must unregister it: the emitter never
        // reaches Spring, so no callback would, and the stream counted towards the user's limit until a restart.
        boolean active;

        try {
            active = isActive(userId);
        } catch (RuntimeException readFailed) {
            stream.close();
            throw readFailed;
        }

        if (!active) {
            stream.close();
            throw new AccessDeniedException("The account is not active");
        }

        stream.send(StreamEvent.reconnectAfter(reconnectDelay()));

        if (lastEventId != null) {
            List<StreamEvent> missed = replayBuffer.eventsAfter(lastEventId, userId).orElse(null);

            // A replay that would fill the stream's buffer would close it at once
            if (missed == null || missed.size() >= properties.bufferSize() - 1) {
                stream.send(StreamEvent.resync());
            } else {
                missed.forEach(stream::send);
            }
        }

        stream.start();
        return emitter;
    }

    /** Hands a notification to the recipient's streams on this instance, and keeps it for replay. */
    public void deliver(UUID eventId, UserNotification notification) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("type", notification.type());
        payload.put("timestamp", notification.timestamp());
        payload.put("data", notification.data());

        StreamEvent event = StreamEvent.notification(eventId, notification.type(), jsonMapper.writeValueAsString(payload));

        replayBuffer.add(notification.recipientId(), event);
        streamsByUser.getOrDefault(notification.recipientId(), Set.of()).forEach(stream -> stream.send(event));
    }

    public void closeIfNoLongerActive(UUID userId) {
        Set<NotificationStream> streams = streamsByUser.get(userId);

        if (streams != null && !isActive(userId)) {
            streams.forEach(NotificationStream::close);
        }
    }

    /**
     * After a gap in the events this instance received (its queue is deleted with a lost connection): every client
     * reloads what it shows, and no replay may skip the gap.
     */
    public void resyncAll() {
        replayBuffer.clear();
        streamsByUser.values().forEach(streams -> streams.forEach(stream -> stream.send(StreamEvent.resync())));
    }

    // Before the web server's graceful shutdown, which would otherwise wait for every open stream until its timeout
    @EventListener(ContextClosedEvent.class)
    public void closeAll() {
        streamsByUser.values().forEach(streams -> streams.forEach(NotificationStream::close));
    }

    private int openStreams() {
        return streamsByUser.values().stream().mapToInt(Set::size).sum();
    }

    private void register(NotificationStream stream) {
        streamsByUser.compute(stream.getUserId(), (userId, streams) -> {
            Set<NotificationStream> open = streams != null ? streams : ConcurrentHashMap.newKeySet();

            if (open.size() >= properties.maxPerUser()) {
                throw new TooManyNotificationStreamsException(properties.maxPerUser());
            }

            open.add(stream);
            return open;
        });
    }

    private void forget(NotificationStream stream) {
        streamsByUser.computeIfPresent(stream.getUserId(), (userId, streams) -> {
            streams.remove(stream);
            return streams.isEmpty() ? null : streams;
        });
    }

    private boolean isActive(UUID userId) {
        return userRepository.findAccountStateById(userId)
                .map(AccountState::status)
                .orElse(UserStatus.DELETED) == UserStatus.ACTIVE;
    }

    // A random extra, so that a restart does not bring every client back in the same second
    private Duration reconnectDelay() {
        long base = properties.reconnectDelay().toMillis();

        return Duration.ofMillis(base + ThreadLocalRandom.current().nextLong(base + 1));
    }
}
