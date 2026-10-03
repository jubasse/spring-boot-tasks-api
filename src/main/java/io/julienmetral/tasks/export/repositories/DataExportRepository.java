package io.julienmetral.tasks.export.repositories;

import io.julienmetral.tasks.export.entities.DataExport;
import io.julienmetral.tasks.export.entities.DataExportStatus;
import io.julienmetral.tasks.export.entities.DataExportType;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface DataExportRepository extends JpaRepository<DataExport, UUID> {

    @EntityGraph(attributePaths = "media", type = EntityGraph.EntityGraphType.LOAD)
    Page<DataExport> findByOwnerId(UUID ownerId, Pageable pageable);

    @EntityGraph(attributePaths = "media", type = EntityGraph.EntityGraphType.LOAD)
    Optional<DataExport> findByIdAndOwnerId(UUID id, UUID ownerId);

    boolean existsByOwnerIdAndTypeAndStatusIn(UUID ownerId, DataExportType type, Collection<DataExportStatus> statuses);

    /**
     * Takes a queued export, or one whose run outlived its lease, for this instance: a duplicate message finds it
     * running and does nothing.
     *
     * @return 1 when this instance now runs it
     */
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("""
            UPDATE DataExport e
            SET e.status = :running, e.leaseUntil = :leaseUntil, e.attempts = e.attempts + 1, e.startedAt = :now,
                e.failure = null
            WHERE e.id = :id
              AND (e.status = :queued OR (e.status = :running AND e.leaseUntil < :now))
            """)
    int claim(
            @Param("id") UUID id,
            @Param("now") Instant now,
            @Param("leaseUntil") Instant leaseUntil,
            @Param("queued") DataExportStatus queued,
            @Param("running") DataExportStatus running
    );

    @Modifying
    @Query("UPDATE DataExport e SET e.leaseUntil = :leaseUntil WHERE e.id = :id AND e.status = :running")
    int renewLease(
            @Param("id") UUID id,
            @Param("leaseUntil") Instant leaseUntil,
            @Param("running") DataExportStatus running
    );

    List<DataExport> findByStatusInAndLeaseUntilBefore(Collection<DataExportStatus> statuses, Instant cutoff);

    @EntityGraph(attributePaths = "media", type = EntityGraph.EntityGraphType.LOAD)
    List<DataExport> findByStatusAndExpiresAtBefore(DataExportStatus status, Instant cutoff);

    @Modifying
    @Query("DELETE FROM DataExport e WHERE e.status IN :statuses AND e.createdAt < :cutoff")
    int deleteByStatusInAndCreatedAtBefore(
            @Param("statuses") Collection<DataExportStatus> statuses,
            @Param("cutoff") Instant cutoff
    );
}
