package gr.routify.admin.client;

import com.fasterxml.jackson.databind.ObjectMapper;
import gr.routify.common.client.AmqpServiceClientSupport;
import gr.routify.common.client.KafkaServiceClientSupport;
import gr.routify.common.event.CommandEvent;
import gr.routify.common.event.KafkaTopics;
import gr.routify.common.event.QueryRequest;
import gr.routify.common.event.QueryResponse;
import gr.routify.common.event.RabbitTopology;
import io.github.resilience4j.circuitbreaker.annotation.CircuitBreaker;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.List;
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
                                    KafkaTemplate<String, Object> kafkaTemplate) {
        super(rabbitTemplate, objectMapper, RabbitTopology.EXCHANGE_CERT_VAULT, "admin-api");
        this.kafka = new KafkaServiceClientSupport(kafkaTemplate, objectMapper, "admin-api") {};
    }

    // ─── Queries (RabbitMQ) ────────────────────────────────────────────────────

    @CircuitBreaker(name = "cert-vault", fallbackMethod = "queryCertificatesFallback")
    public QueryResponse.CertsPage queryCertificates(UUID tenantId, String status, int page, int size,
                                                     String sortBy, String sortDir) {
        try {
            return rpc(RabbitTopology.RK_CERTS_QUERY,
                    new QueryRequest.CertsQuery(tenantId, status, page, size, sortBy, sortDir),
                    QueryResponse.CertsPage.class);
        } catch (Exception e) {
            log.error("queryCertificates failed: {}", e.getMessage(), e);
            throw e;
        }
    }

    @SuppressWarnings("unused")
    private QueryResponse.CertsPage queryCertificatesFallback(UUID tenantId, String status, int page, int size,
                                                              String sortBy, String sortDir, Throwable t) {
        log.warn("queryCertificates circuit open or timed out: {}", t.getMessage());
        return new QueryResponse.CertsPage(List.of(), 0L, 0, page, size, true, true);
    }

    @CircuitBreaker(name = "cert-vault", fallbackMethod = "getCertificateFallback")
    public QueryResponse.CertDetail getCertificate(UUID id, UUID tenantId) {
        try {
            return rpc(RabbitTopology.RK_CERTS_GET,
                    new QueryRequest.CertGet(id, tenantId),
                    QueryResponse.CertDetail.class);
        } catch (Exception e) {
            log.error("getCertificate failed: {}", e.getMessage(), e);
            throw e;
        }
    }

    @SuppressWarnings("unused")
    private QueryResponse.CertDetail getCertificateFallback(UUID id, UUID tenantId, Throwable t) {
        log.warn("getCertificate circuit open or timed out: {}", t.getMessage());
        return null;
    }

    @CircuitBreaker(name = "cert-vault", fallbackMethod = "listActiveCertificatesFallback")
    public QueryResponse.CertsList listActiveCertificates(UUID tenantId) {
        try {
            return rpc(RabbitTopology.RK_CERTS_ACTIVE_LIST,
                    new QueryRequest.CertsActiveList(tenantId),
                    QueryResponse.CertsList.class);
        } catch (Exception e) {
            log.error("listActiveCertificates failed: {}", e.getMessage(), e);
            return new QueryResponse.CertsList(List.of());
        }
    }

    @SuppressWarnings("unused")
    private QueryResponse.CertsList listActiveCertificatesFallback(UUID tenantId, Throwable t) {
        log.warn("listActiveCertificates circuit open or timed out: {}", t.getMessage());
        return new QueryResponse.CertsList(List.of());
    }

    /**
     * Lists only the active certificates that have a {@code gatewayTlsLogicalId} mapping.
     * Used by the admin-api TLS tab to show which vault certs are linked to the gateway.
     */
    @CircuitBreaker(name = "cert-vault", fallbackMethod = "listGatewayMappedCertsFallback")
    public QueryResponse.CertsList listGatewayMappedCertificates(UUID tenantId) {
        try {
            return rpc(RabbitTopology.RK_CERTS_GATEWAY_SNAPSHOT,
                    new QueryRequest.CertsGatewaySnapshot(tenantId),
                    QueryResponse.CertsList.class);
        } catch (Exception e) {
            log.error("listGatewayMappedCertificates failed: {}", e.getMessage(), e);
            return new QueryResponse.CertsList(List.of());
        }
    }

    @SuppressWarnings("unused")
    private QueryResponse.CertsList listGatewayMappedCertsFallback(UUID tenantId, Throwable t) {
        log.warn("listGatewayMappedCertificates circuit open or timed out: {}", t.getMessage());
        return new QueryResponse.CertsList(List.of());
    }

    @CircuitBreaker(name = "cert-vault", fallbackMethod = "getCertVaultStatsFallback")
    public QueryResponse.CertStatsResult getCertVaultStats(UUID tenantId) {
        try {
            return rpc(RabbitTopology.RK_CERTS_STATS,
                    new QueryRequest.CertStats(tenantId),
                    QueryResponse.CertStatsResult.class);
        } catch (Exception e) {
            log.error("getCertVaultStats failed: {}", e.getMessage(), e);
            throw e;
        }
    }

    @SuppressWarnings("unused")
    private QueryResponse.CertStatsResult getCertVaultStatsFallback(UUID tenantId, Throwable t) {
        log.warn("getCertVaultStats circuit open or timed out: {}", t.getMessage());
        return new QueryResponse.CertStatsResult(0L, 0L, 0L, Map.of());
    }

    // ─── Cert Group Queries (RabbitMQ) ────────────────────────────────────────

    @CircuitBreaker(name = "cert-vault", fallbackMethod = "queryCertGroupsFallback")
    public QueryResponse.CertGroupsPage queryCertGroups(UUID tenantId, String status, int page, int size,
                                                        String sortBy, String sortDir) {
        try {
            return rpc(RabbitTopology.RK_CERT_GROUPS_QUERY,
                    new QueryRequest.CertGroupsQuery(tenantId, status, page, size, sortBy, sortDir),
                    QueryResponse.CertGroupsPage.class);
        } catch (Exception e) {
            log.error("queryCertGroups failed: {}", e.getMessage(), e);
            throw e;
        }
    }

    @SuppressWarnings("unused")
    private QueryResponse.CertGroupsPage queryCertGroupsFallback(UUID tenantId, String status, int page, int size,
                                                                  String sortBy, String sortDir, Throwable t) {
        log.warn("queryCertGroups circuit open or timed out: {}", t.getMessage());
        return new QueryResponse.CertGroupsPage(List.of(), 0L, 0, page, size, true, true);
    }

    @CircuitBreaker(name = "cert-vault", fallbackMethod = "getCertGroupFallback")
    public QueryResponse.CertGroupDetail getCertGroup(UUID id, UUID tenantId) {
        try {
            return rpc(RabbitTopology.RK_CERT_GROUPS_GET,
                    new QueryRequest.CertGroupGet(id, tenantId),
                    QueryResponse.CertGroupDetail.class);
        } catch (Exception e) {
            log.error("getCertGroup failed: {}", e.getMessage(), e);
            throw e;
        }
    }

    @SuppressWarnings("unused")
    private QueryResponse.CertGroupDetail getCertGroupFallback(UUID id, UUID tenantId, Throwable t) {
        log.warn("getCertGroup circuit open or timed out: {}", t.getMessage());
        return null;
    }

    @CircuitBreaker(name = "cert-vault", fallbackMethod = "listCertGroupMembersFallback")
    public QueryResponse.CertGroupMembersList listCertGroupMembers(UUID groupId, UUID tenantId) {
        try {
            return rpc(RabbitTopology.RK_CERT_GROUPS_MEMBERS,
                    new QueryRequest.CertGroupMembers(groupId, tenantId),
                    QueryResponse.CertGroupMembersList.class);
        } catch (Exception e) {
            log.error("listCertGroupMembers failed: {}", e.getMessage(), e);
            return new QueryResponse.CertGroupMembersList(List.of());
        }
    }

    @SuppressWarnings("unused")
    private QueryResponse.CertGroupMembersList listCertGroupMembersFallback(UUID groupId, UUID tenantId, Throwable t) {
        log.warn("listCertGroupMembers circuit open or timed out: {}", t.getMessage());
        return new QueryResponse.CertGroupMembersList(List.of());
    }

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
