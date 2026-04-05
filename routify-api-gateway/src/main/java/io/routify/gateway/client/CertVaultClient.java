package io.routify.gateway.client;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.routify.common.client.AmqpServiceClientSupport;
import io.routify.common.event.QueryRequest;
import io.routify.common.event.QueryResponse;
import io.routify.common.event.RabbitTopology;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * RabbitMQ client for communicating with routify-cert-vault.
 *
 * <p>Used by the gateway to:
 * <ul>
 *   <li>Fetch the initial snapshot of all gateway-mapped certificates at startup.</li>
 *   <li>Fetch decrypted certificate material (PEM chain + optional private key) on demand
 *       when a {@code CERTIFICATE_MAPPED_TO_GATEWAY} Kafka event arrives.</li>
 * </ul>
 *
 * <p>Both calls use the RabbitMQ Direct Reply-To pattern (synchronous request/reply)
 * via {@link AmqpServiceClientSupport}, which serialises strongly-typed
 * {@link QueryRequest} objects with the Jackson {@code "type"} discriminator that
 * cert-vault's {@code Jackson2JsonMessageConverter} requires for deserialisation.
 * They are <em>blocking</em> at the AMQP level and must be called from a non-reactive
 * thread (e.g. a virtual thread or bounded-elastic scheduler).
 */
@Slf4j
@Component
public class CertVaultClient extends AmqpServiceClientSupport {

    public CertVaultClient(RabbitTemplate rabbitTemplate, ObjectMapper objectMapper) {
        super(rabbitTemplate, objectMapper, RabbitTopology.EXCHANGE_CERT_VAULT, "api-gateway");
    }

    /**
     * Fetches all active certificates that have a gateway TLS mapping.
     * Each element is a {@code CertificateDto}-shaped map (no cert material).
     *
     * @param tenantId owning tenant UUID (or {@code null} for all tenants)
     */
    public List<Map<String, Object>> fetchGatewayMappedCerts(UUID tenantId) {
        try {
            QueryResponse.CertsList response = rpc(
                    RabbitTopology.RK_CERTS_GATEWAY_SNAPSHOT,
                    new QueryRequest.CertsGatewaySnapshot(tenantId),
                    QueryResponse.CertsList.class);
            // Convert each CertSummary record to a Map for downstream consumers
            return response.items().stream()
                    .map(cert -> objectMapper.convertValue(cert, new TypeReference<Map<String, Object>>() {}))
                    .toList();
        } catch (Exception e) {
            log.error("Failed to fetch gateway cert snapshot from cert-vault: {}", e.getMessage(), e);
            return List.of();
        }
    }

    /**
     * Fetches the decrypted PEM certificate chain and optional private key for a single cert.
     *
     * @param certId   vault certificate UUID
     * @param tenantId owning tenant UUID
     * @return map with keys {@code certPem} (String) and {@code privateKeyPem} (String, nullable),
     *         or {@code null} if the call failed or timed out
     */
    public Map<String, String> fetchCertMaterial(UUID certId, UUID tenantId) {
        try {
            QueryResponse.GatewayConfig response = rpc(
                    RabbitTopology.RK_CERTS_FETCH_MATERIAL,
                    new QueryRequest.CertFetchMaterial(certId, tenantId),
                    QueryResponse.GatewayConfig.class);
            @SuppressWarnings("unchecked")
            Map<String, String> result = (Map<String, String>) (Map<?, ?>) response.config();
            if (result.containsKey("error")) {
                log.error("cert-vault returned error for certId={}: {}", certId, result.get("error"));
                return null;
            }
            return result;
        } catch (Exception e) {
            log.error("Failed to fetch cert material from cert-vault for certId={}: {}", certId, e.getMessage(), e);
            return null;
        }
    }
}

