package io.julienmetral.tasks.identity.entities;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.ColumnDefault;
import org.hibernate.annotations.Generated;
import org.hibernate.annotations.NotFound;
import org.hibernate.annotations.NotFoundAction;

import java.time.Instant;
import java.util.UUID;

/**
 * An opaque refresh token, stored as a SHA-256 hash. Every refresh rotates the token: the used one is revoked and a
 * new one is issued in the same family. Replaying a revoked token revokes the whole family.
 */
@Entity
@Table(
        name = "refresh_tokens",
        uniqueConstraints = {
                @UniqueConstraint(
                        name = "refresh_tokens_token_hashUQ",
                        columnNames = "token_hash"
                )
        },
        indexes = {
                @Index(name = "refresh_tokens_user_idIDX", columnList = "user_id"),
                @Index(name = "refresh_tokens_family_idIDX", columnList = "family_id")
        }
)
@Getter
@Setter
@NoArgsConstructor
public class RefreshToken {

    @Id
    @Generated
    @ColumnDefault("uuidv7()")
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    // The user may be soft-deleted (@SoftDelete): Hibernate then cannot find the row, so map it to null
    // instead of failing the whole token lookup. Services treat a null user as an invalid token.
    @ManyToOne(fetch = FetchType.LAZY)
    @NotFound(action = NotFoundAction.IGNORE)
    @JoinColumn(
            name = "user_id",
            nullable = false,
            updatable = false,
            foreignKey = @ForeignKey(name = "refresh_tokens_userFK")
    )
    private User user;

    @Column(name = "token_hash", nullable = false, updatable = false, length = 64)
    private String tokenHash;

    // Shared by every token rotated from the same login
    @Column(name = "family_id", nullable = false, updatable = false)
    private UUID familyId;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "expires_at", nullable = false, updatable = false)
    private Instant expiresAt;

    @Column(name = "revoked_at")
    private Instant revokedAt;
}
