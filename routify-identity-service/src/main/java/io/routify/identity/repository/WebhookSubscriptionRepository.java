package io.routify.identity.repository;

import io.routify.identity.domain.WebhookSubscription;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface WebhookSubscriptionRepository extends JpaRepository<WebhookSubscription, UUID> {

    Page<WebhookSubscription> findByTenantId(UUID tenantId, Pageable pageable);

    Optional<WebhookSubscription> findByIdAndTenantId(UUID id, UUID tenantId);

    /**
     * Finds all ACTIVE subscriptions for a given tenant that include the specified event type.
     * Uses a native PostgreSQL array containment check ({@code = ANY(...)}).
     */
    @Query(value = """
            SELECT * FROM routify_identity.webhook_subscription ws
            WHERE ws.tenant_id = :tenantId
              AND ws.status = 'ACTIVE'
              AND :eventType = ANY(ws.event_types)
            """, nativeQuery = true)
    List<WebhookSubscription> findActiveByTenantIdAndEventType(
            @Param("tenantId") UUID tenantId,
            @Param("eventType") String eventType);
}

