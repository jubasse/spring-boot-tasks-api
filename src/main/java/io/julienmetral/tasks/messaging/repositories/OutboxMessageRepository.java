package io.julienmetral.tasks.messaging.repositories;

import io.julienmetral.tasks.messaging.entities.OutboxMessage;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.UUID;

public interface OutboxMessageRepository extends JpaRepository<OutboxMessage, UUID> {

    /**
     * Locks the unpublished messages among {@code ids}. {@code SKIP LOCKED}: a message another instance (or the
     * poller) is publishing right now is left to it, so it is never sent twice.
     */
    @Query(
            value = """
                    SELECT * FROM outbox_messages
                    WHERE id IN (:ids) AND published_at IS NULL
                    FOR UPDATE SKIP LOCKED
                    """,
            nativeQuery = true
    )
    List<OutboxMessage> lockUnpublished(@Param("ids") Collection<UUID> ids);

    /** Locks the oldest messages due for a (new) publishing attempt, skipping those locked elsewhere. */
    @Query(
            value = """
                    SELECT * FROM outbox_messages
                    WHERE published_at IS NULL AND next_attempt_at <= :now
                    ORDER BY created_at
                    LIMIT :limit
                    FOR UPDATE SKIP LOCKED
                    """,
            nativeQuery = true
    )
    List<OutboxMessage> lockDue(@Param("now") Instant now, @Param("limit") int limit);

    @Modifying
    @Query(value = "DELETE FROM outbox_messages WHERE published_at < :cutoff", nativeQuery = true)
    int deletePublishedBefore(@Param("cutoff") Instant cutoff);
}
