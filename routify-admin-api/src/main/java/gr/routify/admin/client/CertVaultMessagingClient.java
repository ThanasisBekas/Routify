package gr.routify.admin.client;

import com.fasterxml.jackson.databind.ObjectMapper;
import gr.routify.admin.dto.AddCertToGroupRequest;
import gr.routify.admin.dto.CreateCertGroupRequest;
import gr.routify.admin.dto.UpdateCertGroupRequest;
import gr.routify.admin.dto.UploadCertificateRequest;
import gr.routify.common.client.AmqpServiceClientSupport;
import gr.routify.common.client.KafkaServiceClientSupport;
import gr.routify.common.event.CommandEvent;
import gr.routify.common.event.KafkaTopics;
import gr.routify.common.event.QueryRequest;
import gr.routify.common.event.QueryResponse;
import gr.routify.common.event.RabbitTopology;
import gr.routify.common.observability.RoutifyMetrics;
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
                                    KafkaTemplate<String, Object> kafkaTemplate,
                                    RoutifyMetrics metrics) {
        super(rabbitTemplate, objectMapper, RabbitTopology.EXCHANGE_CERT_VAULT, "admin-api", metrics);
        this.kafka = new KafkaServiceClientSupport(kafkaTemplate, "admin-api") {};
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

    public void sendUploadCertificate(UUID tenantId, String actor, UploadCertificateRequest req) {
        kafka.publishCommand(KafkaTopics.CERT_COMMANDS, new CommandEvent.UploadCertificate(
                UUID.randomUUID(), tenantId, actor, Instant.now(),
                req.groupId(), req.memberAlias(),
                req.alias(), req.description(),
                req.format() != null ? req.format() : "PEM",
                req.certPem(), req.privateKey()));
    }

    public void sendRevokeCertificate(UUID id, UUID tenantId, String actor) {
        kafka.publishCommand(KafkaTopics.CERT_COMMANDS,
                new CommandEvent.RevokeCertificate(UUID.randomUUID(), tenantId, actor, Instant.now(), id));
    }

    public void sendDeleteCertificate(UUID id, UUID tenantId, String actor) {
        kafka.publishCommand(KafkaTopics.CERT_COMMANDS,
                new CommandEvent.DeleteCertificate(UUID.randomUUID(), tenantId, actor, Instant.now(), id));
    }

    public void sendCreateCertGroup(UUID tenantId, String actor, CreateCertGroupRequest req) {
        kafka.publishCommand(KafkaTopics.CERT_COMMANDS, new CommandEvent.CreateCertGroup(
                UUID.randomUUID(), tenantId, actor, Instant.now(),
                req.logicalId(), req.alias(), req.description()));
    }

    public void sendUpdateCertGroup(UUID id, UUID tenantId, String actor, UpdateCertGroupRequest req) {
        kafka.publishCommand(KafkaTopics.CERT_COMMANDS, new CommandEvent.UpdateCertGroup(
                UUID.randomUUID(), tenantId, actor, Instant.now(),
                id, req.alias(), req.description()));
    }

    public void sendArchiveCertGroup(UUID id, UUID tenantId, String actor) {
        kafka.publishCommand(KafkaTopics.CERT_COMMANDS,
                new CommandEvent.ArchiveCertGroup(UUID.randomUUID(), tenantId, actor, Instant.now(), id));
    }

    public void sendDeleteCertGroup(UUID id, UUID tenantId, String actor) {
        kafka.publishCommand(KafkaTopics.CERT_COMMANDS,
                new CommandEvent.DeleteCertGroup(UUID.randomUUID(), tenantId, actor, Instant.now(), id));
    }

    public void sendAddCertToGroup(UUID groupId, UUID tenantId, String actor, AddCertToGroupRequest req) {
        kafka.publishCommand(KafkaTopics.CERT_COMMANDS, new CommandEvent.AddCertToGroup(
                UUID.randomUUID(), tenantId, actor, Instant.now(),
                groupId, req.certId(), req.memberAlias()));
    }

    public void sendRemoveCertFromGroup(UUID groupId, UUID certId, UUID tenantId, String actor) {
        kafka.publishCommand(KafkaTopics.CERT_COMMANDS,
                new CommandEvent.RemoveCertFromGroup(UUID.randomUUID(), tenantId, actor, Instant.now(),
                        groupId, certId));
    }
}
