package io.julienmetral.tasks.media.model;

import io.julienmetral.tasks.identity.entities.UserProfile;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.ForeignKey;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import java.time.Instant;
import java.util.UUID;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.ColumnDefault;
import org.hibernate.annotations.Generated;
import org.jspecify.annotations.Nullable;

/** A stored file: metadata here, bytes in object storage under {@link #storageKey}. */
@Entity
@Table(
        name = "media",
        uniqueConstraints = {
            @UniqueConstraint(name = "media_storage_keyUQ", columnNames = "storage_key")
        })
@Getter
@Setter
@NoArgsConstructor
public class Media {

    @Id
    @Generated
    @ColumnDefault("uuidv7()")
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    @Column(name = "storage_key", nullable = false, updatable = false, length = 512)
    private String storageKey;

    @Enumerated(EnumType.STRING)
    @Column(name = "usage", nullable = false, updatable = false, length = 30)
    private MediaUsage usage;

    // As sent by the client: shown in the download's Content-Disposition, never used in the storage
    // key
    @Column(name = "original_filename", nullable = false, updatable = false, length = 255)
    private String originalFilename;

    // Detected from the bytes, not taken from the client's Content-Type header
    @Column(name = "content_type", nullable = false, updatable = false, length = 255)
    private String contentType;

    @Column(name = "size_bytes", nullable = false, updatable = false)
    private long sizeBytes;

    @Column(name = "sha256", nullable = false, updatable = false, length = 64)
    private String sha256;

    // UserProfile, not User: the uploader may later be soft-deleted
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "uploaded_by_id", foreignKey = @ForeignKey(name = "media_uploaded_byFK"))
    private @Nullable UserProfile uploadedBy;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;
}
