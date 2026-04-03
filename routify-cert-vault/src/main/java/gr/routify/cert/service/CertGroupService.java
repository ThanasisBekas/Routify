package gr.routify.cert.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import gr.routify.cert.domain.CertGroup;
import gr.routify.cert.domain.CertOutboxEvent;
import gr.routify.cert.domain.StoredCertificate;
import gr.routify.cert.dto.CertGroupDto;
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

import java.util.List;
import java.util.UUID;

/**
 * Service for certificate group management.
 *
 * <p>A {@link CertGroup} is a logical container that groups one or more certificates
 * under a single stable {@code logicalId}. The gateway TLS registry and filters bind
 * to the group's logical ID — not to individual certificate logical IDs — enabling:
 * <ul>
 *   <li>Seamless certificate rotation (add new → gateway picks it up → retire old)</li>
 *   <li>Multi-cert groups (RSA + ECDSA, primary + backup)</li>
 *   <li>Authorization scoping: filters can restrict access to certs within a specific group</li>
 * </ul>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class CertGroupService {

    private final CertGroupRepository        groupRepository;
    private final StoredCertificateRepository certRepository;
    private final CertOutboxEventRepository  outboxRepository;
    private final ObjectMapper               objectMapper;

    // ─── Group CRUD ────────────────────────────────────────────────────────────

    @Transactional
    public CertGroupDto createGroup(UUID tenantId, String logicalId, String alias,
                                    String description, String createdBy) {
        if (groupRepository.existsByLogicalIdAndTenantId(logicalId, tenantId)) {
            throw new RoutifyException.Conflict(
                    "Certificate group with logicalId '%s' already exists".formatted(logicalId));
        }
        if (groupRepository.existsByAliasAndTenantId(alias, tenantId)) {
            throw new RoutifyException.Conflict(
                    "Certificate group with alias '%s' already exists".formatted(alias));
        }

        CertGroup group = new CertGroup();
        group.setTenantId(tenantId);
        group.setLogicalId(logicalId);
        group.setAlias(alias);
        group.setDescription(description);
        group.setStatus(CertGroup.GroupStatus.ACTIVE);
        group.setCreatedBy(createdBy);

        group = groupRepository.save(group);
        log.info("CertGroup created: logicalId={} alias={} tenantId={}", logicalId, alias, tenantId);
        publishGroupOutboxEvent(group, "CERT_GROUP_CREATED");
        return CertGroupDto.fromSummary(group);
    }

    @Transactional
    public CertGroupDto updateGroup(UUID groupId, UUID tenantId, String alias,
                                    String description, String updatedBy) {
        CertGroup group = findGroupOrThrow(groupId, tenantId);
        if (alias != null && !alias.equals(group.getAlias())
                && groupRepository.existsByAliasAndTenantIdAndIdNot(alias, tenantId, groupId)) {
            throw new RoutifyException.Conflict(
                    "Certificate group with alias '%s' already exists".formatted(alias));
        }
        if (alias        != null) group.setAlias(alias);
        if (description  != null) group.setDescription(description);

        group = groupRepository.save(group);
        log.info("CertGroup updated: id={} by={}", groupId, updatedBy);
        publishGroupOutboxEvent(group, "CERT_GROUP_UPDATED");
        return CertGroupDto.fromSummary(group);
    }

    @Transactional
    public void archiveGroup(UUID groupId, UUID tenantId, String archivedBy) {
        CertGroup group = findGroupOrThrow(groupId, tenantId);
        group.archive();
        groupRepository.save(group);
        log.info("CertGroup archived: id={} by={}", groupId, archivedBy);
        publishGroupOutboxEvent(group, "CERT_GROUP_ARCHIVED");
    }

    @Transactional
    public void deleteGroup(UUID groupId, UUID tenantId, String deletedBy) {
        CertGroup group = findGroupOrThrow(groupId, tenantId);
        // Detach all member certs before deleting the group
        List<StoredCertificate> members = certRepository.findByGroupId(groupId);
        for (StoredCertificate m : members) {
            m.setGroup(null);
            m.setMemberAlias(null);
        }
        certRepository.saveAll(members);
        groupRepository.delete(group);
        log.info("CertGroup deleted: id={} by={} (detached {} members)", groupId, deletedBy, members.size());
        // Publish a final deletion event so the gateway can purge the registry entry
        publishGroupDeletedEvent(groupId, group.getLogicalId(), group.getTenantId());
    }

    // ─── Queries ──────────────────────────────────────────────────────────────

    @Transactional(readOnly = true)
    public Page<CertGroupDto> listGroups(UUID tenantId, String status, int page, int size,
                                          String sortBy, String sortDir) {
        Sort sort = Sort.by(Sort.Direction.fromString(sortDir), sortBy);
        PageRequest pageable = PageRequest.of(page, size, sort);
        Page<CertGroup> results;
        if (status != null && !status.isBlank()) {
            try {
                CertGroup.GroupStatus gs = CertGroup.GroupStatus.valueOf(status.toUpperCase());
                results = groupRepository.findByTenantIdAndStatus(tenantId, gs, pageable);
            } catch (IllegalArgumentException e) {
                results = groupRepository.findByTenantId(tenantId, pageable);
            }
        } else {
            results = groupRepository.findByTenantId(tenantId, pageable);
        }
        return results.map(CertGroupDto::fromSummary);
    }

    @Transactional(readOnly = true)
    public CertGroupDto getGroup(UUID groupId, UUID tenantId) {
        CertGroup group = findGroupOrThrow(groupId, tenantId);
        return CertGroupDto.fromDetail(group);
    }

    @Transactional(readOnly = true)
    public CertGroupDto getGroupByLogicalId(String logicalId, UUID tenantId) {
        CertGroup group = groupRepository.findByLogicalIdAndTenantId(logicalId, tenantId)
                .orElseThrow(() -> new RoutifyException.NotFound("CertGroup", logicalId));
        return CertGroupDto.fromDetail(group);
    }

    // ─── Member Management ─────────────────────────────────────────────────────

    /**
     * Adds an existing certificate to a group.
     *
     * @param groupId     target group
     * @param certId      certificate to add
     * @param memberAlias short label for this cert within the group (e.g. "primary", "backup-2025")
     * @param tenantId    tenant scope
     * @param updatedBy   actor
     */
    @Transactional
    public CertificateDto addCertificateToGroup(UUID groupId, UUID certId, String memberAlias,
                                                 UUID tenantId, String updatedBy) {
        CertGroup group = findGroupOrThrow(groupId, tenantId);
        if (!group.isActive()) {
            throw new RoutifyException.Validation("Cannot add a certificate to an ARCHIVED group");
        }

        StoredCertificate cert = certRepository.findByIdAndTenantId(certId, tenantId)
                .orElseThrow(() -> new RoutifyException.NotFound("Certificate", certId.toString()));

        if (cert.getStatus() != StoredCertificate.CertStatus.ACTIVE) {
            throw new RoutifyException.Validation("Only ACTIVE certificates can be added to a group");
        }

        // Validate memberAlias uniqueness within the group (excluding this cert itself)
        if (memberAlias != null && !memberAlias.isBlank()) {
            certRepository.findByGroupIdAndMemberAlias(groupId, memberAlias)
                    .filter(existing -> !existing.getId().equals(certId))
                    .ifPresent(existing -> {
                        throw new RoutifyException.Conflict(
                                "Member alias '%s' already in use within group '%s'"
                                        .formatted(memberAlias, group.getLogicalId()));
                    });
        }

        cert.setGroup(group);
        cert.setMemberAlias(memberAlias);
        cert = certRepository.save(cert);

        log.info("Cert {} added to group {} (logicalId={}) as member '{}' by={}",
                certId, groupId, group.getLogicalId(), memberAlias, updatedBy);

        // Publish event so gateway loads material under the group's logicalId
        publishMemberEvent(cert, group, "CERT_ADDED_TO_GROUP");
        return CertificateDto.from(cert);
    }

    /**
     * Removes a certificate from its group.
     * The cert becomes standalone (group = null). Its own {@code gatewayTlsLogicalId} is preserved.
     */
    @Transactional
    public CertificateDto removeCertificateFromGroup(UUID groupId, UUID certId,
                                                      UUID tenantId, String updatedBy) {
        CertGroup group = findGroupOrThrow(groupId, tenantId);
        StoredCertificate cert = certRepository.findByIdAndTenantId(certId, tenantId)
                .orElseThrow(() -> new RoutifyException.NotFound("Certificate", certId.toString()));

        if (cert.getGroup() == null || !cert.getGroup().getId().equals(groupId)) {
            throw new RoutifyException.Validation(
                    "Certificate '%s' does not belong to group '%s'".formatted(certId, groupId));
        }

        cert.setGroup(null);
        cert.setMemberAlias(null);
        cert = certRepository.save(cert);

        log.info("Cert {} removed from group {} (logicalId={}) by={}",
                certId, groupId, group.getLogicalId(), updatedBy);

        publishMemberEvent(cert, group, "CERT_REMOVED_FROM_GROUP");
        return CertificateDto.from(cert);
    }

    @Transactional(readOnly = true)
    public List<CertificateDto> listGroupMembers(UUID groupId, UUID tenantId) {
        findGroupOrThrow(groupId, tenantId); // validate access
        return certRepository.findByGroupId(groupId)
                .stream()
                .map(CertificateDto::from)
                .toList();
    }

    // ─── Private helpers ───────────────────────────────────────────────────────

    private CertGroup findGroupOrThrow(UUID groupId, UUID tenantId) {
        return groupRepository.findByIdAndTenantId(groupId, tenantId)
                .orElseThrow(() -> new RoutifyException.NotFound("CertGroup", groupId.toString()));
    }

    private void publishGroupOutboxEvent(CertGroup group, String eventType) {
        try {
            java.time.Instant now = java.time.Instant.now();
            java.util.UUID eventId = java.util.UUID.randomUUID();

            DomainEvent event = switch (eventType) {
                case "CERT_GROUP_CREATED" -> new DomainEvent.CertGroupCreated(
                        eventId, group.getTenantId(), group.getId(),
                        group.getLogicalId(), group.getAlias(), group.getStatus().name(),
                        now, null, null);
                case "CERT_GROUP_UPDATED" -> new DomainEvent.CertGroupUpdated(
                        eventId, group.getTenantId(), group.getId(),
                        group.getLogicalId(), group.getAlias(), group.getStatus().name(),
                        now, null, null);
                case "CERT_GROUP_ARCHIVED" -> new DomainEvent.CertGroupArchived(
                        eventId, group.getTenantId(), group.getId(),
                        group.getLogicalId(), group.getAlias(),
                        now, null, null);
                default -> throw new IllegalArgumentException("Unknown group event type: " + eventType);
            };

            CertOutboxEvent outbox = CertOutboxEvent.of(
                    "CertGroup",
                    group.getId().toString(),
                    eventType,
                    KafkaTopics.CERT_GROUP_EVENTS,
                    group.getTenantId().toString(),
                    objectMapper.writeValueAsString(event)
            );
            outboxRepository.save(outbox);
        } catch (Exception e) {
            log.error("Failed to store cert-group outbox event for {}: {}", eventType, e.getMessage(), e);
        }
    }

    private void publishGroupDeletedEvent(UUID groupId, String logicalId, UUID tenantId) {
        try {
            DomainEvent event = new DomainEvent.CertGroupDeleted(
                    java.util.UUID.randomUUID(), tenantId, groupId, logicalId,
                    java.time.Instant.now(), null, null);

            CertOutboxEvent outbox = CertOutboxEvent.of(
                    "CertGroup",
                    groupId.toString(),
                    "CERT_GROUP_DELETED",
                    KafkaTopics.CERT_GROUP_EVENTS,
                    tenantId.toString(),
                    objectMapper.writeValueAsString(event)
            );
            outboxRepository.save(outbox);
        } catch (Exception e) {
            log.error("Failed to store cert-group deleted outbox event: {}", e.getMessage(), e);
        }
    }

    private void publishMemberEvent(StoredCertificate cert, CertGroup group, String eventType) {
        try {
            java.time.Instant now = java.time.Instant.now();
            java.util.UUID eventId = java.util.UUID.randomUUID();

            DomainEvent event = switch (eventType) {
                case "CERT_ADDED_TO_GROUP" -> new DomainEvent.CertAddedToGroup(
                        eventId, cert.getTenantId(),
                        group.getId(), group.getLogicalId(),
                        cert.getId(), cert.getLogicalId(), cert.getAlias(),
                        cert.getMemberAlias(), cert.getStatus().name(),
                        now, null, null);
                case "CERT_REMOVED_FROM_GROUP" -> new DomainEvent.CertRemovedFromGroup(
                        eventId, cert.getTenantId(),
                        group.getId(), group.getLogicalId(),
                        cert.getId(), cert.getLogicalId(), cert.getAlias(),
                        cert.getMemberAlias(),
                        now, null, null);
                default -> throw new IllegalArgumentException("Unknown member event type: " + eventType);
            };

            CertOutboxEvent outbox = CertOutboxEvent.of(
                    "CertGroup",
                    group.getId().toString(),
                    eventType,
                    KafkaTopics.CERT_GROUP_EVENTS,
                    cert.getTenantId().toString(),
                    objectMapper.writeValueAsString(event)
            );
            outboxRepository.save(outbox);
        } catch (Exception e) {
            log.error("Failed to store cert-group member outbox event for {}: {}", eventType, e.getMessage(), e);
        }
    }
}

