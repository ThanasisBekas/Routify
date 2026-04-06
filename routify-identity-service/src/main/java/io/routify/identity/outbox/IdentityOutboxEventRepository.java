package io.routify.identity.outbox;

import io.routify.identity.domain.IdentityOutboxEvent;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.UUID;

/**
 * Repository for the identity-service Transactional Outbox.
 */
@Repository
public interface IdentityOutboxEventRepository extends JpaRepository<IdentityOutboxEvent, UUID> {

    @Query("""
           SELECT o FROM IdentityOutboxEvent o
           WHERE o.status = 'PENDING'
           ORDER BY o.createdAt ASC
           LIMIT :limit
           """)
    List<IdentityOutboxEvent> findPendingForPublishing(@Param("limit") int limit);

    @Query("""
           SELECT o FROM IdentityOutboxEvent o
           WHERE o.status = 'FAILED'
             AND o.retryCount < :maxRetries
           ORDER BY o.createdAt ASC
           """)
    List<IdentityOutboxEvent> findRetryable(@Param("maxRetries") int maxRetries);
}

