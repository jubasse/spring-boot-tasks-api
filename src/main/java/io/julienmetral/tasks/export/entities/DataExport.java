package io.julienmetral.tasks.export.entities;

import io.julienmetral.tasks.identity.entities.UserProfile;
import io.julienmetral.tasks.media.model.Media;
import jakarta.persistence.Column;
import jakarta.persistence.Embedded;
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
import jakarta.persistence.UniqueConstraint;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.ColumnDefault;
import org.hibernate.annotations.Generated;

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(
        name = "data_exports",
        indexes = @Index(name = "data_exports_owner_idIDX", columnList = "owner_id, created_at"),
        // Explicit: a @OneToOne would add an implicit unique constraint under a generated name
        uniqueConstraints = @UniqueConstraint(name = "data_exports_media_idUQ", columnNames = "media_id")
)
@Getter
@Setter
@NoArgsConstructor
public class DataExport {

    @Id
    @Generated
    @ColumnDefault("uuidv7()")
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(
            name = "owner_id",
            nullable = false,
            updatable = false,
            foreignKey = @ForeignKey(name = "data_exports_ownerFK")
    )
    private UserProfile owner;

    @Enumerated(EnumType.STRING)
    @Column(name = "type", nullable = false, updatable = false, length = 30)
    private DataExportType type;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 20)
    private DataExportStatus status;

    @Embedded
    private TaskExportFilters taskFilters;

    // The produced file, until it expires
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "media_id", foreignKey = @ForeignKey(name = "data_exports_mediaFK"))
    private Media media;

    @Column(name = "row_count")
    private Long rowCount;

    @Column(name = "attempts", nullable = false)
    private int attempts;

    // A short failure kind, never an exception message
    @Column(name = "failure", length = 100)
    private String failure;

    // While running: when the run counts as interrupted, if the instance running it stopped
    @Column(name = "lease_until")
    private Instant leaseUntil;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "started_at")
    private Instant startedAt;

    @Column(name = "completed_at")
    private Instant completedAt;

    @Column(name = "expires_at")
    private Instant expiresAt;
}
