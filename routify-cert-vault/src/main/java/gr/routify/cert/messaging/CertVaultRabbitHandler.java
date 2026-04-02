package gr.routify.cert.messaging;

import com.fasterxml.jackson.databind.ObjectMapper;
import gr.routify.cert.service.CertGroupService;
import gr.routify.cert.service.CertificateVaultService;
import gr.routify.common.event.QueryRequest;
import gr.routify.common.event.RabbitTopology;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.stereotype.Component;

import java.util.Map;

/**
 * RabbitMQ request/reply handler for routify-cert-vault.
 *
 * <p>All request bodies are deserialised into strongly-typed {@link QueryRequest} records.
 * The {@code "type"} discriminator embedded by Jackson makes the wire format self-describing.
 *
 * <p>Write operations arrive via Kafka commands consumed by {@link CertCommandKafkaConsumer}.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class CertVaultRabbitHandler {

    private final CertificateVaultService vaultService;
    private final CertGroupService        groupService;
    private final ObjectMapper            objectMapper;

    @RabbitListener(queues = RabbitTopology.QUEUE_CERTS_QUERY)
    public String handleCertificatesQuery(String requestBody) {
        log.debug("RabbitMQ: cert vault query received");
        try {
            QueryRequest.CertsQuery req = objectMapper.readValue(
                    requestBody, QueryRequest.CertsQuery.class);
            String sortBy  = req.sortBy()  != null ? req.sortBy()  : "createdAt";
            String sortDir = req.sortDir() != null ? req.sortDir() : "DESC";

            var pageResult = vaultService.listCertificates(
                    req.tenantId(), req.status(), req.page(), req.size(), sortBy, sortDir);

            Map<String, Object> response = Map.of(
                    "content",       pageResult.getContent(),
                    "totalElements", pageResult.getTotalElements(),
                    "totalPages",    pageResult.getTotalPages(),
                    "size",          pageResult.getSize(),
                    "page",          pageResult.getNumber(),
                    "first",         pageResult.isFirst(),
                    "last",          pageResult.isLast()
            );
            return objectMapper.writeValueAsString(response);
        } catch (Exception e) {
            log.error("RabbitMQ: cert vault query failed: {}", e.getMessage(), e);
            return "{\"error\":\"" + e.getMessage() + "\"}";
        }
    }

    @RabbitListener(queues = RabbitTopology.QUEUE_CERTS_GET)
    public String handleCertificateGet(String requestBody) {
        log.debug("RabbitMQ: cert get request received");
        try {
            QueryRequest.CertGet req = objectMapper.readValue(requestBody, QueryRequest.CertGet.class);
            return objectMapper.writeValueAsString(vaultService.getCertificate(req.id(), req.tenantId()));
        } catch (Exception e) {
            log.error("RabbitMQ: cert get failed: {}", e.getMessage(), e);
            return "{\"error\":\"" + e.getMessage() + "\"}";
        }
    }

    @RabbitListener(queues = RabbitTopology.QUEUE_CERTS_ACTIVE_LIST)
    public String handleActiveCertsList(String requestBody) {
        log.debug("RabbitMQ: active certs list request received");
        try {
            QueryRequest.CertsActiveList req = objectMapper.readValue(
                    requestBody, QueryRequest.CertsActiveList.class);
            return objectMapper.writeValueAsString(vaultService.listActiveCertificates(req.tenantId()));
        } catch (Exception e) {
            log.error("RabbitMQ: active certs list failed: {}", e.getMessage(), e);
            return "[]";
        }
    }

    @RabbitListener(queues = RabbitTopology.QUEUE_CERTS_STATS)
    public String handleCertStats(String requestBody) {
        log.debug("RabbitMQ: cert stats request received");
        try {
            QueryRequest.CertStats req = objectMapper.readValue(requestBody, QueryRequest.CertStats.class);
            return objectMapper.writeValueAsString(vaultService.getStats(req.tenantId()));
        } catch (Exception e) {
            log.error("RabbitMQ: cert stats failed: {}", e.getMessage(), e);
            return "{\"error\":\"" + e.getMessage() + "\"}";
        }
    }

    @RabbitListener(queues = RabbitTopology.QUEUE_CERTS_GATEWAY_SNAPSHOT)
    public String handleGatewayMappedSnapshot(String requestBody) {
        log.debug("RabbitMQ: gateway cert snapshot request received");
        try {
            QueryRequest.CertsGatewaySnapshot req = objectMapper.readValue(
                    requestBody, QueryRequest.CertsGatewaySnapshot.class);
            return objectMapper.writeValueAsString(vaultService.listGatewayMappedCerts(req.tenantId()));
        } catch (Exception e) {
            log.error("RabbitMQ: gateway cert snapshot failed: {}", e.getMessage(), e);
            return "[]";
        }
    }

    /**
     * Serves decrypted certificate material (PEM chain + optional private key) to the gateway.
     * <strong>Internal only</strong> — this queue must not be accessible outside the service mesh.
     */
    @RabbitListener(queues = RabbitTopology.QUEUE_CERTS_FETCH_MATERIAL)
    public String handleFetchMaterial(String requestBody) {
        log.debug("RabbitMQ: cert fetch-material request received");
        try {
            QueryRequest.CertFetchMaterial req = objectMapper.readValue(
                    requestBody, QueryRequest.CertFetchMaterial.class);
            return objectMapper.writeValueAsString(
                    vaultService.fetchDecryptedMaterial(req.id(), req.tenantId()));
        } catch (Exception e) {
            log.error("RabbitMQ: cert fetch-material failed: {}", e.getMessage(), e);
            return "{\"error\":\"" + e.getMessage().replace("\"", "\\\"") + "\"}";
        }
    }

    // ─── Cert Group Query Handlers ────────────────────────────────────────────

    @RabbitListener(queues = RabbitTopology.QUEUE_CERT_GROUPS_QUERY)
    public String handleCertGroupsQuery(String requestBody) {
        log.debug("RabbitMQ: cert-groups query received");
        try {
            QueryRequest.CertGroupsQuery req = objectMapper.readValue(
                    requestBody, QueryRequest.CertGroupsQuery.class);
            String sortBy  = req.sortBy()  != null ? req.sortBy()  : "createdAt";
            String sortDir = req.sortDir() != null ? req.sortDir() : "DESC";

            var pageResult = groupService.listGroups(
                    req.tenantId(), req.status(), req.page(), req.size(), sortBy, sortDir);
            Map<String, Object> response = Map.of(
                    "content",       pageResult.getContent(),
                    "totalElements", pageResult.getTotalElements(),
                    "totalPages",    pageResult.getTotalPages(),
                    "size",          pageResult.getSize(),
                    "page",          pageResult.getNumber(),
                    "first",         pageResult.isFirst(),
                    "last",          pageResult.isLast()
            );
            return objectMapper.writeValueAsString(response);
        } catch (Exception e) {
            log.error("RabbitMQ: cert-groups query failed: {}", e.getMessage(), e);
            return "{\"error\":\"" + e.getMessage() + "\"}";
        }
    }

    @RabbitListener(queues = RabbitTopology.QUEUE_CERT_GROUPS_GET)
    public String handleCertGroupGet(String requestBody) {
        log.debug("RabbitMQ: cert-groups get received");
        try {
            QueryRequest.CertGroupGet req = objectMapper.readValue(
                    requestBody, QueryRequest.CertGroupGet.class);
            return objectMapper.writeValueAsString(groupService.getGroup(req.id(), req.tenantId()));
        } catch (Exception e) {
            log.error("RabbitMQ: cert-groups get failed: {}", e.getMessage(), e);
            return "{\"error\":\"" + e.getMessage() + "\"}";
        }
    }

    @RabbitListener(queues = RabbitTopology.QUEUE_CERT_GROUPS_MEMBERS)
    public String handleCertGroupMembers(String requestBody) {
        log.debug("RabbitMQ: cert-groups members query received");
        try {
            QueryRequest.CertGroupMembers req = objectMapper.readValue(
                    requestBody, QueryRequest.CertGroupMembers.class);
            return objectMapper.writeValueAsString(
                    groupService.listGroupMembers(req.groupId(), req.tenantId()));
        } catch (Exception e) {
            log.error("RabbitMQ: cert-groups members query failed: {}", e.getMessage(), e);
            return "[]";
        }
    }
}
