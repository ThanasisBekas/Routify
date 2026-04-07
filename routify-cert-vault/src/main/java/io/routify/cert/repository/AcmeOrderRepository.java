package io.routify.cert.repository;

import io.routify.cert.domain.AcmeOrder;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface AcmeOrderRepository extends JpaRepository<AcmeOrder, UUID> {

    Optional<AcmeOrder> findByIdAndTenantId(UUID id, UUID tenantId);

    Page<AcmeOrder> findByTenantId(UUID tenantId, Pageable pageable);

    /**
     * Find orders due for auto-renewal: auto_renew=true, next_renewal_at before the given
     * threshold, and status is one of the provided values.
     */
    List<AcmeOrder> findByAutoRenewTrueAndNextRenewalAtBeforeAndStatusIn(
            Instant renewalWindow, List<AcmeOrder.AcmeOrderStatus> statuses);
}

