package io.julienmetral.tasks.notification.entities;

import io.julienmetral.tasks.identity.entities.UserProfile;
import jakarta.persistence.CollectionTable;
import jakarta.persistence.Column;
import jakarta.persistence.ElementCollection;
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
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;

/** An HTTPS endpoint of a user, which receives the task events it subscribes to, signed with its secret. */
@Entity
@Table(
        name = "webhook_endpoints",
        indexes = @Index(name = "webhook_endpoints_user_idIDX", columnList = "user_id")
)
@Getter
@Setter
@NoArgsConstructor
public class WebhookEndpoint {

    @Id
    @Generated
    @ColumnDefault("uuidv7()")
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(
            name = "user_id",
            nullable = false,
            updatable = false,
            foreignKey = @ForeignKey(name = "webhook_endpoints_userFK")
    )
    private UserProfile user;

    @Column(name = "url", nullable = false, length = 2048)
    private String url;

    // Encrypted by WebhookSecrets: a signing secret must be recoverable, so it cannot be hashed like a token
    @Column(name = "secret", nullable = false, length = 512)
    private String secret;

    // The secret before the last rotation, still used to sign until previousSecretExpiresAt so receivers can switch
    @Column(name = "previous_secret", length = 512)
    private String previousSecret;

    @Column(name = "previous_secret_expires_at")
    private Instant previousSecretExpiresAt;

    @ElementCollection
    @CollectionTable(
            name = "webhook_endpoint_events",
            joinColumns = @JoinColumn(name = "endpoint_id"),
            foreignKey = @ForeignKey(name = "webhook_endpoint_events_endpointFK")
    )
    @Column(name = "event", nullable = false, length = 50)
    @Enumerated(EnumType.STRING)
    private Set<WebhookEvent> events = new HashSet<>();

    // When the first of the failed attempts since the last success happened; cleared by a success
    @Column(name = "failing_since")
    private Instant failingSince;

    @Column(name = "disabled_at")
    private Instant disabledAt;

    @Enumerated(EnumType.STRING)
    @Column(name = "disabled_reason", length = 50)
    private WebhookDisabledReason disabledReason;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    public boolean isEnabled() {
        return disabledAt == null;
    }

    public void enable() {
        disabledAt = null;
        disabledReason = null;
        failingSince = null;
    }

    public void disable(WebhookDisabledReason reason, Instant now) {
        if (isEnabled()) {
            disabledAt = now;
            disabledReason = reason;
        }
    }
}
