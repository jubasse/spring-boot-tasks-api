package io.julienmetral.tasks.notification.entities;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.ForeignKey;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.ColumnDefault;
import org.hibernate.annotations.Generated;

import java.time.Instant;
import java.util.UUID;

/**
 * One event to send to one endpoint, with its attempts. Its id is the {@code webhook-id} header, identical on every
 * attempt, so receivers can drop a duplicate.
 */
@Entity
@Table(
        name = "webhook_deliveries",
        indexes = {
                @Index(name = "webhook_deliveries_endpoint_idIDX", columnList = "endpoint_id"),
                @Index(name = "webhook_deliveries_dueIDX", columnList = "status, next_attempt_at")
        }
)
@Getter
@Setter
@NoArgsConstructor
public class WebhookDelivery {

    @Id
    @Generated
    @ColumnDefault("uuidv7()")
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(
            name = "endpoint_id",
            nullable = false,
            updatable = false,
            foreignKey = @ForeignKey(name = "webhook_deliveries_endpointFK")
    )
    private WebhookEndpoint endpoint;

    @Enumerated(EnumType.STRING)
    @Column(name = "event", nullable = false, updatable = false, length = 50)
    private WebhookEvent event;

    // The exact JSON sent and signed; text rather than jsonb, which would reorder and reformat it
    @Column(name = "payload", nullable = false, updatable = false, columnDefinition = "TEXT")
    private String payload;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 20)
    private WebhookDeliveryStatus status;

    @Column(name = "attempts", nullable = false)
    private int attempts;

    // When a pending delivery is due; while an attempt is in flight, the end of its lease
    @Column(name = "next_attempt_at")
    private Instant nextAttemptAt;

    @Column(name = "last_attempt_at")
    private Instant lastAttemptAt;

    @Column(name = "last_status_code")
    private Integer lastStatusCode;

    // A short failure kind, never an exception message: those carry the URL, and a Slack URL carries its secret
    @Column(name = "last_error", length = 100)
    private String lastError;

    @Column(name = "delivered_at")
    private Instant deliveredAt;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;
}
