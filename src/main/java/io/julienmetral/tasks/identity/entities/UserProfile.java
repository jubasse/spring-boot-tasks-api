package io.julienmetral.tasks.identity.entities;

import io.julienmetral.tasks.media.model.Media;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.ColumnDefault;
import org.hibernate.annotations.DynamicUpdate;
import org.hibernate.annotations.Generated;
import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.annotation.LastModifiedDate;
import org.springframework.data.jpa.domain.support.AuditingEntityListener;

import java.time.Instant;
import java.util.UUID;

/**
 * What other resources show of a user: every task, comment, event, reminder and media points here, never to
 * {@link User}. The row outlives the account: a deleted user keeps a profile with status DELETED, and once
 * anonymized, the {@code users} row is deleted while this one stays as "Deleted user".
 * <p>
 * Warning: {@code status} is a copy of the account state, written by {@link User} on every transition (see
 * {@code User.syncProfileStatus}). Checks that grant access read the account itself, not this copy.
 */
@Entity
@DynamicUpdate
@EntityListeners(AuditingEntityListener.class)
@Table(
        name = "user_profiles",
        uniqueConstraints = {
                // A media file is the photo of one profile at most
                @UniqueConstraint(name = "user_profiles_avatar_media_idUQ", columnNames = "avatar_media_id"),
                @UniqueConstraint(
                        name = "user_profiles_pending_avatar_media_idUQ",
                        columnNames = "pending_avatar_media_id"
                )
        }
)
@Getter
@Setter
@NoArgsConstructor
public class UserProfile {

    @Id
    @Generated
    @ColumnDefault("uuidv7()")
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    @Column(name = "display_name", nullable = false, length = 255)
    private String displayName;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 20)
    private UserStatus status = UserStatus.UNVERIFIED;

    // ManyToOne rather than OneToOne: the uniqueness is the named constraint above, not an implicit generated one
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "avatar_media_id", foreignKey = @ForeignKey(name = "user_profiles_avatar_mediaFK"))
    private Media avatar;

    // The uploaded photo waiting for the worker (see AvatarService.process); null once processed
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(
            name = "pending_avatar_media_id",
            foreignKey = @ForeignKey(name = "user_profiles_pending_avatar_mediaFK")
    )
    private Media pendingAvatar;

    // Written by UserRetentionQueries when the account's personal data is erased
    @Column(name = "anonymized_at")
    private Instant anonymizedAt;

    @CreatedDate
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @LastModifiedDate
    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    /** The photo others may see: none while the account is disabled (the file is kept) or deleted. */
    public Media visibleAvatar() {
        return status == UserStatus.ACTIVE || status == UserStatus.UNVERIFIED ? avatar : null;
    }
}
