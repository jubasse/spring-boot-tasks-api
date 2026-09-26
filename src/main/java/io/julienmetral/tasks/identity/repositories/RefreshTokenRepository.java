package io.julienmetral.tasks.identity.repositories;

import io.julienmetral.tasks.identity.entities.RefreshToken;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

public interface RefreshTokenRepository extends JpaRepository<RefreshToken, UUID> {

    // Row lock: two concurrent refreshes with the same token must not both rotate it
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    Optional<RefreshToken> findByTokenHash(String tokenHash);

    @Modifying
    @Query("""
            UPDATE RefreshToken t
            SET t.revokedAt = :now
            WHERE t.familyId = :familyId AND t.revokedAt IS NULL
            """)
    int revokeFamily(
            @Param("familyId") UUID familyId,
            @Param("now") Instant now
    );

    // Native on purpose: when tokens pointed to User, the JPQL path t.user.id joined users, which @SoftDelete
    // filters, and revoked nothing for a deleted user (caught by the tests). Filtering on the foreign key column
    // cannot regress that way, whatever the mapping.
    @Modifying
    @Query(
            value = """
                    UPDATE refresh_tokens
                    SET revoked_at = :now
                    WHERE user_id = :userId AND revoked_at IS NULL
                    """,
            nativeQuery = true
    )
    int revokeAllForUser(
            @Param("userId") UUID userId,
            @Param("now") Instant now
    );
}
