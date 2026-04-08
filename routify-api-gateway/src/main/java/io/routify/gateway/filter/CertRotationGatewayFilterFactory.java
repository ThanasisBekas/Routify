package io.routify.gateway.filter;

import io.routify.gateway.certificate.CertificateRegistry;
import io.routify.gateway.certificate.PemCertificateParser;
import io.routify.gateway.certificate.VersionedCertificate;
import io.routify.gateway.filter.shared.GatewayProblemResponse;
import lombok.Data;
import lombok.extern.slf4j.Slf4j;
import org.springframework.cloud.gateway.filter.GatewayFilter;
import org.springframework.cloud.gateway.filter.factory.AbstractGatewayFilterFactory;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;

import java.security.cert.X509Certificate;
import java.util.List;

/**
 * Gateway filter factory that enforces certificate rotation on mTLS routes by
 * checking the client's presented certificate against the active versions stored in
 * the {@link CertificateRegistry}.
 *
 * <h3>How it works</h3>
 * <ol>
 *   <li>Reads the PEM-encoded client certificate from the configured request header
 *       (default: {@code X-Client-Certificate}).</li>
 *   <li>Parses the PEM and looks up the logical ID in the {@link CertificateRegistry}.</li>
 *   <li>If the certificate matches an <em>active</em> version → request is forwarded with
 *       the certificate version number injected as {@code X-Cert-Version}.</li>
 *   <li>If the certificate is present but matches an <em>inactive/revoked/expired</em>
 *       version → request is rejected with {@code 401 Unauthorized} and an error
 *       detail advising the client to rotate.</li>
 *   <li>If the certificate header is absent → request is rejected with {@code 401}.</li>
 * </ol>
 *
 * <h3>Config params:</h3>
 * <ul>
 *   <li>{@code logicalId}         — the logical certificate ID in the registry (required)</li>
 *   <li>{@code certificateHeader} — header that carries the PEM cert
 *       (default: {@code X-Client-Certificate})</li>
 * </ul>
 *
 * <p>Filter type: {@code CERT_ROTATION}
 */
@Slf4j
@Component
public class CertRotationGatewayFilterFactory
        extends AbstractGatewayFilterFactory<CertRotationGatewayFilterFactory.Config> {

    private final CertificateRegistry certificateRegistry;

    public CertRotationGatewayFilterFactory(CertificateRegistry certificateRegistry) {
        super(Config.class);
        this.certificateRegistry = certificateRegistry;
    }

    @Override
    public GatewayFilter apply(Config config) {
        String logicalId   = config.getLogicalId();
        String certHeader  = config.getCertificateHeader() != null
                ? config.getCertificateHeader() : "X-Client-Certificate";

        if (logicalId == null || logicalId.isBlank()) {
            log.error("CertRotation: 'logicalId' config is required but was not provided — filter disabled");
            return (exchange, chain) -> chain.filter(exchange);
        }

        return (exchange, chain) -> {
            String pemHeader = exchange.getRequest().getHeaders().getFirst(certHeader);
            if (pemHeader == null || pemHeader.isBlank()) {
                log.debug("CertRotation: missing '{}' header on request", certHeader);
                return unauthorized(exchange, "MISSING_CERTIFICATE",
                        "Client certificate is required in header '" + certHeader + "'");
            }

            X509Certificate incoming = PemCertificateParser.parseCertificateFromHeader(pemHeader);
            if (incoming == null) {
                log.debug("CertRotation: failed to parse PEM certificate from header '{}'", certHeader);
                return unauthorized(exchange, "INVALID_CERTIFICATE",
                        "Client certificate could not be parsed from header '" + certHeader + "'");
            }

            // Check active versions first
            java.util.Optional<VersionedCertificate> active =
                    certificateRegistry.matchCertificate(logicalId, incoming);
            if (active.isPresent()) {
                VersionedCertificate cert = active.get();
                log.debug("CertRotation: active match id='{}' v{} fp='{}'",
                        logicalId, cert.version(), cert.fingerprint());
                var mutated = exchange.getRequest().mutate()
                        .header("X-Cert-Version", String.valueOf(cert.version()))
                        .header("X-Cert-Fingerprint", cert.fingerprint())
                        .build();
                return chain.filter(exchange.mutate().request(mutated).build());
            }

            // Not active — check whether any inactive version matches (rotation needed)
            List<VersionedCertificate> allVersions = certificateRegistry.getAllVersions(logicalId);
            boolean knownButInactive = allVersions.stream()
                    .anyMatch(v -> v.certificate().equals(incoming));

            if (knownButInactive) {
                log.warn("CertRotation: certificate matched an inactive version for id='{}' — rotation required",
                        logicalId);
                return unauthorized(exchange, "CERTIFICATE_ROTATION_REQUIRED",
                        "The presented certificate has been revoked or expired. Please rotate to the current certificate.");
            }

            log.warn("CertRotation: certificate not found in registry for id='{}'", logicalId);
            return unauthorized(exchange, "CERTIFICATE_UNKNOWN",
                    "The presented certificate is not registered for this route");
        };
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
        /** Logical certificate ID in the CertificateRegistry. Required. */
        private String logicalId;
        /** Request header containing the PEM-encoded client certificate. Default: X-Client-Certificate. */
        private String certificateHeader = "X-Client-Certificate";
    }
}

