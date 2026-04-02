package gr.routify.gateway.filter;

import gr.routify.gateway.auth.properties.CertificateValuesConfig;
import gr.routify.gateway.certificate.CertificateRegistry;
import gr.routify.gateway.certificate.PemCertificateParser;
import gr.routify.gateway.certificate.VersionedCertificate;
import lombok.extern.slf4j.Slf4j;
import org.springframework.cloud.gateway.filter.GatewayFilter;
import org.springframework.cloud.gateway.filter.factory.AbstractGatewayFilterFactory;
import org.springframework.core.Ordered;
import org.springframework.http.HttpStatus;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;
import reactor.core.publisher.Mono;

import javax.security.auth.x500.X500Principal;
import java.security.cert.Certificate;
import java.security.cert.X509Certificate;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Inbound auth filter — validates both {@code X-Client-Id} and a PEM
 * {@code X-Client-Certificate} header against the {@link CertificateRegistry}.
 * On success injects {@code organization-common-name} (cert Subject CN) downstream.
 * Returns 401 on mismatch.
 */
@Slf4j
@Component
public class MtlsAuthGatewayFilterFactory
        extends AbstractGatewayFilterFactory<CertificateValuesConfig> implements Ordered {

    private final CertificateRegistry certificateRegistry;

    public MtlsAuthGatewayFilterFactory(CertificateRegistry certificateRegistry) {
        super(CertificateValuesConfig.class);
        this.certificateRegistry = certificateRegistry;
    }

    @Override
    public GatewayFilter apply(CertificateValuesConfig config) {
        return (exchange, chain) -> {
            var matchResult = matchIncomingCertificate(config.getValues(), exchange.getRequest());
            if (matchResult == null) {
                log.error("ClientId/Certificate mismatch. Headers: {}", exchange.getRequest().getHeaders());
                return Mono.error(new ResponseStatusException(HttpStatus.UNAUTHORIZED,
                        "Client Authentication failed. ClientId/ClientCertificate mismatch"));
            }

            log.info("Certificate matched: id='{}', v{}, fp='{}', clientId='{}'",
                    matchResult.matched.id(), matchResult.matched.version(),
                    matchResult.matched.fingerprint(), matchResult.mapping.getClientIdValue());

            var request = exchange.getRequest().mutate()
                    .header("organization-common-name",
                            extractCertificateCommonName(matchResult.matched.certificate()))
                    .build();
            return chain.filter(exchange.mutate().request(request).build());
        };
    }

    @Override
    public int getOrder() { return 0; }

    static String extractCertificateCommonName(Certificate cert) {
        if (cert instanceof X509Certificate x509) {
            X500Principal subject = x509.getSubjectX500Principal();
            String dn = subject.getName(X500Principal.RFC2253);
            // Extract CN from Distinguished Name, e.g. "CN=my-client,O=MyOrg,C=US"
            Pattern pattern = Pattern.compile("(?:^|,)\\s*CN=([^,]+)");
            Matcher matcher = pattern.matcher(dn);
            if (matcher.find()) {
                return matcher.group(1).trim();
            }
        }
        return null;
    }

    private MatchResult matchIncomingCertificate(
            List<CertificateValuesConfig.CertificateClientMapping> mappings,
            ServerHttpRequest request) {
        for (var mapping : mappings) {
            if (!mapping.getClientIdValue().equals(
                    request.getHeaders().getFirst(mapping.getClientIdRequestHeader())))
                continue;

            var pemHeader = request.getHeaders().getFirst(mapping.getClientCertificateRequestHeader());
            var incoming = PemCertificateParser.parseCertificateFromHeader(pemHeader);
            if (incoming == null) continue;

            var matched = certificateRegistry.matchCertificate(mapping.getClientCertificateValue(), incoming);
            if (matched.isPresent()) return new MatchResult(mapping, matched.get());
        }
        return null;
    }

    private record MatchResult(CertificateValuesConfig.CertificateClientMapping mapping,
                               VersionedCertificate matched) {}
}

