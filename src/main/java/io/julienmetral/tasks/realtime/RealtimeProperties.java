package io.julienmetral.tasks.realtime;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import org.hibernate.validator.constraints.time.DurationMax;
import org.hibernate.validator.constraints.time.DurationMin;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.util.unit.DataSize;
import org.springframework.validation.annotation.Validated;

import java.time.Duration;
import java.util.List;

/**
 * @param queueMaxLength events an instance's own queue keeps while it cannot keep up; the oldest go first
 * @param streams        the notification streams (server-sent events)
 * @param rooms          the task rooms (STOMP over WebSocket)
 */
@Validated
@ConfigurationProperties(prefix = "realtime")
public record RealtimeProperties(
        @DefaultValue("10000") @Min(1) int queueMaxLength,
        @DefaultValue @Valid @NotNull Streams streams,
        @DefaultValue @Valid @NotNull Rooms rooms
) {

    /**
     * @param heartbeat      silence after which a comment is sent, so proxies do not close an idle stream; below
     *                       their read timeouts
     * @param maxDuration    longest life of a stream, which also ends when the access token expires
     * @param maxPerUser     streams one user can keep open on one instance
     * @param reconnectDelay the {@code retry} sent to clients, before a random extra of up to the same amount, so a
     *                       restart does not bring every client back at once
     * @param replayWindow   how long an instance keeps the events it relayed, for a client that reconnects with
     *                       {@code Last-Event-ID}
     * @param replaySize     most events kept for replay, whatever their age
     * @param bufferSize     events waiting for a slow client before its stream is closed
     */
    public record Streams(
            @DefaultValue("20s") @NotNull @DurationMin(seconds = 1) Duration heartbeat,
            @DefaultValue("15m") @NotNull @DurationMin(seconds = 1) @DurationMax(hours = 24) Duration maxDuration,
            @DefaultValue("5") @Min(1) int maxPerUser,
            @DefaultValue("3s") @NotNull @DurationMin(millis = 100) Duration reconnectDelay,
            @DefaultValue("5m") @NotNull Duration replayWindow,
            @DefaultValue("10000") @Min(1) int replaySize,
            @DefaultValue("100") @Min(1) int bufferSize
    ) {
    }

    /**
     * @param allowedOrigins     origins whose pages may open the WebSocket, as patterns such as
     *                           {@code https://*.example.com}; empty allows the API's own origin only
     * @param heartbeat          interval of the heartbeats both sides send, so a lost connection is noticed
     * @param messageSizeLimit   largest frame a client may send
     * @param timeToFirstMessage how long a new connection may wait before its CONNECT frame
     */
    public record Rooms(
            @DefaultValue List<String> allowedOrigins,
            @DefaultValue("10s") @NotNull @DurationMin(seconds = 1) Duration heartbeat,
            @DefaultValue("16KB") @NotNull DataSize messageSizeLimit,
            @DefaultValue("10s") @NotNull @DurationMin(seconds = 1) Duration timeToFirstMessage
    ) {
    }
}
