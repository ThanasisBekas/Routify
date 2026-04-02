package gr.routify.cert.messaging;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import gr.routify.cert.service.CertGroupService;
import gr.routify.cert.service.CertificateVaultService;
import gr.routify.common.event.RabbitTopology;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.UUID;

/**
 * RabbitMQ request/reply handler for routify-cert-vault.
 *
 * <p>Responds to synchronous queries from routify-admin-api:
 * <ul>
 *   <li><b>Certificates list</b>: paginated list for the vault dashboard</li>
 *   <li><b>Certificate get</b>: single certificate detail</li>
 *   <li><b>Active list</b>: all active certs for a tenant (for gateway config picker)</li>
 *   <li><b>Vault stats</b>: counts by status</li>
 *   <li><b>Gateway snapshot</b>: all mapped certs for gateway reload</li>
 * </ul>
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
            Map<String, Object> req = objectMapper.readValue(requestBody, new TypeReference<>() {});
            UUID   tenantId = parseUuid(req.get("tenantId"));
            String status   = str(req.get("status"));
            int    page     = intVal(req.get("page"), 0);
            int    size     = intVal(req.get("size"), 20);
            String sortBy   = str(req.getOrDefault("sortBy",   "createdAt"));
            String sortDir  = str(req.getOrDefault("sortDir",  "DESC"));

            var pageResult = vaultService.listCertificates(tenantId, status, page, size, sortBy, sortDir);

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
            Map<String, Object> req = objectMapper.readValue(requestBody, new TypeReference<>() {});
            UUID id       = parseUuid(req.get("id"));
            UUID tenantId = parseUuid(req.get("tenantId"));
            return objectMapper.writeValueAsString(vaultService.getCertificate(id, tenantId));
        } catch (Exception e) {
            log.error("RabbitMQ: cert get failed: {}", e.getMessage(), e);
            return "{\"error\":\"" + e.getMessage() + "\"}";
        }
    }

    @RabbitListener(queues = RabbitTopology.QUEUE_CERTS_ACTIVE_LIST)
    public String handleActiveCertsList(String requestBody) {
        log.debug("RabbitMQ: active certs list request received");
        try {
            Map<String, Object> req = objectMapper.readValue(requestBody, new TypeReference<>() {});
            UUID tenantId = parseUuid(req.get("tenantId"));
            return objectMapper.writeValueAsString(vaultService.listActiveCertificates(tenantId));
        } catch (Exception e) {
            log.error("RabbitMQ: active certs list failed: {}", e.getMessage(), e);
            return "[]";
        }
    }

    @RabbitListener(queues = RabbitTopology.QUEUE_CERTS_STATS)
    public String handleCertStats(String requestBody) {
        log.debug("RabbitMQ: cert stats request received");
        try {
            Map<String, Object> req = objectMapper.readValue(requestBody, new TypeReference<>() {});
            UUID tenantId = parseUuid(req.get("tenantId"));
            return objectMapper.writeValueAsString(vaultService.getStats(tenantId));
        } catch (Exception e) {
            log.error("RabbitMQ: cert stats failed: {}", e.getMessage(), e);
            return "{\"error\":\"" + e.getMessage() + "\"}";
        }
    }

    @RabbitListener(queues = RabbitTopology.QUEUE_CERTS_GATEWAY_SNAPSHOT)
    public String handleGatewayMappedSnapshot(String requestBody) {
        log.debug("RabbitMQ: gateway cert snapshot request received");
        try {
            Map<String, Object> req = objectMapper.readValue(requestBody, new TypeReference<>() {});
            UUID tenantId = parseUuid(req.get("tenantId"));
            return objectMapper.writeValueAsString(vaultService.listGatewayMappedCerts(tenantId));
        } catch (Exception e) {
            log.error("RabbitMQ: gateway cert snapshot failed: {}", e.getMessage(), e);
            return "[]";
        }
    }

    /**
     * Serves decrypted certificate material (PEM chain + optional private key) to the gateway.
     * Request: {@code { "id": "<uuid>", "tenantId": "<uuid>" }}
     * Response: {@code { "certPem": "...", "privateKeyPem": "..." }} or {@code { "error": "..." }}
     * <p><strong>Internal only</strong> — this queue must not be accessible outside the service mesh.
     */
    @RabbitListener(queues = RabbitTopology.QUEUE_CERTS_FETCH_MATERIAL)
    public String handleFetchMaterial(String requestBody) {
        log.debug("RabbitMQ: cert fetch-material request received");
        try {
            Map<String, Object> req = objectMapper.readValue(requestBody, new TypeReference<>() {});
            UUID id       = parseUuid(req.get("id"));
            UUID tenantId = parseUuid(req.get("tenantId"));
            return objectMapper.writeValueAsString(vaultService.fetchDecryptedMaterial(id, tenantId));
        } catch (Exception e) {
            log.error("RabbitMQ: cert fetch-material failed: {}", e.getMessage(), e);
            return "{\"error\":\"" + e.getMessage().replace("\"", "\\\"") + "\"}";
        }
    }

    // ─── Cert Group Query Handlers ────────────────────────────────────────────

    /**
     * Paginated cert-group list query from admin-api.
     * Request: {@code { "tenantId", "status"?, "page"?, "size"?, "sortBy"?, "sortDir"? }}
     */
    @RabbitListener(queues = RabbitTopology.QUEUE_CERT_GROUPS_QUERY)
    public String handleCertGroupsQuery(String requestBody) {
        log.debug("RabbitMQ: cert-groups query received");
        try {
            Map<String, Object> req = objectMapper.readValue(requestBody, new TypeReference<>() {});
            UUID   tenantId = parseUuid(req.get("tenantId"));
            String status   = str(req.get("status"));
            int    page     = intVal(req.get("page"), 0);
            int    size     = intVal(req.get("size"), 20);
            String sortBy   = str(req.getOrDefault("sortBy",  "createdAt"));
            String sortDir  = str(req.getOrDefault("sortDir", "DESC"));

            var pageResult = groupService.listGroups(tenantId, status, page, size, sortBy, sortDir);
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

    /**
     * Single cert-group GET query from admin-api.
     * Request: {@code { "id": "<uuid>", "tenantId": "<uuid>" }}
     */
    @RabbitListener(queues = RabbitTopology.QUEUE_CERT_GROUPS_GET)
    public String handleCertGroupGet(String requestBody) {
        log.debug("RabbitMQ: cert-groups get received");
        try {
            Map<String, Object> req = objectMapper.readValue(requestBody, new TypeReference<>() {});
            UUID id       = parseUuid(req.get("id"));
            UUID tenantId = parseUuid(req.get("tenantId"));
            return objectMapper.writeValueAsString(groupService.getGroup(id, tenantId));
        } catch (Exception e) {
            log.error("RabbitMQ: cert-groups get failed: {}", e.getMessage(), e);
            return "{\"error\":\"" + e.getMessage() + "\"}";
        }
    }

    /**
     * Cert-group members list query from admin-api.
     * Request: {@code { "groupId": "<uuid>", "tenantId": "<uuid>" }}
     */
    @RabbitListener(queues = RabbitTopology.QUEUE_CERT_GROUPS_MEMBERS)
    public String handleCertGroupMembers(String requestBody) {
        log.debug("RabbitMQ: cert-groups members query received");
        try {
            Map<String, Object> req = objectMapper.readValue(requestBody, new TypeReference<>() {});
            UUID groupId  = parseUuid(req.get("groupId"));
            UUID tenantId = parseUuid(req.get("tenantId"));
            return objectMapper.writeValueAsString(groupService.listGroupMembers(groupId, tenantId));
        } catch (Exception e) {
            log.error("RabbitMQ: cert-groups members query failed: {}", e.getMessage(), e);
            return "[]";
        }
    }

    // ─── Private helpers ───────────────────────────────────────────────────────

    private String str(Object val) {
        return val != null ? val.toString() : null;
    }

    private UUID parseUuid(Object val) {
        if (val == null || val.toString().isBlank()) return null;
        try { return UUID.fromString(val.toString()); }
        catch (Exception e) { return null; }
    }

    private int intVal(Object val, int defaultVal) {
        if (val == null) return defaultVal;
        try { return Integer.parseInt(val.toString()); }
        catch (Exception e) { return defaultVal; }
    }
}

