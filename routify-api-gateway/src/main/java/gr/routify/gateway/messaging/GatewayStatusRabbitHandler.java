package gr.routify.gateway.messaging;

import com.fasterxml.jackson.databind.ObjectMapper;
import gr.routify.common.event.RabbitTopology;
import gr.routify.gateway.certificate.CertificateRegistry;
import gr.routify.gateway.certificate.VersionedCertificate;
import gr.routify.gateway.config.GatewayConfigLoader;
import gr.routify.gateway.routing.DynamicRouteDefinitionLocator;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * RabbitMQ request/reply handler for routify-api-gateway.
 *
 * <p>Handles two types of synchronous requests from routify-admin-api:
 * <ul>
 *   <li><b>Status requests</b> — returns live route count, applied config, and uptime.</li>
 *   <li><b>Certificate registry snapshot</b> — returns the full in-memory CertificateRegistry
 *       as a flat {@code Map<logicalId, {fingerprint, notAfter, source, status, version}>}
 *       used by the admin dashboard TLS tab to show which certs are live in the gateway.</li>
 * </ul>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class GatewayStatusRabbitHandler {

    private final DynamicRouteDefinitionLocator routeLocator;
    private final GatewayConfigLoader           configLoader;
    private final CertificateRegistry           certificateRegistry;
    private final ObjectMapper                  objectMapper;

    private static final Instant START_TIME = Instant.now();

    /**
     * Handles gateway status requests.
     * Returns a JSON snapshot of the gateway's live state.
     */
    @RabbitListener(queues = RabbitTopology.QUEUE_GATEWAY_STATUS)
    public String handleStatusRequest(@SuppressWarnings("unused") String requestBody) {
        log.debug("RabbitMQ: received gateway status request");
        try {
            Map<String, Object> status = new LinkedHashMap<>();
            status.put("status",      "UP");
            status.put("routeCount",  routeLocator.getLoadedRouteCount());
            status.put("startTime",   START_TIME.toString());
            status.put("config",      configLoader.getConfig());
            status.put("timestamp",   Instant.now().toString());
            return objectMapper.writeValueAsString(status);
        } catch (Exception e) {
            log.error("RabbitMQ: failed to build gateway status: {}", e.getMessage(), e);
            return "{\"status\":\"ERROR\",\"error\":\"" + e.getMessage() + "\"}";
        }
    }

    /**
     * Handles certificate registry snapshot requests from admin-api.
     *
     * <p>Returns a flat JSON object keyed by logical ID. Only the <em>latest active</em>
     * version for each logical ID is included. Format:
     * <pre>{@code
     * {
     *   "my-inbound-tls": {
     *     "fingerprint": "AA:BB:...",
     *     "notAfter":    "2027-01-01T00:00:00Z",
     *     "source":      "vault:cert-uuid",
     *     "status":      "VALID",
     *     "version":     1
     *   }
     * }
     * }</pre>
     */
    @RabbitListener(queues = RabbitTopology.QUEUE_GATEWAY_CERT_REGISTRY)
    public String handleCertRegistrySnapshot(@SuppressWarnings("unused") String requestBody) {
        log.debug("RabbitMQ: received certificate registry snapshot request");
        try {
            Map<String, Object> snapshot = new LinkedHashMap<>();
            for (String logicalId : certificateRegistry.registeredIds()) {
                certificateRegistry.getActiveCertificates(logicalId).stream()
                        .max(java.util.Comparator.comparingInt(VersionedCertificate::version))
                        .ifPresent(cert -> {
                            Map<String, Object> entry = new LinkedHashMap<>();
                            entry.put("fingerprint", cert.fingerprint());
                            entry.put("notAfter",    cert.notAfter() != null ? cert.notAfter().toString() : null);
                            entry.put("source",      cert.source());
                            entry.put("status",      resolveExpiryStatus(cert));
                            entry.put("version",     cert.version());
                            snapshot.put(logicalId, entry);
                        });
            }
            log.debug("RabbitMQ: cert registry snapshot — {} entries", snapshot.size());
            return objectMapper.writeValueAsString(snapshot);
        } catch (Exception e) {
            log.error("RabbitMQ: failed to build cert registry snapshot: {}", e.getMessage(), e);
            return "{}";
        }
    }

    // ─── Private helpers ──────────────────────────────────────────────────────

    private String resolveExpiryStatus(VersionedCertificate cert) {
        if (cert.isExpired()) return "EXPIRED";
        long days = cert.daysUntilExpiry();
        // Use a conservative 30-day threshold for the admin panel warning
        if (days <= 30) return "EXPIRING_SOON";
        return "VALID";
    }
}

