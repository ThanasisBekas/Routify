package io.routify.gateway.filter;

import io.routify.gateway.certificate.CertificateRegistry;
import io.routify.gateway.certificate.VersionedCertificate;
import lombok.Data;
import lombok.extern.slf4j.Slf4j;
import org.springframework.cloud.gateway.filter.GatewayFilter;
import org.springframework.cloud.gateway.filter.factory.AbstractGatewayFilterFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.server.reactive.ServerHttpResponse;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Optional;

/**
 * Gateway filter factory that validates the lifecycle status of a certificate
 * stored in the Routify Certificate Vault (via the in-memory {@link CertificateRegistry}).
 *
 * <h3>Purpose</h3>
 * <p>Whereas {@link CertRotationGatewayFilterFactory} validates that the
 * <em>client-presented</em> PEM certificate matches an active vault entry,
 * this filter performs a <em>route-level</em> health check against a named
 * vault certificate to enforce that:
 * <ul>
 *   <li>The certificate is still <strong>ACTIVE</strong> (not revoked or expired)</li>
 *   <li>Optionally: the certificate is not within the configured
 *       {@code warningDays} window of its expiry date</li>
 * </ul>
 *
 * <h3>Typical use cases</h3>
 * <ul>
 *   <li>Protecting routes that serve resources signed with a particular certificate
 *       (e.g. SAML, document signing) — stop serving if the signing cert has expired.</li>
 *   <li>Enforcing SLA compliance: block traffic once a service certificate enters its
 *       warning window so the operator is forced to rotate before expiry.</li>
 *   <li>Pre-flight check before forwarding to an upstream that requires a client TLS
 *       certificate that is about to expire.</li>
 * </ul>
 *
 * <h3>Behaviour</h3>
 * <ol>
 *   <li>Looks up the certificate by {@code logicalId} in the {@link CertificateRegistry}.</li>
 *   <li>If <strong>no active version</strong> is found → returns {@code 503 Service Unavailable}
 *       with {@code errorCode: CERT_NOT_AVAILABLE}.</li>
 *   <li>If the <strong>best active version is expired</strong> → returns {@code 503}
 *       with {@code errorCode: CERT_EXPIRED}.</li>
 *   <li>If the certificate is within the {@code warningDays} window <strong>and</strong>
 *       {@code rejectOnExpiringSoon=true</strong> → returns {@code 503}
 *       with {@code errorCode: CERT_EXPIRING_SOON}.</li>
 *   <li>Otherwise → the request is forwarded with three informational headers injected:
 *       <ul>
 *         <li>{@code X-Cert-Status}         — {@code ACTIVE}, {@code EXPIRING_SOON}</li>
 *         <li>{@code X-Cert-Expiry}         — ISO-8601 notAfter timestamp</li>
 *         <li>{@code X-Cert-Days-Remaining} — integer days until expiry</li>
 *         <li>{@code X-Cert-Fingerprint}    — SHA-256 fingerprint of the active version</li>
 *       </ul>
 *   </li>
 * </ol>
 *
 * <h3>Config params:</h3>
 * <ul>
 *   <li>{@code logicalId}          — vault certificate logical ID to check (required)</li>
 *   <li>{@code warningDays}        — days before expiry to consider EXPIRING_SOON (default: 30)</li>
 *   <li>{@code rejectOnExpiringSoon} — if {@code true}, also reject EXPIRING_SOON certificates
 *                                    with 503 (default: {@code false} — inject header only)</li>
 *   <li>{@code injectMetadataHeaders} — if {@code true}, inject X-Cert-* headers downstream
 *                                       (default: {@code true})</li>
 * </ul>
 *
 * <p>Filter type: {@code CERT_VAULT_EXPIRY_CHECK}
 */
@Slf4j
@Component
public class CertVaultExpiryCheckGatewayFilterFactory
        extends AbstractGatewayFilterFactory<CertVaultExpiryCheckGatewayFilterFactory.Config> {

    private final CertificateRegistry certificateRegistry;

    public CertVaultExpiryCheckGatewayFilterFactory(CertificateRegistry certificateRegistry) {
        super(Config.class);
        this.certificateRegistry = certificateRegistry;
    }

    @Override
    public GatewayFilter apply(Config config) {
        String logicalId = config.getLogicalId();

        if (logicalId == null || logicalId.isBlank()) {
            log.error("CertVaultExpiryCheck: 'logicalId' config is required but was not provided — filter disabled (pass-through)");
            return (exchange, chain) -> chain.filter(exchange);
        }

        int warningDays = config.getWarningDays() > 0 ? config.getWarningDays() : 30;

        return (exchange, chain) -> {
            List<VersionedCertificate> activeVersions = certificateRegistry.getActiveCertificates(logicalId);

            if (activeVersions.isEmpty()) {
                log.warn("CertVaultExpiryCheck: no active certificate found for logicalId='{}' — rejecting", logicalId);
                return serviceUnavailable(exchange, "CERT_NOT_AVAILABLE",
                        "No active certificate is available for logical ID '" + logicalId + "'");
            }

            // Pick the version with the furthest notAfter (most recently valid)
            Optional<VersionedCertificate> best = activeVersions.stream()
                    .filter(v -> !v.isExpired())
                    .max(java.util.Comparator.comparing(VersionedCertificate::notAfter));

            if (best.isEmpty()) {
                log.warn("CertVaultExpiryCheck: all active versions for logicalId='{}' are expired — rejecting", logicalId);
                return serviceUnavailable(exchange, "CERT_EXPIRED",
                        "All certificates for logical ID '" + logicalId + "' have expired. Please rotate.");
            }

            VersionedCertificate cert = best.get();
            long daysRemaining = ChronoUnit.DAYS.between(Instant.now(), cert.notAfter());
            boolean isExpiringSoon = daysRemaining <= warningDays;

            if (isExpiringSoon && config.isRejectOnExpiringSoon()) {
                log.warn("CertVaultExpiryCheck: certificate for logicalId='{}' expires in {} days (threshold={}d) — rejecting",
                        logicalId, daysRemaining, warningDays);
                return serviceUnavailable(exchange, "CERT_EXPIRING_SOON",
                        "Certificate '" + logicalId + "' expires in " + daysRemaining +
                                " days. Please rotate before the expiry threshold of " + warningDays + " days.");
            }

            String certStatus = isExpiringSoon ? "EXPIRING_SOON" : "ACTIVE";

            log.debug("CertVaultExpiryCheck: logicalId='{}' status={} daysRemaining={} fp='{}'",
                    logicalId, certStatus, daysRemaining, cert.fingerprint());

            if (!config.isInjectMetadataHeaders()) {
                return chain.filter(exchange);
            }

            var mutated = exchange.getRequest().mutate()
                    .header("X-Cert-Status",         certStatus)
                    .header("X-Cert-Expiry",          cert.notAfter().toString())
                    .header("X-Cert-Days-Remaining",  String.valueOf(daysRemaining))
                    .header("X-Cert-Fingerprint",     cert.fingerprint())
                    .build();

            return chain.filter(exchange.mutate().request(mutated).build());
        };
    }

    private Mono<Void> serviceUnavailable(org.springframework.web.server.ServerWebExchange exchange,
                                          String errorCode, String detail) {
        ServerHttpResponse resp = exchange.getResponse();
        resp.setStatusCode(HttpStatus.SERVICE_UNAVAILABLE);
        resp.getHeaders().set("Content-Type", "application/problem+json");
        String body = """
                {"type":"about:blank","title":"Service Unavailable","status":503,\
                "errorCode":"%s","detail":"%s"}""".formatted(errorCode,
                detail.replace("\"", "\\\""));
        return resp.writeWith(Mono.just(resp.bufferFactory().wrap(body.getBytes())));
    }

    @Data
    public static class Config {
        /** Vault certificate logical ID to check. Required. */
        private String logicalId;

        /**
         * Days before expiry at which the certificate is considered EXPIRING_SOON.
         * Default: 30.
         */
        private int warningDays = 30;

        /**
         * When {@code true}, requests are rejected with 503 if the certificate enters
         * the expiry warning window. When {@code false} (default), a header
         * {@code X-Cert-Status: EXPIRING_SOON} is injected but the request proceeds.
         */
        private boolean rejectOnExpiringSoon = false;

        /**
         * When {@code true} (default), injects {@code X-Cert-Status}, {@code X-Cert-Expiry},
         * {@code X-Cert-Days-Remaining}, and {@code X-Cert-Fingerprint} headers downstream.
         */
        private boolean injectMetadataHeaders = true;
    }
}

