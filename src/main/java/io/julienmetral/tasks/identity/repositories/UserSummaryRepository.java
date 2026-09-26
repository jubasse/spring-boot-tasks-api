package io.julienmetral.tasks.identity.repositories;

import io.julienmetral.tasks.identity.entities.UserSummary;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.UUID;

/** Use {@code getReferenceById} to point an entity to a user without loading it. */
public interface UserSummaryRepository extends JpaRepository<UserSummary, UUID> {
}
