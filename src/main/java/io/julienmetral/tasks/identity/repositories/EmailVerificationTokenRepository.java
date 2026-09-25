package io.julienmetral.tasks.identity.repositories;

import io.julienmetral.tasks.identity.entities.EmailVerificationToken;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Optional;
import java.util.UUID;

public interface EmailVerificationTokenRepository extends JpaRepository<EmailVerificationToken, UUID> {

    // Row lock: a token must not be consumed twice by concurrent requests
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    Optional<EmailVerificationToken> findByTokenHash(String tokenHash);

    // Only the latest link stays valid when a new one is sent.
    // Native for the same reason as RefreshTokenRepository.revokeAllForUser: JPQL would join the soft-deletable users.
    @Modifying
    @Query(
            value = """
                    DELETE FROM email_verification_tokens
                    WHERE user_id = :userId AND used_at IS NULL
                    """,
            nativeQuery = true
    )
    int deleteUnusedForUser(
            @Param("userId") UUID userId
    );
}
