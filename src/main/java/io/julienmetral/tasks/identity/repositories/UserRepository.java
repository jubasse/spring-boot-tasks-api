package io.julienmetral.tasks.identity.repositories;

import io.julienmetral.tasks.identity.entities.User;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Optional;
import java.util.UUID;

public interface UserRepository extends JpaRepository<User, UUID> {

    Optional<User> findByEmailIgnoreCase(String email);

    /** Locks the row until the transaction ends ({@code SELECT ... FOR UPDATE}); skips soft-deleted users. */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT u FROM User u WHERE u.id = :id")
    Optional<User> findByIdForUpdate(@Param("id") UUID id);

    // Native on purpose: JPQL queries are filtered by @SoftDelete, but users_emailUQ also covers deleted users
    @Query(
            value = "SELECT EXISTS (SELECT 1 FROM users WHERE lower(email) = lower(:email))",
            nativeQuery = true
    )
    boolean existsByEmailIncludingDeleted(@Param("email") String email);
}
