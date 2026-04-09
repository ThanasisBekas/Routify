package io.routify.cert.repository;

import io.routify.cert.domain.AcmeAccount;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface AcmeAccountRepository extends JpaRepository<AcmeAccount, UUID> {

    Optional<AcmeAccount> findByIdAndTenantId(UUID id, UUID tenantId);

    Optional<AcmeAccount> findByTenantIdAndStatus(UUID tenantId, AcmeAccount.AcmeAccountStatus status);

    List<AcmeAccount> findByTenantId(UUID tenantId);
}

