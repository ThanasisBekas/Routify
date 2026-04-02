package gr.routify.admin.client;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import gr.routify.common.client.AmqpServiceClientSupport;
import gr.routify.common.client.KafkaServiceClientSupport;
import gr.routify.common.event.CommandEvent;
import gr.routify.common.event.KafkaTopics;
import gr.routify.common.event.QueryRequest;
import gr.routify.common.event.RabbitTopology;
import io.github.resilience4j.circuitbreaker.annotation.CircuitBreaker;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

/**
 * Admin-API messaging client for certificate vault operations.
 *
 * <p><b>Queries</b> (read) go via RabbitMQ request/reply to routify-cert-vault.
 * <b>Commands</b> (write: upload, revoke, delete, map) are published as Kafka events
 * to {@code routify.cert.commands} and consumed by routify-cert-vault.
 *
 * <p>This is the ONLY path the dashboard uses to manage certificates.
 * There are no direct HTTP calls between admin-api and cert-vault.
 */
@Slf4j
@Component
public class CertVaultMessagingClient extends AmqpServiceClientSupport {

    private final KafkaServiceClientSupport kafka;

    public CertVaultMessagingClient(RabbitTemplate rabbitTemplate,
                                    ObjectMapper objectMapper,
                                    KafkaTemplate<String, String> kafkaTemplate) {
        super(rabbitTemplate, objectMapper, RabbitTopology.EXCHANGE_CERT_VAULT, "admin-api");
        this.kafka = new KafkaServiceClientSupport(kafkaTemplate, objectMapper, "admin-api") {};
    }

    // ─── Queries (RabbitMQ) ────────────────────────────────────────────────────

    @CircuitBreaker(name = "cert-vault", fallbackMethod = "queryCertificatesFallback")
    public Map<String, Object> queryCertificates(UUID tenantId, String status, int page, int size,
                                                  String sortBy, String sortDir) {
        try {
            return rpc(RabbitTopology.RK_CERTS_QUERY,
                    new QueryRequest.CertsQuery(tenantId, status, page, size, sortBy, sortDir));
        } catch (Exception e) {
            log.error("queryCertificates failed: {}", e.getMessage(), e);
            return Map.of("error", e.getMessage());
        }
    }

    @SuppressWarnings("unused")
    private Map<String, Object> queryCertificatesFallback(UUID tenantId, String status, int page, int size,
                                                           String sortBy, String sortDir, Throwable t) {
        log.warn("queryCertificates circuit open or timed out: {}", t.getMessage());
        return Map.of("error", "cert-vault temporarily unavailable", "circuitOpen", true);
    }

    @CircuitBreaker(name = "cert-vault", fallbackMethod = "getCertificateFallback")
    public Map<String, Object> getCertificate(UUID id, UUID tenantId) {
        try {
            return rpc(RabbitTopology.RK_CERTS_GET, new QueryRequest.CertGet(id, tenantId));
        } catch (Exception e) {
            log.error("getCertificate failed: {}", e.getMessage(), e);
            return Map.of("error", e.getMessage());
        }
    }

    @SuppressWarnings("unused")
    private Map<String, Object> getCertificateFallback(UUID id, UUID tenantId, Throwable t) {
        log.warn("getCertificate circuit open or timed out: {}", t.getMessage());
        return Map.of("error", "cert-vault temporarily unavailable", "circuitOpen", true);
    }

    @CircuitBreaker(name = "cert-vault", fallbackMethod = "listActiveCertificatesFallback")
    public Object listActiveCertificates(UUID tenantId) {
        try {
            return rpc(RabbitTopology.RK_CERTS_ACTIVE_LIST,
                    new QueryRequest.CertsActiveList(tenantId),
                    new TypeReference<java.util.List<?>>() {});
        } catch (Exception e) {
            log.error("listActiveCertificates failed: {}", e.getMessage(), e);
            return java.util.List.of();
        }
    }

    @SuppressWarnings("unused")
    private Object listActiveCertificatesFallback(UUID tenantId, Throwable t) {
        log.warn("listActiveCertificates circuit open or timed out: {}", t.getMessage());
        return java.util.List.of();
    }

    /**
     * Lists only the active certificates that have a {@code gatewayTlsLogicalId} mapping.
     * Used by the admin-api TLS tab to show which vault certs are linked to the gateway.
     * Routes to the {@code certs.gateway.snapshot} queue on cert-vault.
     */
    @CircuitBreaker(name = "cert-vault", fallbackMethod = "listGatewayMappedCertsFallback")
    public Object listGatewayMappedCertificates(UUID tenantId) {
        try {
            return rpc(RabbitTopology.RK_CERTS_GATEWAY_SNAPSHOT,
                    new QueryRequest.CertsGatewaySnapshot(tenantId),
                    new TypeReference<java.util.List<?>>() {});
        } catch (Exception e) {
            log.error("listGatewayMappedCertificates failed: {}", e.getMessage(), e);
            return java.util.List.of();
        }
    }

    @SuppressWarnings("unused")
    private Object listGatewayMappedCertsFallback(UUID tenantId, Throwable t) {
        log.warn("listGatewayMappedCertificates circuit open or timed out: {}", t.getMessage());
        return java.util.List.of();
    }

    @CircuitBreaker(name = "cert-vault", fallbackMethod = "getCertVaultStatsFallback")
    public Map<String, Object> getCertVaultStats(UUID tenantId) {
        try {
            return rpc(RabbitTopology.RK_CERTS_STATS, new QueryRequest.CertStats(tenantId));
        } catch (Exception e) {
            log.error("getCertVaultStats failed: {}", e.getMessage(), e);
            return Map.of("error", e.getMessage());
        }
    }

    @SuppressWarnings("unused")
    private Map<String, Object> getCertVaultStatsFallback(UUID tenantId, Throwable t) {
        log.warn("getCertVaultStats circuit open or timed out: {}", t.getMessage());
        return Map.of("error", "cert-vault temporarily unavailable", "circuitOpen", true);
    }

    // ─── Cert Group Queries (RabbitMQ) ────────────────────────────────────────

    @CircuitBreaker(name = "cert-vault", fallbackMethod = "queryCertGroupsFallback")
    public Map<String, Object> queryCertGroups(UUID tenantId, String status, int page, int size,
                                                String sortBy, String sortDir) {
        try {
            return rpc(RabbitTopology.RK_CERT_GROUPS_QUERY,
                    new QueryRequest.CertGroupsQuery(tenantId, status, page, size, sortBy, sortDir));
        } catch (Exception e) {
            log.error("queryCertGroups failed: {}", e.getMessage(), e);
            return Map.of("error", e.getMessage());
        }
    }

    @SuppressWarnings("unused")
    private Map<String, Object> queryCertGroupsFallback(UUID tenantId, String status, int page, int size,
                                                         String sortBy, String sortDir, Throwable t) {
        log.warn("queryCertGroups circuit open or timed out: {}", t.getMessage());
        return Map.of("error", "cert-vault temporarily unavailable", "circuitOpen", true);
    }

    @CircuitBreaker(name = "cert-vault", fallbackMethod = "getCertGroupFallback")
    public Map<String, Object> getCertGroup(UUID id, UUID tenantId) {
        try {
            return rpc(RabbitTopology.RK_CERT_GROUPS_GET, new QueryRequest.CertGroupGet(id, tenantId));
        } catch (Exception e) {
            log.error("getCertGroup failed: {}", e.getMessage(), e);
            return Map.of("error", e.getMessage());
        }
    }

    @SuppressWarnings("unused")
    private Map<String, Object> getCertGroupFallback(UUID id, UUID tenantId, Throwable t) {
        log.warn("getCertGroup circuit open or timed out: {}", t.getMessage());
        return Map.of("error", "cert-vault temporarily unavailable", "circuitOpen", true);
    }

    @CircuitBreaker(name = "cert-vault", fallbackMethod = "listCertGroupMembersFallback")
    public Object listCertGroupMembers(UUID groupId, UUID tenantId) {
        try {
            return rpc(RabbitTopology.RK_CERT_GROUPS_MEMBERS,
                    new QueryRequest.CertGroupMembers(groupId, tenantId),
                    new TypeReference<java.util.List<?>>() {});
        } catch (Exception e) {
            log.error("listCertGroupMembers failed: {}", e.getMessage(), e);
            return java.util.List.of();
        }
    }

    @SuppressWarnings("unused")
    private Object listCertGroupMembersFallback(UUID groupId, UUID tenantId, Throwable t) {
        log.warn("listCertGroupMembers circuit open or timed out: {}", t.getMessage());
        return java.util.List.of();
    }

    // ─── Commands (Kafka) ─────────────────────────────────────────────────────

    // ─── Commands (Kafka) ─────────────────────────────────────────────────────

    public void sendUploadCertificate(UUID tenantId, String actor, Map<String, Object> req) {
        kafka.publishCommand(KafkaTopics.CERT_COMMANDS, new CommandEvent.UploadCertificate(
                UUID.randomUUID(), tenantId, actor, Instant.now(),
                uuid(req, "groupId"), str(req, "memberAlias"),
                str(req, "alias"), str(req, "description"),
                req.getOrDefault("format", "PEM").toString(),
                str(req, "certPem"), str(req, "privateKey")));
    }

    public void sendRevokeCertificate(UUID id, UUID tenantId, String actor) {
        kafka.publishCommand(KafkaTopics.CERT_COMMANDS,
                new CommandEvent.RevokeCertificate(UUID.randomUUID(), tenantId, actor, Instant.now(), id));
    }

    public void sendDeleteCertificate(UUID id, UUID tenantId, String actor) {
        kafka.publishCommand(KafkaTopics.CERT_COMMANDS,
                new CommandEvent.DeleteCertificate(UUID.randomUUID(), tenantId, actor, Instant.now(), id));
    }

    public void sendCreateCertGroup(UUID tenantId, String actor, Map<String, Object> req) {
        kafka.publishCommand(KafkaTopics.CERT_COMMANDS, new CommandEvent.CreateCertGroup(
                UUID.randomUUID(), tenantId, actor, Instant.now(),
                str(req, "logicalId"), str(req, "alias"), str(req, "description")));
    }

    public void sendUpdateCertGroup(UUID id, UUID tenantId, String actor, Map<String, Object> req) {
        kafka.publishCommand(KafkaTopics.CERT_COMMANDS, new CommandEvent.UpdateCertGroup(
                UUID.randomUUID(), tenantId, actor, Instant.now(),
                id, str(req, "alias"), str(req, "description")));
    }

    public void sendArchiveCertGroup(UUID id, UUID tenantId, String actor) {
        kafka.publishCommand(KafkaTopics.CERT_COMMANDS,
                new CommandEvent.ArchiveCertGroup(UUID.randomUUID(), tenantId, actor, Instant.now(), id));
    }

    public void sendDeleteCertGroup(UUID id, UUID tenantId, String actor) {
        kafka.publishCommand(KafkaTopics.CERT_COMMANDS,
                new CommandEvent.DeleteCertGroup(UUID.randomUUID(), tenantId, actor, Instant.now(), id));
    }

    public void sendAddCertToGroup(UUID groupId, UUID tenantId, String actor, Map<String, Object> req) {
        kafka.publishCommand(KafkaTopics.CERT_COMMANDS, new CommandEvent.AddCertToGroup(
                UUID.randomUUID(), tenantId, actor, Instant.now(),
                groupId, uuid(req, "certId"), str(req, "memberAlias")));
    }

    public void sendRemoveCertFromGroup(UUID groupId, UUID certId, UUID tenantId, String actor) {
        kafka.publishCommand(KafkaTopics.CERT_COMMANDS,
                new CommandEvent.RemoveCertFromGroup(UUID.randomUUID(), tenantId, actor, Instant.now(),
                        groupId, certId));
    }

    // ─── Private helpers ──────────────────────────────────────────────────────

    private static String str(Map<String, Object> m, String key) {
        Object v = m.get(key);
        return v != null ? v.toString() : null;
    }

    private static UUID uuid(Map<String, Object> m, String key) {
        Object v = m.get(key);
        return v != null ? UUID.fromString(v.toString()) : null;
    }
}


