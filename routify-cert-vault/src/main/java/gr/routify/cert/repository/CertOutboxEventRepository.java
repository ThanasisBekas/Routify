package gr.routify.cert.repository;

import gr.routify.cert.domain.CertOutboxEvent;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.UUID;

/**
 * Repository for {@link CertOutboxEvent}.
 */
@Repository
public interface CertOutboxEventRepository extends JpaRepository<CertOutboxEvent, UUID> {

    @Query(value = """
        SELECT * FROM routify_cert.cert_outbox_event
        WHERE status = 'PENDING'
        ORDER BY created_at
        LIMIT :batchSize
        FOR UPDATE SKIP LOCKED
        """, nativeQuery = true)
    List<CertOutboxEvent> findPendingForPublishing(@Param("batchSize") int batchSize);

    @Query(value = """
        SELECT * FROM routify_cert.cert_outbox_event
        WHERE status = 'FAILED' AND retry_count < :maxRetries
        ORDER BY created_at
        LIMIT 50
        """, nativeQuery = true)
    List<CertOutboxEvent> findRetryable(@Param("maxRetries") int maxRetries);
}

