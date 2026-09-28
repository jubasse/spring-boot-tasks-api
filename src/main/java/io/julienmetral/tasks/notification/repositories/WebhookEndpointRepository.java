package io.julienmetral.tasks.notification.repositories;

import io.julienmetral.tasks.notification.entities.WebhookEndpoint;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface WebhookEndpointRepository extends JpaRepository<WebhookEndpoint, UUID> {

    @EntityGraph(attributePaths = "events", type = EntityGraph.EntityGraphType.LOAD)
    List<WebhookEndpoint> findAllByUserIdOrderByCreatedAt(UUID userId);

    @EntityGraph(attributePaths = "events", type = EntityGraph.EntityGraphType.LOAD)
    Optional<WebhookEndpoint> findByIdAndUserId(UUID id, UUID userId);

    long countByUserId(UUID userId);
}
