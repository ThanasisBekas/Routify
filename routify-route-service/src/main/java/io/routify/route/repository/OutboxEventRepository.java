package io.routify.route.repository;

import io.routify.route.domain.OutboxEvent;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.UUID;

@Repository
public interface OutboxEventRepository extends JpaRepository<OutboxEvent, UUID> {

    /**
     * Fetch PENDING outbox events for publishing, with a SKIP LOCKED advisory lock
     * to safely handle concurrent poller instances (e.g. multiple replicas).
     */
    @Query(value = """
            SELECT * FROM routify.outbox_event
            WHERE status = 'PENDING'
            ORDER BY created_at ASC
            LIMIT :limit
            FOR UPDATE SKIP LOCKED
            """, nativeQuery = true)
    List<OutboxEvent> findPendingForPublishing(@Param("limit") int limit);

    /** Fetch FAILED events that should be retried (retry_count < maxRetries), limited to batchSize */
    @Query(value = """
            SELECT * FROM routify.outbox_event
            WHERE status = 'FAILED'
            AND retry_count < :maxRetries
            ORDER BY created_at ASC
            LIMIT :limit
            """, nativeQuery = true)
    List<OutboxEvent> findRetryable(@Param("maxRetries") int maxRetries, @Param("limit") int limit);

    long countByStatus(OutboxEvent.Status status);
}

