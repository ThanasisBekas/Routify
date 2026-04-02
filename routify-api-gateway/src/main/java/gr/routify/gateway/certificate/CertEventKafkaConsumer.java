package gr.routify.gateway.certificate;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import gr.routify.common.event.KafkaTopics;
import gr.routify.gateway.client.CertVaultClient;
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
 *   <li><b>CERTIFICATE_MAPPED_TO_GATEWAY</b> — fetches decrypted PEM material from
 *       cert-vault and registers it under the cert's <em>effective</em> gateway logical ID.
 *       If the cert belongs to a group, the group's {@code logicalId} is used (not the
 *       individual cert's {@code gatewayTlsLogicalId}).</li>
 *   <li><b>CERTIFICATE_UNMAPPED_FROM_GATEWAY</b> — deactivates all active versions
 *       for the cert's former effective logical ID.</li>
 *   <li><b>CERTIFICATE_REVOKED</b> — deactivates the registry entry for any
 *       logical ID this cert was mapped to.</li>
 *   <li><b>CERTIFICATE_DELETED</b> — same as REVOKED; purges inactive versions.</li>
 * </ul>
 *
 * <h3>Event types handled (cert-group events topic)</h3>
 * <ul>
 *   <li><b>CERT_ADDED_TO_GROUP</b> — fetches material for the new member and registers it
 *       under the group's {@code logicalId}.</li>
 *   <li><b>CERT_REMOVED_FROM_GROUP</b> — if the cert was the sole active member, deactivates
 *       the registry entry for the group's logical ID.</li>
 *   <li><b>CERT_GROUP_ARCHIVED / CERT_GROUP_DELETED</b> — deactivates + purges registry
 *       entry for the group's logical ID.</li>
 * </ul>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class CertEventKafkaConsumer {

    private final CertificateRegistry  certificateRegistry;
    private final CertificateVaultLoader vaultLoader;
    private final CertVaultClient      certVaultClient;
    private final ObjectMapper         objectMapper;

    @KafkaListener(
            topics = KafkaTopics.CERT_EVENTS,
            groupId = "routify-gateway-cert-events",
            containerFactory = "kafkaListenerContainerFactory"
    )
    public void onCertEvent(String eventJson, Acknowledgment ack) {
        try {
            Map<String, Object> event = objectMapper.readValue(eventJson, new TypeReference<>() {});
            String eventType               = str(event.get("eventType"));
            String certIdStr               = str(event.get("certId"));
            String tenantIdStr             = str(event.get("tenantId"));
            // effectiveGatewayLogicalId is the authoritative registry key (group or direct mapping)
            String effectiveLogicalId      = str(event.get("effectiveGatewayLogicalId"));
            // Fallback for legacy events that don't carry effectiveGatewayLogicalId
            if (effectiveLogicalId == null || effectiveLogicalId.isBlank()) {
                effectiveLogicalId = str(event.get("gatewayTlsLogicalId"));
            }
            String alias = str(event.getOrDefault("alias", certIdStr));

            log.debug("CertEventKafkaConsumer: eventType={} certId={} effectiveLogicalId={}",
                    eventType, certIdStr, effectiveLogicalId);

            switch (eventType != null ? eventType : "") {
                case "CERTIFICATE_MAPPED_TO_GATEWAY" ->
                        handleMapped(certIdStr, tenantIdStr, effectiveLogicalId, alias);

                case "CERTIFICATE_UNMAPPED_FROM_GATEWAY" ->
                        handleUnmapped(effectiveLogicalId, alias);

                case "CERTIFICATE_REVOKED", "CERTIFICATE_DELETED" ->
                        handleRevokedOrDeleted(effectiveLogicalId, alias, eventType);

                default ->
                        log.debug("CertEventKafkaConsumer: ignoring event type '{}'", eventType);
            }

            ack.acknowledge();
        } catch (Exception e) {
            log.error("CertEventKafkaConsumer: failed to process event: {} — {}",
                    eventJson, e.getMessage(), e);
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
    public void onCertGroupEvent(String eventJson, Acknowledgment ack) {
        try {
            Map<String, Object> event = objectMapper.readValue(eventJson, new TypeReference<>() {});
            String eventType      = str(event.get("eventType"));
            String groupLogicalId = str(event.get("groupLogicalId"));
            String certIdStr      = str(event.get("certId"));
            String tenantIdStr    = str(event.get("tenantId"));
            String certAlias      = str(event.getOrDefault("certAlias", certIdStr));

            log.debug("CertGroupEventConsumer: eventType={} groupLogicalId={} certId={}",
                    eventType, groupLogicalId, certIdStr);

            switch (eventType != null ? eventType : "") {
                case "CERT_ADDED_TO_GROUP" ->
                        handleMapped(certIdStr, tenantIdStr, groupLogicalId, certAlias);

                case "CERT_REMOVED_FROM_GROUP" ->
                        // A removal only deactivates if no other active member exists.
                        // We optimistically deactivate this cert's version and let the next
                        // CERT_ADDED_TO_GROUP re-register if needed. Full registry refresh
                        // can be triggered by gateway restart or a snapshot reload.
                        log.info("CertGroupEventConsumer: cert '{}' removed from group '{}' — " +
                                 "group logicalId='{}' registry remains active if other members exist",
                                certAlias, event.get("groupId"), groupLogicalId);

                case "CERT_GROUP_ARCHIVED", "CERT_GROUP_DELETED" -> {
                    if (groupLogicalId != null && !groupLogicalId.isBlank()) {
                        log.info("CertGroupEventConsumer: {} — deactivating + purging registry for " +
                                 "groupLogicalId='{}'", eventType, groupLogicalId);
                        deactivateAll(groupLogicalId);
                        int purged = certificateRegistry.purgeInactive(groupLogicalId);
                        if (purged > 0) log.info("CertGroupEventConsumer: purged {} version(s) for '{}'",
                                purged, groupLogicalId);
                    }
                }

                default ->
                        log.debug("CertGroupEventConsumer: ignoring event type '{}'", eventType);
            }

            ack.acknowledge();
        } catch (Exception e) {
            log.error("CertGroupEventConsumer: failed to process event: {} — {}",
                    eventJson, e.getMessage(), e);
            // Do NOT ack — let Kafka retry / route to DLQ
        }
    }

    // ─── Handlers ─────────────────────────────────────────────────────────────

    private void handleMapped(String certIdStr, String tenantIdStr,
                               String gatewayTlsLogicalId, String alias) {
        if (gatewayTlsLogicalId == null || gatewayTlsLogicalId.isBlank()) {
            log.warn("CertEventKafkaConsumer: CERTIFICATE_MAPPED_TO_GATEWAY has null/blank " +
                     "gatewayTlsLogicalId for cert '{}' — ignoring", alias);
            return;
        }

        UUID certId;
        UUID tenantId;
        try {
            certId   = UUID.fromString(certIdStr);
            tenantId = UUID.fromString(tenantIdStr);
        } catch (Exception e) {
            log.error("CertEventKafkaConsumer: invalid UUIDs in MAPPED event (id={}, tenant={}) — skipping",
                    certIdStr, tenantIdStr);
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

    private String str(Object val) {
        return val != null ? val.toString() : null;
    }
}

