package gr.routify.gateway.client;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import gr.routify.common.event.RabbitTopology;
import lombok.RequiredArgsConstructor;
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
 * <p>Both calls use the RabbitMQ Direct Reply-To pattern (synchronous request/reply).
 * They are <em>blocking</em> at the AMQP level and must be called from a non-reactive
 * thread (e.g. a virtual thread or bounded-elastic scheduler).
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class CertVaultClient {

    private final RabbitTemplate rabbitTemplate;
    private final ObjectMapper   objectMapper;

    /**
     * Fetches all active certificates that have a gateway TLS mapping.
     * Each element is a {@code CertificateDto}-shaped map (no cert material).
     *
     * @param tenantId owning tenant UUID (or {@code null} for all tenants)
     */
    public List<Map<String, Object>> fetchGatewayMappedCerts(UUID tenantId) {
        try {
            String request = tenantId != null
                    ? "{\"tenantId\":\"" + tenantId + "\"}"
                    : "{}";
            Object response = rabbitTemplate.convertSendAndReceive(
                    RabbitTopology.EXCHANGE_CERT_VAULT,
                    RabbitTopology.RK_CERTS_GATEWAY_SNAPSHOT,
                    request);
            if (response == null) {
                log.warn("cert-vault returned null for gateway snapshot (timeout or unavailable)");
                return List.of();
            }
            return objectMapper.readValue(response.toString(), new TypeReference<>() {});
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
            String request = objectMapper.writeValueAsString(
                    Map.of("id", certId.toString(), "tenantId", tenantId.toString()));
            Object response = rabbitTemplate.convertSendAndReceive(
                    RabbitTopology.EXCHANGE_CERT_VAULT,
                    RabbitTopology.RK_CERTS_FETCH_MATERIAL,
                    request);
            if (response == null) {
                log.warn("cert-vault returned null for fetch-material certId={} (timeout or unavailable)", certId);
                return null;
            }
            Map<String, String> result = objectMapper.readValue(response.toString(), new TypeReference<>() {});
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

