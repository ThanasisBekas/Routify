package io.routify.gateway.filter;

import io.routify.gateway.auth.properties.CertificateValuesConfig;
import io.routify.gateway.certificate.CertificateRegistry;
import io.routify.gateway.certificate.VersionedCertificate;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.cloud.gateway.filter.GatewayFilter;
import org.springframework.http.HttpStatus;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import java.security.cert.X509Certificate;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

/**
 * Dedicated unit tests for {@link MtlsAuthGatewayFilterFactory}.
 *
 * <p>Covers: dual config paths (dynamic gateway config ref vs legacy static YAML),
 * PEM header parsing, multi-mapping iteration, CN extraction, mismatch rejection.
 */
@ExtendWith(MockitoExtension.class)
class MtlsAuthGatewayFilterFactoryTest {

    @Mock
    private CertificateRegistry certificateRegistry;

    private MtlsAuthGatewayFilterFactory factory;

    private final X509Certificate cert1 = TestCertificateHelper.generateCert("client-acme");
    private final X509Certificate cert2 = TestCertificateHelper.generateCert("client-globex");

    @BeforeEach
    void setup() {
        factory = new MtlsAuthGatewayFilterFactory(certificateRegistry);
    }

    private CertificateValuesConfig.CertificateClientMapping mapping(
            String clientIdHeader, String clientIdValue,
            String certHeader, String certLogicalId) {
        var m = new CertificateValuesConfig.CertificateClientMapping();
        m.setClientIdRequestHeader(clientIdHeader);
        m.setClientIdValue(clientIdValue);
        m.setClientCertificateRequestHeader(certHeader);
        m.setClientCertificateValue(certLogicalId);
        return m;
    }

    // ═══════════════════════════════════════════════════════════════════════════
    //  Happy path — single mapping
    // ═══════════════════════════════════════════════════════════════════════════

    @Test
    @DisplayName("Valid client-ID + matching cert → pass with organization-common-name header")
    void validMapping_passesWithCnHeader() {
        VersionedCertificate vc = VersionedCertificate.of(
                "cert-acme", 1, cert1, null, "test");
        when(certificateRegistry.matchCertificate(eq("cert-acme"), any()))
                .thenReturn(Optional.of(vc));

        var config = new CertificateValuesConfig();
        config.setValues(List.of(
                mapping("X-Client-Id", "acme", "X-Client-Certificate", "cert-acme")
        ));
        GatewayFilter filter = factory.apply(config);

        var exchange = MockServerWebExchange.from(
                MockServerHttpRequest.get("/api/test")
                        .header("X-Client-Id", "acme")
                        .header("X-Client-Certificate", TestCertificateHelper.toPem(cert1))
                        .build());

        ServerHttpRequest[] captured = {null};
        StepVerifier.create(filter.filter(exchange, ex -> {
            captured[0] = ex.getRequest();
            return Mono.empty();
        })).verifyComplete();

        assertThat(captured[0]).isNotNull();
        String cn = captured[0].getHeaders().getFirst("organization-common-name");
        assertThat(cn).isNotBlank();
        assertThat(cn).contains("client-acme");
    }

    // ═══════════════════════════════════════════════════════════════════════════
    //  Multi-mapping iteration
    // ═══════════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("Multiple mappings")
    class MultipleMappings {

        @Test
        @DisplayName("Second mapping matches → pass")
        void secondMappingMatches() {
            // First mapping: cert-acme — will NOT match because clientId is "globex"
            // Second mapping: cert-globex — matches
            VersionedCertificate vc2 = VersionedCertificate.of(
                    "cert-globex", 1, cert2, null, "test");
            when(certificateRegistry.matchCertificate(eq("cert-globex"), any()))
                    .thenReturn(Optional.of(vc2));

            var config = new CertificateValuesConfig();
            config.setValues(List.of(
                    mapping("X-Client-Id", "acme", "X-Client-Certificate", "cert-acme"),
                    mapping("X-Client-Id", "globex", "X-Client-Certificate", "cert-globex")
            ));
            GatewayFilter filter = factory.apply(config);

            var exchange = MockServerWebExchange.from(
                    MockServerHttpRequest.get("/api/test")
                            .header("X-Client-Id", "globex")
                            .header("X-Client-Certificate", TestCertificateHelper.toPem(cert2))
                            .build());

            ServerHttpRequest[] captured = {null};
            StepVerifier.create(filter.filter(exchange, ex -> {
                captured[0] = ex.getRequest();
                return Mono.empty();
            })).verifyComplete();

            assertThat(captured[0]).isNotNull();
            assertThat(captured[0].getHeaders().getFirst("organization-common-name"))
                    .contains("client-globex");
        }

        @Test
        @DisplayName("No mapping matches clientId → 401")
        void noMappingMatchesClientId() {
            var config = new CertificateValuesConfig();
            config.setValues(List.of(
                    mapping("X-Client-Id", "acme", "X-Client-Certificate", "cert-acme")
            ));
            GatewayFilter filter = factory.apply(config);

            var exchange = MockServerWebExchange.from(
                    MockServerHttpRequest.get("/api/test")
                            .header("X-Client-Id", "unknown-client")
                            .header("X-Client-Certificate", TestCertificateHelper.toPem(cert1))
                            .build());

            StepVerifier.create(filter.filter(exchange, ex -> Mono.empty()))
                    .verifyComplete();

            assertThat(exchange.getResponse().getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        }
    }

    // ═══════════════════════════════════════════════════════════════════════════
    //  Rejection paths
    // ═══════════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("Rejection paths")
    class RejectionPaths {

        @Test
        @DisplayName("ClientId matches but cert doesn't match registry → 401")
        void certMismatch_returns401() {
            when(certificateRegistry.matchCertificate(eq("cert-acme"), any()))
                    .thenReturn(Optional.empty());

            var config = new CertificateValuesConfig();
            config.setValues(List.of(
                    mapping("X-Client-Id", "acme", "X-Client-Certificate", "cert-acme")
            ));
            GatewayFilter filter = factory.apply(config);

            var exchange = MockServerWebExchange.from(
                    MockServerHttpRequest.get("/api/test")
                            .header("X-Client-Id", "acme")
                            .header("X-Client-Certificate", TestCertificateHelper.toPem(cert1))
                            .build());

            StepVerifier.create(filter.filter(exchange, ex -> Mono.empty()))
                    .verifyComplete();

            assertThat(exchange.getResponse().getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        }

        @Test
        @DisplayName("Missing certificate header → 401")
        void missingCertHeader_returns401() {
            var config = new CertificateValuesConfig();
            config.setValues(List.of(
                    mapping("X-Client-Id", "acme", "X-Client-Certificate", "cert-acme")
            ));
            GatewayFilter filter = factory.apply(config);

            var exchange = MockServerWebExchange.from(
                    MockServerHttpRequest.get("/api/test")
                            .header("X-Client-Id", "acme")
                            // No X-Client-Certificate header
                            .build());

            StepVerifier.create(filter.filter(exchange, ex -> Mono.empty()))
                    .verifyComplete();

            assertThat(exchange.getResponse().getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        }

        @Test
        @DisplayName("Missing clientId header → 401 (no mapping matches)")
        void missingClientIdHeader_returns401() {
            var config = new CertificateValuesConfig();
            config.setValues(List.of(
                    mapping("X-Client-Id", "acme", "X-Client-Certificate", "cert-acme")
            ));
            GatewayFilter filter = factory.apply(config);

            var exchange = MockServerWebExchange.from(
                    MockServerHttpRequest.get("/api/test")
                            // No X-Client-Id header
                            .header("X-Client-Certificate", TestCertificateHelper.toPem(cert1))
                            .build());

            StepVerifier.create(filter.filter(exchange, ex -> Mono.empty()))
                    .verifyComplete();

            assertThat(exchange.getResponse().getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        }

        @Test
        @DisplayName("Invalid PEM in certificate header → 401")
        void invalidPem_returns401() {
            var config = new CertificateValuesConfig();
            config.setValues(List.of(
                    mapping("X-Client-Id", "acme", "X-Client-Certificate", "cert-acme")
            ));
            GatewayFilter filter = factory.apply(config);

            var exchange = MockServerWebExchange.from(
                    MockServerHttpRequest.get("/api/test")
                            .header("X-Client-Id", "acme")
                            .header("X-Client-Certificate", "not-valid-pem")
                            .build());

            StepVerifier.create(filter.filter(exchange, ex -> Mono.empty()))
                    .verifyComplete();

            assertThat(exchange.getResponse().getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        }
    }

    // ═══════════════════════════════════════════════════════════════════════════
    //  CN extraction
    // ═══════════════════════════════════════════════════════════════════════════

    @Test
    @DisplayName("extractCertificateCommonName extracts CN from X.509 subject")
    void extractCn() {
        String cn = MtlsAuthGatewayFilterFactory.extractCertificateCommonName(cert1);
        assertThat(cn).isNotNull().contains("client-acme");
    }

    // ═══════════════════════════════════════════════════════════════════════════
    //  Filter order
    // ═══════════════════════════════════════════════════════════════════════════

    @Test
    @DisplayName("Filter order is 0 (auth priority)")
    void filterOrderIsZero() {
        assertThat(factory.getOrder()).isZero();
    }
}

