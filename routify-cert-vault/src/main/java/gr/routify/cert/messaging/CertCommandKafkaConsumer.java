package gr.routify.cert.messaging;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import gr.routify.cert.service.CertGroupService;
import gr.routify.cert.service.CertificateVaultService;
import gr.routify.common.event.KafkaTopics;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.support.Acknowledgment;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.UUID;

/**
 * Kafka command consumer for routify-cert-vault.
 *
 * <p>Consumes certificate and certificate-group command events published by routify-admin-api.
 * Each command triggers the appropriate service method which persists the change
 * and publishes a domain event via the transactional outbox.
 *
 * <p>Command envelope format (JSON):
 * <pre>{@code
 * {
 *   "command":     "UPLOAD_CERTIFICATE" | "REVOKE_CERTIFICATE" | "DELETE_CERTIFICATE" |
 *                  "MAP_CERTIFICATE_TO_GATEWAY" | "UNMAP_CERTIFICATE_FROM_GATEWAY" |
 *                  "CREATE_CERT_GROUP" | "UPDATE_CERT_GROUP" | "ARCHIVE_CERT_GROUP" |
 *                  "DELETE_CERT_GROUP" | "ADD_CERT_TO_GROUP" | "REMOVE_CERT_FROM_GROUP",
 *   "tenantId":    "uuid",
 *   "requestedBy": "user-id or username",
 *   "commandId":   "uuid",
 *   "payload":     { ... command-specific fields ... }
 * }
 * }</pre>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class CertCommandKafkaConsumer {

    private final CertificateVaultService vaultService;
    private final CertGroupService        groupService;
    private final ObjectMapper            objectMapper;

    @KafkaListener(
            topics = KafkaTopics.CERT_COMMANDS,
            groupId = "routify-cert-vault-commands",
            containerFactory = "certCommandKafkaListenerContainerFactory"
    )
    public void onCertCommand(String commandJson, Acknowledgment ack) {
        try {
            Map<String, Object> envelope = objectMapper.readValue(commandJson, new TypeReference<>() {});
            String command     = str(envelope.get("command"));
            UUID   tenantId    = parseUuid(envelope.get("tenantId"));
            String requestedBy = str(envelope.get("requestedBy"));

            @SuppressWarnings("unchecked")
            Map<String, Object> payload = envelope.containsKey("payload")
                    ? (Map<String, Object>) envelope.get("payload")
                    : envelope;

            log.info("Cert command received: command={} tenantId={} by={}", command, tenantId, requestedBy);

            switch (command) {
                case "UPLOAD_CERTIFICATE"  -> executeUpload(payload, tenantId, requestedBy);
                case "REVOKE_CERTIFICATE"  -> vaultService.revokeCertificate(
                        parseUuid(payload.get("id")), tenantId, requestedBy);
                case "DELETE_CERTIFICATE"  -> vaultService.deleteCertificate(
                        parseUuid(payload.get("id")), tenantId, requestedBy);
                case "MAP_CERTIFICATE_TO_GATEWAY" -> vaultService.mapToGateway(
                        parseUuid(payload.get("id")), tenantId,
                        str(payload.get("gatewayTlsLogicalId")), requestedBy);
                case "UNMAP_CERTIFICATE_FROM_GATEWAY" -> vaultService.mapToGateway(
                        parseUuid(payload.get("id")), tenantId, null, requestedBy);

                // ─── Cert Group Commands ───────────────────────────────────
                case "CREATE_CERT_GROUP"   -> groupService.createGroup(
                        tenantId,
                        str(payload.get("logicalId")),
                        str(payload.get("alias")),
                        str(payload.get("description")),
                        requestedBy);
                case "UPDATE_CERT_GROUP"   -> groupService.updateGroup(
                        parseUuid(payload.get("id")), tenantId,
                        str(payload.get("alias")),
                        str(payload.get("description")),
                        requestedBy);
                case "ARCHIVE_CERT_GROUP"  -> groupService.archiveGroup(
                        parseUuid(payload.get("id")), tenantId, requestedBy);
                case "DELETE_CERT_GROUP"   -> groupService.deleteGroup(
                        parseUuid(payload.get("id")), tenantId, requestedBy);
                case "ADD_CERT_TO_GROUP"   -> groupService.addCertificateToGroup(
                        parseUuid(payload.get("groupId")),
                        parseUuid(payload.get("certId")),
                        str(payload.get("memberAlias")),
                        tenantId, requestedBy);
                case "REMOVE_CERT_FROM_GROUP" -> groupService.removeCertificateFromGroup(
                        parseUuid(payload.get("groupId")),
                        parseUuid(payload.get("certId")),
                        tenantId, requestedBy);

                default -> log.warn("Unknown cert command: {}", command);
            }
            ack.acknowledge();
        } catch (Exception e) {
            log.error("Failed to process cert command: {} — {}", commandJson, e.getMessage(), e);
            // Don't ack — let Kafka retry or route to DLQ
        }
    }

    // ─── Private helpers ───────────────────────────────────────────────────────

    private void executeUpload(Map<String, Object> payload, UUID tenantId, String requestedBy) {
        vaultService.uploadCertificate(
                tenantId,
                parseUuid(payload.get("groupId")),
                str(payload.get("memberAlias")),
                str(payload.get("alias")),
                str(payload.get("description")),
                str(payload.getOrDefault("format", "PEM")),
                str(payload.get("certPem")),
                (String) payload.get("privateKey"),
                requestedBy
        );
    }

    private String str(Object val) {
        return val != null ? val.toString() : null;
    }

    private UUID parseUuid(Object val) {
        if (val == null) return null;
        try {
            return UUID.fromString(val.toString());
        } catch (IllegalArgumentException e) {
            return null;
        }
    }
}

