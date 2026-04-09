package io.routify.gateway.filter;

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
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.http.HttpStatus;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import java.security.cert.X509Certificate;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link CertVaultAuthGatewayFilterFactory}.
 *
 * <p>Covers: PEM parsing, scoped vs global fingerprint lookup,
 * revoked/expired detection, 9 identity headers injection,
 * requireCertificate flag, stripCertificateHeader flag.
 */
@ExtendWith(MockitoExtension.class)
class CertVaultAuthGatewayFilterFactoryTest {

    @Mock
    private CertificateRegistry certificateRegistry;

    private CertVaultAuthGatewayFilterFactory factory;

    private final X509Certificate validCert = TestCertificateHelper.generateCert("test-client");

    @BeforeEach
    void setup() {
        factory = new CertVaultAuthGatewayFilterFactory(certificateRegistry);
    }

    private GatewayFilterChain capturingChain(MockServerHttpRequest.BaseBuilder<?> ignored,
                                               org.springframework.http.server.reactive.ServerHttpRequest[] captured) {
        return ex -> {
            captured[0] = ex.getRequest();
            return Mono.empty();
        };
    }

    // ═══════════════════════════════════════════════════════════════════════════
    //  Missing certificate header
    // ═══════════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("Missing certificate header")
    class MissingCertHeader {

        @Test
        @DisplayName("requireCertificate=true + missing header → 401 MISSING_CERTIFICATE")
        void requiredAndMissing_returns401() {
            var config = new CertVaultAuthGatewayFilterFactory.Config();
            config.setRequireCertificate(true);
            GatewayFilter filter = factory.apply(config);

            var exchange = MockServerWebExchange.from(
                    MockServerHttpRequest.get("/api/test").build());

            StepVerifier.create(filter.filter(exchange, ex -> Mono.empty()))
                    .verifyComplete();

            assertThat(exchange.getResponse().getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        }

        @Test
        @DisplayName("requireCertificate=false + missing header → pass-through")
        void optionalAndMissing_passesThrough() {
            var config = new CertVaultAuthGatewayFilterFactory.Config();
            config.setRequireCertificate(false);
            GatewayFilter filter = factory.apply(config);

            var exchange = MockServerWebExchange.from(
                    MockServerHttpRequest.get("/api/test").build());

            boolean[] chainCalled = {false};
            StepVerifier.create(filter.filter(exchange, ex -> {
                chainCalled[0] = true;
                return Mono.empty();
            })).verifyComplete();

            assertThat(chainCalled[0]).isTrue();
        }

        @Test
        @DisplayName("Blank certificate header → treated as missing")
        void blankHeader_treatedAsMissing() {
            var config = new CertVaultAuthGatewayFilterFactory.Config();
            config.setRequireCertificate(true);
            GatewayFilter filter = factory.apply(config);

            var exchange = MockServerWebExchange.from(
                    MockServerHttpRequest.get("/api/test")
                            .header("X-Client-Certificate", "   ")
                            .build());

            StepVerifier.create(filter.filter(exchange, ex -> Mono.empty()))
                    .verifyComplete();

            assertThat(exchange.getResponse().getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        }
    }

    // ═══════════════════════════════════════════════════════════════════════════
    //  Invalid PEM
    // ═══════════════════════════════════════════════════════════════════════════

    @Test
    @DisplayName("Unparseable PEM → 401 INVALID_CERTIFICATE")
    void invalidPem_returns401() {
        var config = new CertVaultAuthGatewayFilterFactory.Config();
        GatewayFilter filter = factory.apply(config);

        var exchange = MockServerWebExchange.from(
                MockServerHttpRequest.get("/api/test")
                        .header("X-Client-Certificate", "not-a-valid-pem")
                        .build());

        StepVerifier.create(filter.filter(exchange, ex -> Mono.empty()))
                .verifyComplete();

        assertThat(exchange.getResponse().getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    // ═══════════════════════════════════════════════════════════════════════════
    //  Scoped lookup (logicalId set)
    // ═══════════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("Scoped lookup (logicalId configured)")
    class ScopedLookup {

        @Test
        @DisplayName("Active match → pass-through with 9 identity headers")
        void activeMatch_injectsHeaders() {
            VersionedCertificate vc = VersionedCertificate.of(
                    "my-cert", 1, validCert, null, "test");
            when(certificateRegistry.matchCertificate(eq("my-cert"), any()))
                    .thenReturn(Optional.of(vc));

            var config = new CertVaultAuthGatewayFilterFactory.Config();
            config.setLogicalId("my-cert");
            GatewayFilter filter = factory.apply(config);

            var exchange = MockServerWebExchange.from(
                    MockServerHttpRequest.get("/api/test")
                            .header("X-Client-Certificate", TestCertificateHelper.toPem(validCert))
                            .build());

            var captured = new org.springframework.http.server.reactive.ServerHttpRequest[]{null};
            StepVerifier.create(filter.filter(exchange, ex -> {
                captured[0] = ex.getRequest();
                return Mono.empty();
            })).verifyComplete();

            assertThat(captured[0]).isNotNull();
            var headers = captured[0].getHeaders();
            assertThat(headers.getFirst("X-Auth-Type")).isEqualTo("CERT_VAULT");
            assertThat(headers.getFirst("X-Auth-Cert-Logical-Id")).isEqualTo("my-cert");
            assertThat(headers.getFirst("X-Auth-Cert-Version")).isEqualTo("1");
            assertThat(headers.getFirst("X-Auth-Cert-Fingerprint")).isNotBlank();
            assertThat(headers.getFirst("X-Auth-Cert-Subject")).isNotBlank();
            assertThat(headers.getFirst("X-Auth-Cert-CN")).isNotBlank();
            assertThat(headers.getFirst("X-Auth-Cert-Issuer")).isNotBlank();
            assertThat(headers.getFirst("X-Auth-Cert-Serial")).isNotBlank();
            assertThat(headers.getFirst("X-Auth-Cert-Expiry")).isNotBlank();
        }

        @Test
        @DisplayName("No match, known but inactive → 401 CERTIFICATE_REVOKED_OR_EXPIRED")
        void knownButInactive_returns401() {
            when(certificateRegistry.matchCertificate(eq("my-cert"), any()))
                    .thenReturn(Optional.empty());
            // The cert exists in all versions but is inactive
            VersionedCertificate inactiveVc = VersionedCertificate.of(
                    "my-cert", 1, validCert, null, "test");
            when(certificateRegistry.getAllVersions("my-cert"))
                    .thenReturn(List.of(inactiveVc));

            var config = new CertVaultAuthGatewayFilterFactory.Config();
            config.setLogicalId("my-cert");
            GatewayFilter filter = factory.apply(config);

            var exchange = MockServerWebExchange.from(
                    MockServerHttpRequest.get("/api/test")
                            .header("X-Client-Certificate", TestCertificateHelper.toPem(validCert))
                            .build());

            StepVerifier.create(filter.filter(exchange, ex -> Mono.empty()))
                    .verifyComplete();

            assertThat(exchange.getResponse().getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        }

        @Test
        @DisplayName("No match, completely unknown → 401 CERTIFICATE_NOT_REGISTERED")
        void unknownCert_returns401() {
            when(certificateRegistry.matchCertificate(eq("my-cert"), any()))
                    .thenReturn(Optional.empty());
            when(certificateRegistry.getAllVersions("my-cert"))
                    .thenReturn(List.of());

            var config = new CertVaultAuthGatewayFilterFactory.Config();
            config.setLogicalId("my-cert");
            GatewayFilter filter = factory.apply(config);

            var exchange = MockServerWebExchange.from(
                    MockServerHttpRequest.get("/api/test")
                            .header("X-Client-Certificate", TestCertificateHelper.toPem(validCert))
                            .build());

            StepVerifier.create(filter.filter(exchange, ex -> Mono.empty()))
                    .verifyComplete();

            assertThat(exchange.getResponse().getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        }
    }

    // ═══════════════════════════════════════════════════════════════════════════
    //  Global fingerprint scan (logicalId blank)
    // ═══════════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("Global fingerprint scan (logicalId blank)")
    class GlobalScan {

        @Test
        @DisplayName("Global scan finds active match → injects headers")
        void globalScanActiveMatch() {
            VersionedCertificate vc = VersionedCertificate.of(
                    "discovered-cert", 2, validCert, null, "test");
            when(certificateRegistry.registeredIds()).thenReturn(Set.of("discovered-cert"));
            when(certificateRegistry.matchCertificate(eq("discovered-cert"), any()))
                    .thenReturn(Optional.of(vc));

            var config = new CertVaultAuthGatewayFilterFactory.Config();
            // logicalId left blank — global scan
            GatewayFilter filter = factory.apply(config);

            var exchange = MockServerWebExchange.from(
                    MockServerHttpRequest.get("/api/test")
                            .header("X-Client-Certificate", TestCertificateHelper.toPem(validCert))
                            .build());

            var captured = new org.springframework.http.server.reactive.ServerHttpRequest[]{null};
            StepVerifier.create(filter.filter(exchange, ex -> {
                captured[0] = ex.getRequest();
                return Mono.empty();
            })).verifyComplete();

            assertThat(captured[0]).isNotNull();
            assertThat(captured[0].getHeaders().getFirst("X-Auth-Cert-Logical-Id"))
                    .isEqualTo("discovered-cert");
        }

        @Test
        @DisplayName("Global scan no match → 401")
        void globalScanNoMatch() {
            when(certificateRegistry.registeredIds()).thenReturn(Set.of("other-cert"));
            when(certificateRegistry.matchCertificate(eq("other-cert"), any()))
                    .thenReturn(Optional.empty());
            when(certificateRegistry.getAllVersions("other-cert"))
                    .thenReturn(List.of());

            var config = new CertVaultAuthGatewayFilterFactory.Config();
            GatewayFilter filter = factory.apply(config);

            var exchange = MockServerWebExchange.from(
                    MockServerHttpRequest.get("/api/test")
                            .header("X-Client-Certificate", TestCertificateHelper.toPem(validCert))
                            .build());

            StepVerifier.create(filter.filter(exchange, ex -> Mono.empty()))
                    .verifyComplete();

            assertThat(exchange.getResponse().getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        }
    }

    // ═══════════════════════════════════════════════════════════════════════════
    //  stripCertificateHeader
    // ═══════════════════════════════════════════════════════════════════════════

    @Test
    @DisplayName("stripCertificateHeader=true removes raw cert header before forwarding")
    void stripCertHeader_removesHeader() {
        VersionedCertificate vc = VersionedCertificate.of(
                "my-cert", 1, validCert, null, "test");
        when(certificateRegistry.matchCertificate(eq("my-cert"), any()))
                .thenReturn(Optional.of(vc));

        var config = new CertVaultAuthGatewayFilterFactory.Config();
        config.setLogicalId("my-cert");
        config.setStripCertificateHeader(true);
        GatewayFilter filter = factory.apply(config);

        var exchange = MockServerWebExchange.from(
                MockServerHttpRequest.get("/api/test")
                        .header("X-Client-Certificate", TestCertificateHelper.toPem(validCert))
                        .build());

        var captured = new org.springframework.http.server.reactive.ServerHttpRequest[]{null};
        StepVerifier.create(filter.filter(exchange, ex -> {
            captured[0] = ex.getRequest();
            return Mono.empty();
        })).verifyComplete();

        assertThat(captured[0]).isNotNull();
        assertThat(captured[0].getHeaders().getFirst("X-Client-Certificate")).isNull();
        assertThat(captured[0].getHeaders().getFirst("X-Auth-Type")).isEqualTo("CERT_VAULT");
    }

    // ═══════════════════════════════════════════════════════════════════════════
    //  Custom certificate header name
    // ═══════════════════════════════════════════════════════════════════════════

    @Test
    @DisplayName("Custom certificateHeader name is respected")
    void customCertHeaderName() {
        VersionedCertificate vc = VersionedCertificate.of(
                "my-cert", 1, validCert, null, "test");
        when(certificateRegistry.matchCertificate(eq("my-cert"), any()))
                .thenReturn(Optional.of(vc));

        var config = new CertVaultAuthGatewayFilterFactory.Config();
        config.setLogicalId("my-cert");
        config.setCertificateHeader("X-Custom-Cert");
        GatewayFilter filter = factory.apply(config);

        // Wrong header name → treated as missing
        var exchangeMissing = MockServerWebExchange.from(
                MockServerHttpRequest.get("/api/test")
                        .header("X-Client-Certificate", TestCertificateHelper.toPem(validCert))
                        .build());

        StepVerifier.create(filter.filter(exchangeMissing, ex -> Mono.empty()))
                .verifyComplete();
        assertThat(exchangeMissing.getResponse().getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);

        // Correct custom header → authenticated
        var exchangeCorrect = MockServerWebExchange.from(
                MockServerHttpRequest.get("/api/test")
                        .header("X-Custom-Cert", TestCertificateHelper.toPem(validCert))
                        .build());

        var captured = new org.springframework.http.server.reactive.ServerHttpRequest[]{null};
        StepVerifier.create(filter.filter(exchangeCorrect, ex -> {
            captured[0] = ex.getRequest();
            return Mono.empty();
        })).verifyComplete();

        assertThat(captured[0].getHeaders().getFirst("X-Auth-Type")).isEqualTo("CERT_VAULT");
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

