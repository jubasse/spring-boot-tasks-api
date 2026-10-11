package io.julienmetral.tasks.identity.repositories;

import io.julienmetral.tasks.identity.entities.UserProfile;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

/** Use {@code getReferenceById} to point an entity to a user without loading it. */
public interface UserProfileRepository extends JpaRepository<UserProfile, UUID> {}
