package io.routify.gateway.filter;

import io.routify.gateway.certificate.CertificateRegistry;
import io.routify.gateway.certificate.PemCertificateParser;
import io.routify.gateway.certificate.VersionedCertificate;
import io.routify.gateway.filter.shared.GatewayProblemResponse;
import lombok.Data;
import lombok.extern.slf4j.Slf4j;
import org.springframework.cloud.gateway.filter.GatewayFilter;
import org.springframework.cloud.gateway.filter.factory.AbstractGatewayFilterFactory;
import org.springframework.core.Ordered;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;

import javax.security.auth.x500.X500Principal;
import java.security.cert.X509Certificate;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Inbound authentication filter that authenticates a caller by verifying the
 * PEM-encoded X.509 certificate they present (via a request header) against the
 * Routify <strong>Certificate Vault</strong> — specifically against an active,
 * non-revoked version stored in the gateway's {@link CertificateRegistry}.
 *
 * <h3>How it differs from existing cert filters</h3>
 * <ul>
 *   <li>{@link MtlsAuthGatewayFilterFactory} — validates a <em>client-ID + cert pair</em>
 *       against a static list of mappings baked into the filter config at deploy time.</li>
 *   <li>{@link CertRotationGatewayFilterFactory} — enforces rotation: accepts or rejects
 *       based on whether a known cert is still the active version.</li>
 *   <li>{@link CertVaultExpiryCheckGatewayFilterFactory} — a route health-check that blocks
 *       traffic when a vault certificate is about to expire; not an authentication filter.</li>
 *   <li><strong>This filter</strong> — performs full <em>authentication</em>: the caller
 *       presents a certificate, we look it up in the vault registry by {@code logicalId}
 *       (or by fingerprint scan when {@code logicalId} is omitted), verify it is ACTIVE,
 *       and then forward a rich set of X.509 identity headers downstream so every
 *       upstream service knows <em>who</em> the caller is without re-validating the cert.</li>
 * </ul>
 *
 * <h3>Behaviour</h3>
 * <ol>
 *   <li>Reads the PEM certificate from the header named by {@code certificateHeader}
 *       (default {@code X-Client-Certificate}).</li>
 *   <li>If the header is absent and {@code requireCertificate=true} (default) → 401.</li>
 *   <li>Parses the PEM. If parsing fails → 401.</li>
 *   <li>Looks up an active, matching version in the {@link CertificateRegistry}:
 *       <ul>
 *         <li>If {@code logicalId} is set → scoped lookup under that logical ID.</li>
 *         <li>If {@code logicalId} is blank → scans all registered IDs for a fingerprint match
 *             (useful for dynamic client registration).</li>
 *       </ul>
 *   </li>
 *   <li>If no active match is found → 401 with an appropriate error code.</li>
 *   <li>On success → injects the following headers downstream:
 *       <ul>
 *         <li>{@code X-Auth-Type}             — {@code CERT_VAULT}</li>
 *         <li>{@code X-Auth-Cert-Logical-Id}  — vault logical ID</li>
 *         <li>{@code X-Auth-Cert-Version}      — matched version number</li>
 *         <li>{@code X-Auth-Cert-Fingerprint}  — SHA-256 fingerprint</li>
 *         <li>{@code X-Auth-Cert-Subject}      — full Subject DN</li>
 *         <li>{@code X-Auth-Cert-CN}           — extracted Common Name</li>
 *         <li>{@code X-Auth-Cert-Issuer}       — Issuer DN</li>
 *         <li>{@code X-Auth-Cert-Serial}        — serial number (hex)</li>
 *         <li>{@code X-Auth-Cert-Expiry}        — ISO-8601 notAfter timestamp</li>
 *       </ul>
 *   </li>
 *   <li>Optionally strips the raw certificate header before forwarding
 *       ({@code stripCertificateHeader=true}, default {@code false}).</li>
 * </ol>
 *
 * <h3>Config params:</h3>
 * <ul>
 *   <li>{@code logicalId}             — vault logical ID to scope the lookup (optional —
 *                                        if blank, a global fingerprint scan is performed)</li>
 *   <li>{@code certificateHeader}     — request header carrying the PEM cert
 *                                        (default: {@code X-Client-Certificate})</li>
 *   <li>{@code requireCertificate}    — reject with 401 if the header is missing
 *                                        (default: {@code true})</li>
 *   <li>{@code stripCertificateHeader} — remove the raw cert header before forwarding
 *                                        (default: {@code false})</li>
 * </ul>
 *
 * <p>Filter type: {@code AUTH_CERT_VAULT}
 */
@Slf4j
@Component
public class CertVaultAuthGatewayFilterFactory
        extends AbstractGatewayFilterFactory<CertVaultAuthGatewayFilterFactory.Config>
        implements Ordered {

    private static final Pattern CN_PATTERN = Pattern.compile("(?:^|,)\\s*CN=([^,]+)");

    private final CertificateRegistry certificateRegistry;

    public CertVaultAuthGatewayFilterFactory(CertificateRegistry certificateRegistry) {
        super(Config.class);
        this.certificateRegistry = certificateRegistry;
    }

    @Override
    public GatewayFilter apply(Config config) {
        String certHeader   = config.getCertificateHeader() != null
                ? config.getCertificateHeader() : "X-Client-Certificate";
        boolean required    = config.isRequireCertificate();
        boolean stripHeader = config.isStripCertificateHeader();
        String logicalId    = config.getLogicalId() != null
                ? config.getLogicalId().trim() : "";

        return (exchange, chain) -> {
            String pemValue = exchange.getRequest().getHeaders().getFirst(certHeader);

            // ── Missing header ────────────────────────────────────────────────
            if (pemValue == null || pemValue.isBlank()) {
                if (required) {
                    log.debug("AUTH_CERT_VAULT: missing '{}' header — rejecting", certHeader);
                    return unauthorized(exchange, "MISSING_CERTIFICATE",
                            "Client certificate is required in header '" + certHeader + "'");
                }
                log.debug("AUTH_CERT_VAULT: no certificate header — passing through (requireCertificate=false)");
                return chain.filter(exchange);
            }

            // ── Parse PEM ─────────────────────────────────────────────────────
            X509Certificate incoming = PemCertificateParser.parseCertificateFromHeader(pemValue);
            if (incoming == null) {
                log.debug("AUTH_CERT_VAULT: failed to parse PEM from header '{}' — rejecting", certHeader);
                return unauthorized(exchange, "INVALID_CERTIFICATE",
                        "Client certificate in header '" + certHeader + "' could not be parsed");
            }

            // ── Registry lookup ───────────────────────────────────────────────
            Optional<VersionedCertificate> matched = logicalId.isBlank()
                    ? findByFingerprint(incoming)
                    : certificateRegistry.matchCertificate(logicalId, incoming);

            if (matched.isEmpty()) {
                // Check if the cert exists but is inactive (revoked/expired)
                boolean knownButInactive = isKnownButInactive(logicalId, incoming);
                if (knownButInactive) {
                    log.warn("AUTH_CERT_VAULT: certificate is revoked or expired for logicalId='{}' — rejecting",
                            logicalId.isBlank() ? "<scan>" : logicalId);
                    return unauthorized(exchange, "CERTIFICATE_REVOKED_OR_EXPIRED",
                            "The presented certificate has been revoked or has expired. Please rotate.");
                }
                log.warn("AUTH_CERT_VAULT: certificate not found in vault registry (logicalId='{}') — rejecting",
                        logicalId.isBlank() ? "<scan>" : logicalId);
                return unauthorized(exchange, "CERTIFICATE_NOT_REGISTERED",
                        "The presented certificate is not registered in the vault");
            }

            VersionedCertificate cert = matched.get();

            log.debug("AUTH_CERT_VAULT: authenticated — logicalId='{}' v{} fp='{}' subject='{}'",
                    cert.id(), cert.version(), cert.fingerprint(),
                    incoming.getSubjectX500Principal().getName());

            // ── Build enriched downstream request ─────────────────────────────
            var requestBuilder = exchange.getRequest().mutate()
                    .header("X-Auth-Type",            "CERT_VAULT")
                    .header("X-Auth-Cert-Logical-Id",  cert.id())
                    .header("X-Auth-Cert-Version",      String.valueOf(cert.version()))
                    .header("X-Auth-Cert-Fingerprint",  cert.fingerprint())
                    .header("X-Auth-Cert-Subject",      incoming.getSubjectX500Principal().getName())
                    .header("X-Auth-Cert-CN",           extractCN(incoming))
                    .header("X-Auth-Cert-Issuer",       incoming.getIssuerX500Principal().getName())
                    .header("X-Auth-Cert-Serial",
                            incoming.getSerialNumber().toString(16).toUpperCase())
                    .header("X-Auth-Cert-Expiry",       cert.notAfter().toString());

            if (stripHeader) {
                requestBuilder.headers(h -> h.remove(certHeader));
            }

            return chain.filter(exchange.mutate().request(requestBuilder.build()).build());
        };
    }

    @Override
    public int getOrder() {
        // Run at the same priority as other auth filters (order 0)
        return 0;
    }

    // ─── Helpers ──────────────────────────────────────────────────────────────

    /**
     * Scans all registered logical IDs to find an active version matching {@code incoming}.
     * Used when no {@code logicalId} is configured — useful for dynamic client registrations.
     */
    private Optional<VersionedCertificate> findByFingerprint(X509Certificate incoming) {
        return certificateRegistry.registeredIds().stream()
                .map(id -> certificateRegistry.matchCertificate(id, incoming))
                .filter(Optional::isPresent)
                .map(Optional::get)
                .findFirst();
    }

    /**
     * Returns true if the certificate exists in the registry but is REVOKED or EXPIRED.
     * Used to give a more helpful error message to the caller.
     */
    private boolean isKnownButInactive(String logicalId, X509Certificate incoming) {
        if (!logicalId.isBlank()) {
            return certificateRegistry.getAllVersions(logicalId).stream()
                    .anyMatch(v -> v.certificate().equals(incoming));
        }
        // Global scan
        return certificateRegistry.registeredIds().stream()
                .flatMap(id -> certificateRegistry.getAllVersions(id).stream())
                .anyMatch(v -> v.certificate().equals(incoming));
    }

    private static String extractCN(X509Certificate cert) {
        X500Principal subject = cert.getSubjectX500Principal();
        String dn = subject.getName(X500Principal.RFC2253);
        Matcher m = CN_PATTERN.matcher(dn);
        return m.find() ? m.group(1).trim() : "";
    }

    private Mono<Void> unauthorized(org.springframework.web.server.ServerWebExchange exchange,
                                    String errorCode, String detail) {
        return GatewayProblemResponse.status(HttpStatus.UNAUTHORIZED)
                .errorCode(errorCode)
                .detail(detail)
                .write(exchange);
    }

    @Data
    public static class Config {
        /**
         * Vault certificate logical ID to scope the registry lookup.
         * Leave blank to perform a global fingerprint scan across all registered IDs.
         */
        private String logicalId;

        /**
         * Request header that carries the PEM-encoded client certificate.
         * Default: {@code X-Client-Certificate}.
         */
        private String certificateHeader = "X-Client-Certificate";

        /**
         * When {@code true} (default), reject with 401 if the certificate header is absent.
         * When {@code false}, unauthenticated requests are allowed through (useful for
         * optional certificate auth where downstream services make the final access decision).
         */
        private boolean requireCertificate = true;

        /**
         * When {@code true}, the raw certificate header is stripped from the forwarded
         * request before it reaches the upstream service.
         * Default: {@code false} (header is forwarded as-is).
         */
        private boolean stripCertificateHeader = false;
    }
}

