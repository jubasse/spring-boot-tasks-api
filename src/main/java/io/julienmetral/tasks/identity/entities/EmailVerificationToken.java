package io.julienmetral.tasks.identity.entities;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.ForeignKey;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
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

/** A single-use email verification token, stored as a SHA-256 hash. */
@Entity
@Table(
        name = "email_verification_tokens",
        uniqueConstraints = {
            @UniqueConstraint(
                    name = "email_verification_tokens_token_hashUQ",
                    columnNames = "token_hash")
        },
        indexes = {@Index(name = "email_verification_tokens_user_idIDX", columnList = "user_id")})
@Getter
@Setter
@NoArgsConstructor
public class EmailVerificationToken {

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
            foreignKey = @ForeignKey(name = "email_verification_tokens_userFK"))
    private UserProfile user;

    @Column(name = "token_hash", nullable = false, updatable = false, length = 64)
    private String tokenHash;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "expires_at", nullable = false, updatable = false)
    private Instant expiresAt;

    @Column(name = "used_at")
    private @Nullable Instant usedAt;
}
