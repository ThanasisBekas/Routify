package gr.routify.admin.client;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import gr.routify.common.event.KafkaTopics;
import gr.routify.common.event.RabbitTopology;
import io.github.resilience4j.circuitbreaker.annotation.CircuitBreaker;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageBuilder;
import org.springframework.amqp.core.MessageProperties;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
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
@RequiredArgsConstructor
public class CertVaultMessagingClient {

    private final RabbitTemplate               rabbitTemplate;
    private final KafkaTemplate<String, String> kafkaTemplate;
    private final ObjectMapper                  objectMapper;

    // ─── Queries (RabbitMQ) ────────────────────────────────────────────────────

    @CircuitBreaker(name = "cert-vault", fallbackMethod = "queryCertificatesFallback")
    public Map<String, Object> queryCertificates(UUID tenantId, String status, int page, int size,
                                                  String sortBy, String sortDir) {
        try {
            var req = Map.of(
                    "tenantId", tenantId != null ? tenantId.toString() : "",
                    "status",   status != null ? status : "",
                    "page",     page, "size", size,
                    "sortBy",   sortBy  != null ? sortBy  : "createdAt",
                    "sortDir",  sortDir != null ? sortDir : "DESC"
            );
            return rpcCertVault(RabbitTopology.RK_CERTS_QUERY, req);
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
            var req = Map.of("id", id.toString(), "tenantId", tenantId.toString());
            return rpcCertVault(RabbitTopology.RK_CERTS_GET, req);
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
            var req = Map.of("tenantId", tenantId != null ? tenantId.toString() : "");
            String body = objectMapper.writeValueAsString(req);
            Message msg = MessageBuilder
                    .withBody(body.getBytes(StandardCharsets.UTF_8))
                    .andProperties(buildJsonProps())
                    .build();
            Message reply = rabbitTemplate.sendAndReceive(
                    RabbitTopology.EXCHANGE_CERT_VAULT, RabbitTopology.RK_CERTS_ACTIVE_LIST, msg);
            if (reply == null) return java.util.List.of();
            return objectMapper.readValue(
                    new String(reply.getBody(), StandardCharsets.UTF_8), new TypeReference<java.util.List<?>>() {});
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
            var req = Map.of("tenantId", tenantId != null ? tenantId.toString() : "");
            String body = objectMapper.writeValueAsString(req);
            Message msg = MessageBuilder
                    .withBody(body.getBytes(StandardCharsets.UTF_8))
                    .andProperties(buildJsonProps())
                    .build();
            Message reply = rabbitTemplate.sendAndReceive(
                    RabbitTopology.EXCHANGE_CERT_VAULT, RabbitTopology.RK_CERTS_GATEWAY_SNAPSHOT, msg);
            if (reply == null) return java.util.List.of();
            return objectMapper.readValue(
                    new String(reply.getBody(), StandardCharsets.UTF_8), new TypeReference<java.util.List<?>>() {});
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
            var req = Map.of("tenantId", tenantId != null ? tenantId.toString() : "");
            return rpcCertVault(RabbitTopology.RK_CERTS_STATS, req);
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
            var req = Map.of(
                    "tenantId", tenantId != null ? tenantId.toString() : "",
                    "status",   status != null ? status : "",
                    "page",     page, "size", size,
                    "sortBy",   sortBy  != null ? sortBy  : "createdAt",
                    "sortDir",  sortDir != null ? sortDir : "DESC"
            );
            return rpcCertVault(RabbitTopology.RK_CERT_GROUPS_QUERY, req);
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
            var req = Map.of("id", id.toString(), "tenantId", tenantId.toString());
            return rpcCertVault(RabbitTopology.RK_CERT_GROUPS_GET, req);
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
            var req = Map.of("groupId", groupId.toString(), "tenantId", tenantId.toString());
            String body = objectMapper.writeValueAsString(req);
            Message msg = MessageBuilder
                    .withBody(body.getBytes(StandardCharsets.UTF_8))
                    .andProperties(buildJsonProps())
                    .build();
            Message reply = rabbitTemplate.sendAndReceive(
                    RabbitTopology.EXCHANGE_CERT_VAULT, RabbitTopology.RK_CERT_GROUPS_MEMBERS, msg);
            if (reply == null) return java.util.List.of();
            return objectMapper.readValue(
                    new String(reply.getBody(), StandardCharsets.UTF_8), new TypeReference<java.util.List<?>>() {});
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

    public void sendCertCommand(String command, Map<String, Object> payload, UUID tenantId, String userId) {
        try {
            var envelope = new java.util.LinkedHashMap<String, Object>();
            envelope.put("command",     command);
            envelope.put("tenantId",    tenantId != null ? tenantId.toString() : null);
            envelope.put("requestedBy", userId);
            envelope.put("payload",     payload);
            envelope.put("commandId",   UUID.randomUUID().toString());
            kafkaTemplate.send(
                    KafkaTopics.CERT_COMMANDS,
                    tenantId != null ? tenantId.toString() : "global",
                    objectMapper.writeValueAsString(envelope));
            log.info("Cert command published: command={} tenantId={} by={}", command, tenantId, userId);
        } catch (Exception e) {
            log.error("Failed to publish cert command {}: {}", command, e.getMessage(), e);
            throw new RuntimeException("Failed to publish cert command: " + command, e);
        }
    }

    // ─── Private helpers ──────────────────────────────────────────────────────

    @SuppressWarnings("unchecked")
    private Map<String, Object> rpcCertVault(String routingKey, Object requestBody) throws Exception {
        String body = objectMapper.writeValueAsString(requestBody);
        Message msg = MessageBuilder
                .withBody(body.getBytes(StandardCharsets.UTF_8))
                .andProperties(buildJsonProps())
                .build();
        Message reply = rabbitTemplate.sendAndReceive(
                RabbitTopology.EXCHANGE_CERT_VAULT, routingKey, msg);
        if (reply == null) return Map.of("error", "cert-vault unavailable");
        return objectMapper.readValue(
                new String(reply.getBody(), StandardCharsets.UTF_8), new TypeReference<>() {});
    }

    private MessageProperties buildJsonProps() {
        MessageProperties props = new MessageProperties();
        props.setContentType(MessageProperties.CONTENT_TYPE_JSON);
        return props;
    }
}

