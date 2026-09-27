package io.julienmetral.tasks.identity.entities;

import io.julienmetral.tasks.shared.entities.AuditableEntity;
import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.DynamicUpdate;

import java.io.Serializable;
import java.time.Instant;
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;

/**
 * The account: credentials, roles, verification and activity. What others see of the user lives in
 * {@link UserProfile}, which shares this entity's id and outlives it.
 */
// Updates write only the changed columns. Otherwise every update rewrites the whole row from the loaded state, and
// the profile photo worker re-enabled an account that an admin disabled while the photo was being processed.
@Entity
@DynamicUpdate
@Table(
        name = "users",
        uniqueConstraints = {
                // Covers soft-deleted rows too: the email of a deleted user stays taken (see UserRepository)
                @UniqueConstraint(
                        name = "users_emailUQ",
                        columnNames = "email"
                )
        }
)
@Getter
@Setter
@NoArgsConstructor
public class User extends AuditableEntity implements Serializable {

    @Id
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    // Warning: the foreign key goes from users to user_profiles, never the other way, so deleting the account leaves
    // the profile that tasks and history point to. @MapsId gives the user the id of its profile.
    @OneToOne(fetch = FetchType.LAZY, optional = false, cascade = CascadeType.PERSIST)
    @MapsId
    @JoinColumn(name = "id", foreignKey = @ForeignKey(name = "users_profileFK"))
    @Setter(AccessLevel.NONE)
    private UserProfile profile = new UserProfile();

    @Column(nullable = false, length = 320)
    private String email;

    @Column(name = "email_verified_at")
    @Setter(AccessLevel.NONE)
    private Instant emailVerifiedAt;

    @Column(name = "last_login_at")
    private Instant lastLoginAt;

    // Login or token refresh: what the inactivity retention measures (see UserRetentionService)
    @Column(name = "last_active_at")
    private Instant lastActiveAt;

    @Column(name = "inactivity_warned_at")
    private Instant inactivityWarnedAt;

    @Column(name = "password_hash", nullable = false, length = 255)
    private String passwordHash;

    @Column(nullable = false)
    @Setter(AccessLevel.NONE)
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

    public String getDisplayName() {
        return profile.getDisplayName();
    }

    public void setDisplayName(String displayName) {
        profile.setDisplayName(displayName);
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
        syncProfileStatus();
    }

    public void setEmailVerifiedAt(Instant emailVerifiedAt) {
        this.emailVerifiedAt = emailVerifiedAt;
        syncProfileStatus();
    }

    /** To call before a soft delete: Hibernate sets {@code deleted_at} without going through the entity. */
    public void markDeleted() {
        profile.setStatus(UserStatus.DELETED);
    }

    /** Any activity cancels a pending inactivity deletion. */
    public void markActive(Instant now) {
        lastActiveAt = now;
        inactivityWarnedAt = null;
    }

    // The profile's status is the copy others see; every transition of the account goes through here
    private void syncProfileStatus() {
        profile.setStatus(UserStatus.of(this));
    }
}
