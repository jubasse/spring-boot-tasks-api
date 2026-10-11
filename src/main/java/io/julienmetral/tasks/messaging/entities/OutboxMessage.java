package io.julienmetral.tasks.messaging.entities;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.ColumnDefault;
import org.hibernate.annotations.Generated;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;
import org.jspecify.annotations.Nullable;

@Entity
@Table(
        name = "outbox_messages",
        indexes = {
            @Index(
                    name = "outbox_messages_pendingIDX",
                    columnList = "published_at, next_attempt_at")
        })
@Getter
@Setter
@NoArgsConstructor
public class OutboxMessage {

    @Id
    @Generated
    @ColumnDefault("uuidv7()")
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    // Exactly one of queue and exchange is set: a queue through the default exchange, or a
    // broadcast exchange
    @Column(name = "queue", updatable = false)
    private @Nullable String queue;

    @Column(name = "exchange", updatable = false)
    private @Nullable String exchange;

    // Class name sent as the __TypeId__ header, which the consumers' message converter reads
    @Column(name = "type", nullable = false, updatable = false)
    private String type;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "payload", columnDefinition = "jsonb", nullable = false, updatable = false)
    private Map<String, Object> payload;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @ColumnDefault("0")
    @Column(name = "attempts", nullable = false)
    private int attempts;

    @Column(name = "next_attempt_at", nullable = false)
    private Instant nextAttemptAt;

    @Column(name = "last_error", length = 1000)
    private @Nullable String lastError;

    @Column(name = "published_at")
    private @Nullable Instant publishedAt;

    public boolean isBroadcast() {
        return exchange != null;
    }

    /** The queue or the broadcast exchange, for metrics and logs. */
    public String destination() {
        String destination = exchange != null ? exchange : queue;

        return Objects.requireNonNull(
                destination, "Outbox writes every message with a queue or an exchange");
    }
}
