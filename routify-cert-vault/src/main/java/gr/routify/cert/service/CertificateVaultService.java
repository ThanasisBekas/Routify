package gr.routify.cert.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import gr.routify.cert.domain.CertGroup;
import gr.routify.cert.domain.CertOutboxEvent;
import gr.routify.cert.domain.StoredCertificate;
import gr.routify.cert.dto.CertificateDto;
import gr.routify.cert.repository.CertGroupRepository;
import gr.routify.cert.repository.CertOutboxEventRepository;
import gr.routify.cert.repository.StoredCertificateRepository;
import gr.routify.common.event.DomainEvent;
import gr.routify.common.event.KafkaTopics;
import gr.routify.common.exception.RoutifyException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.*;

/**
 * Core service for certificate vault operations.
 *
 * <p>Handles upload (encrypt + persist + extract metadata), revoke, delete,
 * gateway TLS mapping, and queries. All write operations store a domain event
 * in the outbox for Kafka publishing.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class CertificateVaultService {

    private final StoredCertificateRepository certRepository;
    private final CertGroupRepository         groupRepository;
    private final CertOutboxEventRepository   outboxRepository;
    private final CertEncryptionService       encryptionService;
    private final CertificateParserService    parserService;
    private final ObjectMapper                objectMapper;

    // ─── Upload ────────────────────────────────────────────────────────────────

    /**
     * Upload a new certificate (PEM or PKCS12) into the vault, assigning it to a group.
     *
     * <p>The group must exist before uploading. The group's {@code logicalId} becomes the
     * effective gateway TLS registry key. The individual certificate's {@code logicalId}
     * is auto-generated as {@code "cert-<uuid>"} for internal tracing only.
     *
     * @param tenantId    owning tenant
     * @param groupId     mandatory group this certificate belongs to
     * @param memberAlias short label within the group (e.g. "primary", "backup-2025")
     * @param alias       human-readable display name for this certificate
     * @param description optional description
     * @param format      PEM or PKCS12
     * @param certPem     PEM-encoded certificate chain (or base64 PKCS12)
     * @param privateKey  optional PEM-encoded private key (may be null)
     * @param uploadedBy  actor performing the upload
     * @return DTO of the stored certificate (no raw material)
     */
    @Transactional
    public CertificateDto uploadCertificate(UUID tenantId, UUID groupId, String memberAlias,
                                             String alias, String description, String format,
                                             String certPem, String privateKey,
                                             String uploadedBy) {
        // Resolve group — mandatory
        CertGroup group = groupRepository.findByIdAndTenantId(groupId, tenantId)
                .orElseThrow(() -> new RoutifyException.NotFound("CertGroup", groupId.toString()));
        if (!group.isActive()) {
            throw new RoutifyException.Validation(
                    "Cannot upload a certificate to an ARCHIVED group. Reactivate the group first.");
        }

        // Alias must be unique per tenant
        if (certRepository.existsByAliasAndTenantId(alias, tenantId)) {
            throw new RoutifyException.Conflict(
                    "Certificate with alias '%s' already exists for this tenant".formatted(alias));
        }

        // memberAlias must be unique within the group (if provided)
        if (memberAlias != null && !memberAlias.isBlank()) {
            certRepository.findByGroupIdAndMemberAlias(groupId, memberAlias)
                    .ifPresent(existing -> {
                        throw new RoutifyException.Conflict(
                                "Member alias '%s' is already in use within group '%s'"
                                        .formatted(memberAlias, group.getLogicalId()));
                    });
        }

        // Parse X.509 metadata
        CertificateParserService.CertMetadata meta = CertificateParserService.CertMetadata.empty();
        if ("PEM".equalsIgnoreCase(format)) {
            meta = parserService.parsePem(certPem);
        }

        // Encrypt certificate material
        CertEncryptionService.EncryptedPayload encCert = encryptionService.encrypt(certPem);
        CertEncryptionService.EncryptedPayload encKey  = null;
        if (privateKey != null && !privateKey.isBlank()) {
            encKey = encryptionService.encrypt(privateKey);
        }

        StoredCertificate cert = new StoredCertificate();
        cert.setTenantId(tenantId);
        cert.setGroup(group);
        cert.setMemberAlias(memberAlias != null && !memberAlias.isBlank() ? memberAlias : null);
        cert.setAlias(alias);
        cert.setDescription(description);
        cert.setFormat(StoredCertificate.CertFormat.valueOf(format.toUpperCase()));
        cert.setStatus(StoredCertificate.CertStatus.ACTIVE);
        cert.setUploadedBy(uploadedBy);

        // Apply metadata
        cert.setSubjectDn(meta.subjectDn());
        cert.setIssuerDn(meta.issuerDn());
        cert.setSerialNumber(meta.serialNumber());
        cert.setNotBefore(meta.notBefore());
        cert.setNotAfter(meta.notAfter());
        cert.setSignatureAlg(meta.signatureAlg());
        cert.setKeyAlgorithm(meta.keyAlgorithm());
        cert.setKeySize(meta.keySize());
        cert.setFingerprintSha1(meta.fingerprintSha1());
        cert.setFingerprintSha256(meta.fingerprintSha256());
        cert.setSanDns(meta.sanDns());
        cert.setSanIp(meta.sanIp());
        cert.setCa(meta.isCa());
        cert.setExpiresAt(meta.notAfter());

        // Store encrypted material
        cert.setCertDataEnc(encCert.ciphertext());
        cert.setEncIv(encCert.iv());
        cert.setEncTag(encCert.tag());
        if (encKey != null) {
            cert.setPrivateKeyEnc(encKey.ciphertext() + ":" + encKey.iv() + ":" + encKey.tag());
        }

        cert = certRepository.save(cert);

        // Auto-generate internal logicalId after we have the UUID
        cert.setLogicalId("cert-" + cert.getId().toString());
        cert = certRepository.save(cert);

        log.info("Certificate uploaded: id={} alias={} groupId={} groupLogicalId={} memberAlias={} tenantId={}",
                cert.getId(), alias, groupId, group.getLogicalId(), memberAlias, tenantId);

        // Publish domain event — gateway receives this and loads material under group logicalId
        publishOutboxEvent(cert, "CERTIFICATE_UPLOADED");

        return CertificateDto.from(cert);
    }

    // ─── Revoke ────────────────────────────────────────────────────────────────

    @Transactional
    public CertificateDto revokeCertificate(UUID id, UUID tenantId, String revokedBy) {
        StoredCertificate cert = findOrThrow(id, tenantId);
        if (cert.getStatus() == StoredCertificate.CertStatus.DELETED) {
            throw new RoutifyException.Validation("Certificate is already deleted");
        }
        cert.revoke();
        cert = certRepository.save(cert);
        log.info("Certificate revoked: id={} by={}", id, revokedBy);
        publishOutboxEvent(cert, "CERTIFICATE_REVOKED");
        return CertificateDto.from(cert);
    }

    // ─── Delete ────────────────────────────────────────────────────────────────

    @Transactional
    public void deleteCertificate(UUID id, UUID tenantId, String deletedBy) {
        StoredCertificate cert = findOrThrow(id, tenantId);
        cert.markDeleted();
        certRepository.save(cert);
        log.info("Certificate deleted: id={} by={}", id, deletedBy);
        publishOutboxEvent(cert, "CERTIFICATE_DELETED");
    }

    // ─── Gateway TLS Mapping ───────────────────────────────────────────────────

    /**
     * Map or unmap a certificate to a gateway TLS logical ID.
     * The gateway TLS logical ID links this vault entry to a TLS listener in the gateway.
     *
     * @param id               certificate UUID
     * @param tenantId         owning tenant
     * @param gatewayLogicalId gateway TLS logical ID to assign (null to unmap)
     * @param updatedBy        actor performing the mapping
     */
    @Transactional
    public CertificateDto mapToGateway(UUID id, UUID tenantId, String gatewayLogicalId, String updatedBy) {
        StoredCertificate cert = findOrThrow(id, tenantId);
        if (cert.getStatus() != StoredCertificate.CertStatus.ACTIVE) {
            throw new RoutifyException.Validation("Only ACTIVE certificates can be mapped to gateway TLS");
        }

        cert.setGatewayTlsLogicalId(gatewayLogicalId);
        cert = certRepository.save(cert);

        String eventType = gatewayLogicalId != null ? "CERTIFICATE_MAPPED_TO_GATEWAY" : "CERTIFICATE_UNMAPPED_FROM_GATEWAY";
        log.info("{}: certId={} gatewayLogicalId={} by={}", eventType, id, gatewayLogicalId, updatedBy);
        publishOutboxEvent(cert, eventType);
        return CertificateDto.from(cert);
    }

    // ─── Secure Material Fetch (Gateway Internal) ──────────────────────────────

    /**
     * Decrypts and returns the raw PEM certificate chain (and optional private key)
     * for a given cert ID.  This is called exclusively by the gateway via the
     * internal RabbitMQ {@code certs.fetch-material} queue — never exposed externally.
     *
     * @param id       certificate UUID
     * @param tenantId owning tenant (enforced for multi-tenancy)
     * @return map with keys {@code certPem} (always present) and {@code privateKeyPem} (nullable)
     */
    @Transactional(readOnly = true)
    public Map<String, String> fetchDecryptedMaterial(UUID id, UUID tenantId) {
        StoredCertificate cert = findOrThrow(id, tenantId);
        if (cert.getStatus() != StoredCertificate.CertStatus.ACTIVE) {
            throw new gr.routify.common.exception.RoutifyException.Validation(
                    "Cannot fetch material for non-ACTIVE certificate: " + cert.getStatus());
        }

        String certPem = encryptionService.decrypt(new CertEncryptionService.EncryptedPayload(
                cert.getCertDataEnc(), cert.getEncIv(), cert.getEncTag()));

        String privateKeyPem = null;
        if (cert.getPrivateKeyEnc() != null) {
            String[] parts = cert.getPrivateKeyEnc().split(":", 3);
            if (parts.length == 3) {
                privateKeyPem = encryptionService.decrypt(
                        new CertEncryptionService.EncryptedPayload(parts[0], parts[1], parts[2]));
            }
        }

        Map<String, String> result = new java.util.LinkedHashMap<>();
        result.put("certPem",       certPem);
        result.put("privateKeyPem", privateKeyPem);  // null is fine — caller checks
        return result;
    }

    // ─── Queries ────────────────────────────────────────────────────────────────

    @Transactional(readOnly = true)
    public Page<CertificateDto> listCertificates(UUID tenantId, String status, int page, int size,
                                                   String sortBy, String sortDir) {
        Sort sort = Sort.by(Sort.Direction.fromString(sortDir), sortBy);
        PageRequest pageable = PageRequest.of(page, size, sort);

        Page<StoredCertificate> results;
        if (status != null && !status.isBlank()) {
            try {
                StoredCertificate.CertStatus certStatus = StoredCertificate.CertStatus.valueOf(status.toUpperCase());
                results = certRepository.findByTenantIdAndStatus(tenantId, certStatus, pageable);
            } catch (IllegalArgumentException e) {
                results = certRepository.findByTenantId(tenantId, pageable);
            }
        } else {
            results = certRepository.findByTenantId(tenantId, pageable);
        }
        return results.map(CertificateDto::from);
    }

    @Transactional(readOnly = true)
    public CertificateDto getCertificate(UUID id, UUID tenantId) {
        return CertificateDto.from(findOrThrow(id, tenantId));
    }

    @Transactional(readOnly = true)
    public CertificateDto getCertificateByLogicalId(String logicalId, UUID tenantId) {
        StoredCertificate cert = certRepository.findByLogicalIdAndTenantId(logicalId, tenantId)
                .orElseThrow(() -> new RoutifyException.NotFound("Certificate", logicalId));
        return CertificateDto.from(cert);
    }

    @Transactional(readOnly = true)
    public List<CertificateDto> listActiveCertificates(UUID tenantId) {
        return certRepository
                .findByTenantIdAndStatusOrderByAlias(tenantId, StoredCertificate.CertStatus.ACTIVE)
                .stream()
                .map(CertificateDto::from)
                .toList();
    }

    /**
     * Returns a list of all active certs that have an effective gateway TLS logical ID
     * (either via a group or directly via {@code gatewayTlsLogicalId}).
     *
     * <p>When {@code tenantId} is {@code null} (the gateway fetches a cross-tenant
     * snapshot at startup), ALL active gateway-mapped certs are returned.
     * Passing a non-null {@code tenantId} scopes the result to that tenant only.
     *
     * <p>Grouped certs are included automatically because their effective logical ID
     * is derived from the group.
     */
    @Transactional(readOnly = true)
    public List<CertificateDto> listGatewayMappedCerts(UUID tenantId) {
        List<StoredCertificate> directMapped = tenantId == null
                ? certRepository.findByStatusAndGatewayTlsLogicalIdNotNull(StoredCertificate.CertStatus.ACTIVE)
                : certRepository.findByTenantIdAndStatusAndGatewayTlsLogicalIdNotNull(
                        tenantId, StoredCertificate.CertStatus.ACTIVE);

        List<StoredCertificate> groupedCerts = tenantId == null
                ? certRepository.findActiveGroupedCerts()
                : certRepository.findActiveGroupedCertsByTenant(tenantId);

        // Merge, deduplicate by cert id
        java.util.LinkedHashMap<UUID, StoredCertificate> merged = new java.util.LinkedHashMap<>();
        directMapped.forEach(c -> merged.put(c.getId(), c));
        groupedCerts.forEach(c -> merged.put(c.getId(), c));

        return merged.values().stream().map(CertificateDto::from).toList();
    }

    /**
     * Vault statistics for a tenant.
     */
    @Transactional(readOnly = true)
    public Map<String, Object> getStats(UUID tenantId) {
        Map<String, Long> counts = new LinkedHashMap<>();
        if (tenantId != null) {
            certRepository.countByStatusForTenant(tenantId)
                    .forEach(row -> counts.put(row[0].toString(), (Long) row[1]));
        } else {
            certRepository.countByStatus()
                    .forEach(row -> counts.put(row[0].toString(), (Long) row[1]));
        }
        long total  = counts.values().stream().mapToLong(Long::longValue).sum();
        long active = counts.getOrDefault("ACTIVE", 0L);
        long expiringSoon = certRepository
                .findExpiringBefore(java.time.Instant.now().plusSeconds(30L * 86400))
                .stream()
                .filter(c -> tenantId == null || tenantId.equals(c.getTenantId()))
                .count();

        return Map.of(
                "total", total,
                "active", active,
                "expiringSoon", expiringSoon,
                "counts", counts
        );
    }

    // ─── Private helpers ───────────────────────────────────────────────────────

    private StoredCertificate findOrThrow(UUID id, UUID tenantId) {
        return certRepository.findByIdAndTenantId(id, tenantId)
                .orElseThrow(() -> new RoutifyException.NotFound("Certificate", id.toString()));
    }

    private void publishOutboxEvent(StoredCertificate cert, String eventType) {
        try {
            java.time.Instant now = java.time.Instant.now();
            java.util.UUID eventId = java.util.UUID.randomUUID();
            UUID groupId = cert.getGroup() != null ? cert.getGroup().getId() : null;
            String groupLogicalId = cert.getGroup() != null ? cert.getGroup().getLogicalId() : null;

            DomainEvent event = switch (eventType) {
                case "CERTIFICATE_UPLOADED" -> new DomainEvent.CertificateUploaded(
                        eventId, cert.getTenantId(), cert.getId(),
                        cert.getLogicalId(), cert.getAlias(), cert.getStatus().name(),
                        groupId, groupLogicalId, cert.getMemberAlias(),
                        cert.effectiveGatewayLogicalId(), now, null, null);
                case "CERTIFICATE_REVOKED" -> new DomainEvent.CertificateRevoked(
                        eventId, cert.getTenantId(), cert.getId(),
                        cert.getLogicalId(), cert.getAlias(),
                        cert.getGatewayTlsLogicalId(), cert.effectiveGatewayLogicalId(),
                        now, null, null);
                case "CERTIFICATE_DELETED" -> new DomainEvent.CertificateDeleted(
                        eventId, cert.getTenantId(), cert.getId(),
                        cert.getLogicalId(), cert.getAlias(),
                        cert.getGatewayTlsLogicalId(), cert.effectiveGatewayLogicalId(),
                        now, null, null);
                case "CERTIFICATE_MAPPED_TO_GATEWAY" -> new DomainEvent.CertificateMappedToGateway(
                        eventId, cert.getTenantId(), cert.getId(),
                        cert.getLogicalId(), cert.getAlias(),
                        cert.getGatewayTlsLogicalId(), groupId, groupLogicalId,
                        cert.effectiveGatewayLogicalId(), cert.getMemberAlias(),
                        now, null, null);
                case "CERTIFICATE_UNMAPPED_FROM_GATEWAY" -> new DomainEvent.CertificateUnmappedFromGateway(
                        eventId, cert.getTenantId(), cert.getId(),
                        cert.getLogicalId(), cert.getAlias(),
                        cert.getGatewayTlsLogicalId(), cert.effectiveGatewayLogicalId(),
                        now, null, null);
                default -> throw new IllegalArgumentException("Unknown cert event type: " + eventType);
            };

            CertOutboxEvent outbox = CertOutboxEvent.of(
                    "StoredCertificate",
                    cert.getId().toString(),
                    eventType,
                    KafkaTopics.CERT_EVENTS,
                    cert.getTenantId().toString(),
                    objectMapper.writeValueAsString(event)
            );
            outboxRepository.save(outbox);
        } catch (Exception e) {
            log.error("Failed to store cert outbox event for {}: {}", eventType, e.getMessage(), e);
        }
    }
}

