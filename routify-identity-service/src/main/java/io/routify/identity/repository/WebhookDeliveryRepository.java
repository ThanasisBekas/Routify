package io.routify.identity.repository;

import io.routify.identity.domain.WebhookDelivery;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public interface WebhookDeliveryRepository extends JpaRepository<WebhookDelivery, UUID> {

    Page<WebhookDelivery> findBySubscriptionIdOrderByCreatedAtDesc(UUID subscriptionId, Pageable pageable);

    /** Finds deliveries that are PENDING and due for retry. */
    @Query("""
            SELECT d FROM WebhookDelivery d
            WHERE d.status = io.routify.identity.domain.WebhookDelivery$Status.PENDING
              AND d.nextRetryAt IS NOT NULL
              AND d.nextRetryAt <= :now
            ORDER BY d.nextRetryAt ASC
            """)
    List<WebhookDelivery> findPendingRetries(@Param("now") Instant now);

    /** Purges delivery records older than the given cutoff. Returns the number of deleted rows. */
    @Modifying
    @Query("DELETE FROM WebhookDelivery d WHERE d.createdAt < :cutoff")
    int deleteOlderThan(@Param("cutoff") Instant cutoff);

    /**
     * Purges a batch of delivery records older than the given cutoff.
     * Uses a native PostgreSQL subquery with LIMIT to bound the number of rows
     * deleted per transaction, preventing long-held locks on large tables.
     *
     * @param cutoff  delete records with created_at before this instant
     * @param batchSize maximum rows to delete per invocation
     * @return the number of deleted rows (≤ batchSize)
     */
    @Modifying
    @Query(value = """
            DELETE FROM routify_identity.webhook_delivery
            WHERE id IN (
                SELECT id FROM routify_identity.webhook_delivery
                WHERE created_at < :cutoff
                ORDER BY created_at ASC
                LIMIT :batchSize
            )
            """, nativeQuery = true)
    int deleteOlderThanBatch(@Param("cutoff") Instant cutoff, @Param("batchSize") int batchSize);
}

