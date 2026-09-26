package io.julienmetral.tasks.identity.entities;

import io.julienmetral.tasks.media.model.Media;
import io.julienmetral.tasks.shared.entities.AuditableEntity;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.ColumnDefault;
import org.hibernate.annotations.Generated;

import java.io.Serializable;
import java.time.Instant;
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;

@Entity
@Table(
        name = "users",
        uniqueConstraints = {
                // Covers soft-deleted rows too: the email of a deleted user stays taken (see UserRepository)
                @UniqueConstraint(
                        name = "users_emailUQ",
                        columnNames = "email"
                ),
                // A media file is the avatar of one user at most
                @UniqueConstraint(
                        name = "users_avatar_media_idUQ",
                        columnNames = "avatar_media_id"
                ),
                @UniqueConstraint(
                        name = "users_pending_avatar_media_idUQ",
                        columnNames = "pending_avatar_media_id"
                )
        }
)
@Getter
@Setter
@NoArgsConstructor
public class User extends AuditableEntity implements Serializable {

    @Id
    @Generated
    @ColumnDefault("uuidv7()")
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    @Column(nullable = false, length = 320)
    private String email;

    @Column(name = "email_verified_at")
    private Instant emailVerifiedAt;

    @Column(name = "last_login_at")
    private Instant lastLoginAt;

    // Login or token refresh: what the inactivity retention measures (see UserRetentionService)
    @Column(name = "last_active_at")
    private Instant lastActiveAt;

    @Column(name = "inactivity_warned_at")
    private Instant inactivityWarnedAt;

    // Set on soft-deleted rows only, which JPA never loads: written by UserRetentionQueries
    @Column(name = "anonymized_at")
    private Instant anonymizedAt;

    @Column(name = "password_hash", nullable = false, length = 255)
    private String passwordHash;

    @Column(name = "display_name", nullable = false, length = 255)
    private String displayName;

    @Column(nullable = false)
    private boolean enabled = true;

    @ElementCollection(fetch = FetchType.EAGER)
    @CollectionTable(
            name = "user_roles",
            joinColumns = @JoinColumn(name = "user_id"),
            foreignKey = @ForeignKey(name = "user_roles_userFK")
    )
    @Column(name = "role", nullable = false, length = 50)
    @Enumerated(EnumType.STRING)
    private Set<UserRole> roles = new HashSet<>();

    // ManyToOne rather than OneToOne: the uniqueness is the named constraint above, not an implicit generated one
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(
            name = "avatar_media_id",
            foreignKey = @ForeignKey(name = "users_avatar_mediaFK")
    )
    private Media avatar;

    // The uploaded photo waiting for the worker (see AvatarService.process); null once processed
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(
            name = "pending_avatar_media_id",
            foreignKey = @ForeignKey(name = "users_pending_avatar_mediaFK")
    )
    private Media pendingAvatar;

    /** Any activity cancels a pending inactivity deletion. */
    public void markActive(Instant now) {
        lastActiveAt = now;
        inactivityWarnedAt = null;
    }
}
