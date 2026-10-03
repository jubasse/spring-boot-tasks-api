package io.julienmetral.tasks.notification.repositories;

import io.julienmetral.tasks.notification.entities.WebhookEndpoint;
import io.julienmetral.tasks.notification.entities.WebhookEvent;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface WebhookEndpointRepository extends JpaRepository<WebhookEndpoint, UUID> {

    @EntityGraph(attributePaths = "events", type = EntityGraph.EntityGraphType.LOAD)
    List<WebhookEndpoint> findAllByUserIdOrderByCreatedAt(UUID userId);

    @EntityGraph(attributePaths = "events", type = EntityGraph.EntityGraphType.LOAD)
    Optional<WebhookEndpoint> findByIdAndUserId(UUID id, UUID userId);

    long countByUserId(UUID userId);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT e FROM WebhookEndpoint e WHERE e.id = :id")
    Optional<WebhookEndpoint> findByIdForUpdate(@Param("id") UUID id);

    @Query("""
            SELECT e FROM WebhookEndpoint e
            WHERE e.user.id = :userId AND e.disabledAt IS NULL AND :event MEMBER OF e.events
            """)
    List<WebhookEndpoint> findEnabledSubscribedTo(@Param("userId") UUID userId, @Param("event") WebhookEvent event);
}
