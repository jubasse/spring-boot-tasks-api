package io.julienmetral.tasks.identity.entities;

import io.julienmetral.tasks.media.model.Media;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.ForeignKey;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import lombok.Getter;
import org.hibernate.annotations.Immutable;

import java.time.Instant;
import java.util.UUID;

/**
 * Read-only view of the {@code users} table, used by every entity that points to a user.
 * <p>
 * Warning: unlike {@link User}, it has no {@code @SoftDelete}, on purpose. An association to {@code User} could not load a
 * soft-deleted row, and the {@code @NotFound(IGNORE)} workaround made Hibernate drop the foreign key from its model,
 * so {@code liquibase:diff} proposed dropping the real constraints. Through this view, deleted users still load and
 * keep their name, and {@link UserStatus#of(UserSummary)} reports them as DELETED.
 */
@Entity
@Immutable
@Table(name = "users")
@Getter
public class UserSummary {

    @Id
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    @Column(name = "display_name", nullable = false)
    private String displayName;

    @Column(name = "enabled", nullable = false)
    private boolean enabled;

    @Column(name = "email_verified_at")
    private Instant emailVerifiedAt;

    @Column(name = "deleted_at")
    private Instant deletedAt;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(
            name = "avatar_media_id",
            foreignKey = @ForeignKey(name = "users_avatar_mediaFK")
    )
    private Media avatar;
}
