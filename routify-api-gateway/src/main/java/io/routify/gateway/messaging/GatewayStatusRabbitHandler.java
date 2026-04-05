package io.routify.gateway.messaging;

import io.routify.common.event.QueryRequest;
import io.routify.common.event.QueryResponse;
import io.routify.common.event.RabbitTopology;
import io.routify.gateway.certificate.CertificateRegistry;
import io.routify.gateway.certificate.VersionedCertificate;
import io.routify.gateway.config.GatewayConfigLoader;
import io.routify.gateway.routing.DynamicRouteDefinitionLocator;
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
 *   <li><b>Certificate registry snapshot</b> — returns the full in-memory CertificateRegistry.</li>
 * </ul>
 * All request bodies are deserialised into strongly-typed records by Jackson2JsonMessageConverter.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class GatewayStatusRabbitHandler {

    private final DynamicRouteDefinitionLocator routeLocator;
    private final GatewayConfigLoader           configLoader;
    private final CertificateRegistry           certificateRegistry;

    private static final Instant START_TIME = Instant.now();

    /**
     * Handles gateway status requests.
     * Returns a typed snapshot of the gateway's live state.
     */
    @RabbitListener(queues = RabbitTopology.QUEUE_GATEWAY_STATUS)
    public QueryResponse.GatewayStatus handleStatusRequest(
            @SuppressWarnings("unused") QueryRequest.GatewaySnapshot request) {
        log.debug("RabbitMQ: received gateway status request");
        return new QueryResponse.GatewayStatus(
                "UP",
                routeLocator.getLoadedRouteCount(),
                START_TIME.toString(),
                configLoader.getConfig(),
                Instant.now().toString());
    }

    /**
     * Handles certificate registry snapshot requests from admin-api.
     *
     * <p>Returns a snapshot keyed by logical ID. Only the <em>latest active</em>
     * version for each logical ID is included.
     */
    @RabbitListener(queues = RabbitTopology.QUEUE_GATEWAY_CERT_REGISTRY)
    public QueryResponse.CertRegistrySnapshot handleCertRegistrySnapshot(
            @SuppressWarnings("unused") QueryRequest.GatewaySnapshot request) {
        log.debug("RabbitMQ: received certificate registry snapshot request");
        Map<String, QueryResponse.CertRegistrySnapshot.CertRegistryEntry> entries = new LinkedHashMap<>();
        for (String logicalId : certificateRegistry.registeredIds()) {
            certificateRegistry.getActiveCertificates(logicalId).stream()
                    .max(java.util.Comparator.comparingInt(VersionedCertificate::version))
                    .ifPresent(cert -> entries.put(logicalId,
                            new QueryResponse.CertRegistrySnapshot.CertRegistryEntry(
                                    cert.fingerprint(),
                                    cert.notAfter() != null ? cert.notAfter().toString() : null,
                                    cert.source(),
                                    resolveExpiryStatus(cert),
                                    cert.version())));
        }
        log.debug("RabbitMQ: cert registry snapshot — {} entries", entries.size());
        return new QueryResponse.CertRegistrySnapshot(entries);
    }

    // ─── Private helpers ──────────────────────────────────────────────────────

    private String resolveExpiryStatus(VersionedCertificate cert) {
        if (cert.isExpired()) return "EXPIRED";
        long days = cert.daysUntilExpiry();
        if (days <= 30) return "EXPIRING_SOON";
        return "VALID";
    }
}
