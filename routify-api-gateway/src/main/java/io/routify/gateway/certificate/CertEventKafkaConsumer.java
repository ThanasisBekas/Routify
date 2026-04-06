package io.routify.gateway.certificate;

import io.routify.common.event.DomainEvent;
import io.routify.common.event.KafkaTopics;
import io.routify.gateway.client.CertVaultClient;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.support.Acknowledgment;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.UUID;

/**
 * Kafka consumer that keeps the in-memory {@link CertificateRegistry} in sync with
 * the certificate vault whenever a certificate lifecycle event occurs.
 *
 * <h3>Event types handled (cert events topic)</h3>
 * <ul>
 *   <li>{@link DomainEvent.CertificateMappedToGateway} — fetches decrypted PEM material from
 *       cert-vault and registers it under the cert's effective gateway logical ID.</li>
 *   <li>{@link DomainEvent.CertificateUnmappedFromGateway} — deactivates all active versions
 *       for the cert's former effective logical ID.</li>
 *   <li>{@link DomainEvent.CertificateRevoked} — deactivates the registry entry.</li>
 *   <li>{@link DomainEvent.CertificateDeleted} — deactivates and purges the registry entry.</li>
 * </ul>
 *
 * <h3>Event types handled (cert-group events topic)</h3>
 * <ul>
 *   <li>{@link DomainEvent.CertAddedToGroup} — loads material under the group's logical ID.</li>
 *   <li>{@link DomainEvent.CertRemovedFromGroup} — logs the removal; other active members keep the entry live.</li>
 *   <li>{@link DomainEvent.CertGroupArchived} / {@link DomainEvent.CertGroupDeleted} — deactivates + purges.</li>
 * </ul>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class CertEventKafkaConsumer {

    private final CertificateRegistry   certificateRegistry;
    private final CertificateVaultLoader vaultLoader;
    private final CertVaultClient       certVaultClient;

    @KafkaListener(
            topics = KafkaTopics.CERT_EVENTS,
            groupId = "routify-gateway-cert-events",
            containerFactory = "kafkaListenerContainerFactory"
    )
    public void onCertEvent(DomainEvent event, Acknowledgment ack) {
        try {
            log.debug("CertEventKafkaConsumer: received {}", event.getClass().getSimpleName());

            switch (event) {
                case DomainEvent.CertificateMappedToGateway e ->
                        handleMapped(e.certId(), e.tenantId(), e.effectiveGatewayLogicalId(), e.alias());

                case DomainEvent.CertificateUnmappedFromGateway e ->
                        handleUnmapped(e.effectiveGatewayLogicalId(), e.alias());

                case DomainEvent.CertificateRevoked e ->
                        handleRevokedOrDeleted(e.effectiveGatewayLogicalId(), e.alias(), "CERTIFICATE_REVOKED");

                case DomainEvent.CertificateDeleted e ->
                        handleRevokedOrDeleted(e.effectiveGatewayLogicalId(), e.alias(), "CERTIFICATE_DELETED");

                default ->
                        log.debug("CertEventKafkaConsumer: ignoring event type '{}'", event.getClass().getSimpleName());
            }

            ack.acknowledge();
        } catch (Exception e) {
            log.error("CertEventKafkaConsumer: failed to process event: {} — {}",
                    event.getClass().getSimpleName(), e.getMessage(), e);
            // Do NOT ack — let Kafka retry / route to DLQ
        }
    }

    /**
     * Listens to cert-group events for group lifecycle: member additions, removals,
     * group archival/deletion.
     */
    @KafkaListener(
            topics = KafkaTopics.CERT_GROUP_EVENTS,
            groupId = "routify-gateway-cert-group-events",
            containerFactory = "kafkaListenerContainerFactory"
    )
    public void onCertGroupEvent(DomainEvent event, Acknowledgment ack) {
        try {
            log.debug("CertGroupEventConsumer: received {}", event.getClass().getSimpleName());

            switch (event) {
                case DomainEvent.CertAddedToGroup e ->
                        handleMapped(e.certId(), e.tenantId(), e.groupLogicalId(), e.certAlias());

                case DomainEvent.CertRemovedFromGroup e ->
                        log.info("CertGroupEventConsumer: cert '{}' removed from group '{}' — " +
                                 "group logicalId='{}' registry remains active if other members exist",
                                e.certAlias(), e.groupId(), e.groupLogicalId());

                case DomainEvent.CertGroupArchived e -> {
                    log.info("CertGroupEventConsumer: CERT_GROUP_ARCHIVED — deactivating + purging registry for " +
                             "groupLogicalId='{}'", e.logicalId());
                    deactivateAll(e.logicalId());
                    int purged = certificateRegistry.purgeInactive(e.logicalId());
                    if (purged > 0) log.info("CertGroupEventConsumer: purged {} version(s) for '{}'",
                            purged, e.logicalId());
                }

                case DomainEvent.CertGroupDeleted e -> {
                    log.info("CertGroupEventConsumer: CERT_GROUP_DELETED — deactivating + purging registry for " +
                             "groupLogicalId='{}'", e.logicalId());
                    deactivateAll(e.logicalId());
                    int purged = certificateRegistry.purgeInactive(e.logicalId());
                    if (purged > 0) log.info("CertGroupEventConsumer: purged {} version(s) for '{}'",
                            purged, e.logicalId());
                }

                default ->
                        log.debug("CertGroupEventConsumer: ignoring event type '{}'", event.getClass().getSimpleName());
            }

            ack.acknowledge();
        } catch (Exception e) {
            log.error("CertGroupEventConsumer: failed to process event: {} — {}",
                    event.getClass().getSimpleName(), e.getMessage(), e);
            // Do NOT ack — let Kafka retry / route to DLQ
        }
    }

    // ─── Handlers ─────────────────────────────────────────────────────────────

    private void handleMapped(UUID certId, UUID tenantId,
                               String gatewayTlsLogicalId, String alias) {
        if (gatewayTlsLogicalId == null || gatewayTlsLogicalId.isBlank()) {
            log.warn("CertEventKafkaConsumer: MAPPED event has null/blank " +
                     "gatewayTlsLogicalId for cert '{}' — ignoring", alias);
            return;
        }

        log.info("CertEventKafkaConsumer: loading cert '{}' (id={}) into registry under logicalId='{}'",
                alias, certId, gatewayTlsLogicalId);

        Map<String, String> material = certVaultClient.fetchCertMaterial(certId, tenantId);
        if (material == null) {
            log.error("CertEventKafkaConsumer: could not fetch material for certId={} — " +
                      "cert will NOT be registered in gateway TLS", certId);
            return;
        }

        boolean ok = vaultLoader.registerMaterial(
                gatewayTlsLogicalId, material, "vault-event:" + certId);
        if (ok) {
            log.info("CertEventKafkaConsumer: cert '{}' successfully registered under logicalId='{}'",
                    alias, gatewayTlsLogicalId);
        }
    }

    private void handleUnmapped(String gatewayTlsLogicalId, String alias) {
        if (gatewayTlsLogicalId == null || gatewayTlsLogicalId.isBlank()) {
            log.debug("CertEventKafkaConsumer: UNMAPPED event for cert '{}' has no prior " +
                      "gatewayTlsLogicalId — nothing to deactivate", alias);
            return;
        }
        log.info("CertEventKafkaConsumer: deactivating all active versions for logicalId='{}' " +
                 "(cert '{}' unmapped)", gatewayTlsLogicalId, alias);
        deactivateAll(gatewayTlsLogicalId);
    }

    private void handleRevokedOrDeleted(String gatewayTlsLogicalId, String alias, String eventType) {
        if (gatewayTlsLogicalId == null || gatewayTlsLogicalId.isBlank()) {
            log.debug("CertEventKafkaConsumer: {} event for cert '{}' has no gateway mapping — nothing to do",
                    eventType, alias);
            return;
        }
        log.info("CertEventKafkaConsumer: {} — deactivating + purging registry for logicalId='{}' (cert '{}')",
                eventType, gatewayTlsLogicalId, alias);
        deactivateAll(gatewayTlsLogicalId);
        int purged = certificateRegistry.purgeInactive(gatewayTlsLogicalId);
        if (purged > 0) {
            log.info("CertEventKafkaConsumer: purged {} inactive version(s) for logicalId='{}'",
                    purged, gatewayTlsLogicalId);
        }
    }

    // ─── Helpers ──────────────────────────────────────────────────────────────

    /**
     * Deactivates every active version registered under {@code logicalId}.
     */
    private void deactivateAll(String logicalId) {
        certificateRegistry.getAllVersions(logicalId).stream()
                .filter(certificateRegistry::isActive)
                .forEach(v -> {
                    certificateRegistry.deactivate(logicalId, v.version());
                    log.debug("CertEventKafkaConsumer: deactivated version {} for logicalId='{}'",
                            v.version(), logicalId);
                });
    }
}
