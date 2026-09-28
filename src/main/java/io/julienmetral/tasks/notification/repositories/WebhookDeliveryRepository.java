package io.julienmetral.tasks.notification.repositories;

import io.julienmetral.tasks.notification.entities.WebhookDelivery;
import jakarta.persistence.LockModeType;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Optional;
import java.util.UUID;

public interface WebhookDeliveryRepository extends JpaRepository<WebhookDelivery, UUID> {

    Page<WebhookDelivery> findAllByEndpointId(UUID endpointId, Pageable pageable);

    @EntityGraph(attributePaths = "endpoint", type = EntityGraph.EntityGraphType.LOAD)
    Optional<WebhookDelivery> findWithEndpointById(UUID id);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT d FROM WebhookDelivery d JOIN FETCH d.endpoint WHERE d.id = :id")
    Optional<WebhookDelivery> findForUpdateById(@Param("id") UUID id);

    @Modifying
    @Query("DELETE FROM WebhookDelivery d WHERE d.endpoint.id = :endpointId")
    void deleteAllByEndpointId(@Param("endpointId") UUID endpointId);
}
