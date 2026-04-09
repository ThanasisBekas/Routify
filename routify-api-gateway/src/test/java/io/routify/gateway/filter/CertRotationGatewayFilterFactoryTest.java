package io.routify.gateway.filter;

import io.routify.gateway.certificate.CertificateRegistry;
import io.routify.gateway.certificate.VersionedCertificate;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
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
 * Unit tests for {@link CertRotationGatewayFilterFactory}.
 *
 * <p>Covers: active match → pass, inactive match → reject,
 * unknown cert → reject, missing header → reject, missing logicalId → pass-through.
 */
@ExtendWith(MockitoExtension.class)
class CertRotationGatewayFilterFactoryTest {

    @Mock
    private CertificateRegistry certificateRegistry;

    private CertRotationGatewayFilterFactory factory;

    private final X509Certificate validCert = TestCertificateHelper.generateCert("rotation-client");

    @BeforeEach
    void setup() {
        factory = new CertRotationGatewayFilterFactory(certificateRegistry);
    }

    // ─── Missing logicalId ────────────────────────────────────────────────────

    @Test
    @DisplayName("Blank logicalId → filter disabled, pass-through")
    void blankLogicalId_passesThrough() {
        var config = new CertRotationGatewayFilterFactory.Config();
        config.setLogicalId("  ");
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
    @DisplayName("Null logicalId → filter disabled, pass-through")
    void nullLogicalId_passesThrough() {
        var config = new CertRotationGatewayFilterFactory.Config();
        config.setLogicalId(null);
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

    // ─── Missing certificate header ───────────────────────────────────────────

    @Test
    @DisplayName("Missing certificate header → 401 MISSING_CERTIFICATE")
    void missingCertHeader_returns401() {
        var config = new CertRotationGatewayFilterFactory.Config();
        config.setLogicalId("my-cert");
        GatewayFilter filter = factory.apply(config);

        var exchange = MockServerWebExchange.from(
                MockServerHttpRequest.get("/api/test").build());

        StepVerifier.create(filter.filter(exchange, ex -> Mono.empty()))
                .verifyComplete();

        assertThat(exchange.getResponse().getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    @Test
    @DisplayName("Blank certificate header → 401 MISSING_CERTIFICATE")
    void blankCertHeader_returns401() {
        var config = new CertRotationGatewayFilterFactory.Config();
        config.setLogicalId("my-cert");
        GatewayFilter filter = factory.apply(config);

        var exchange = MockServerWebExchange.from(
                MockServerHttpRequest.get("/api/test")
                        .header("X-Client-Certificate", "   ")
                        .build());

        StepVerifier.create(filter.filter(exchange, ex -> Mono.empty()))
                .verifyComplete();

        assertThat(exchange.getResponse().getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    // ─── Invalid PEM ──────────────────────────────────────────────────────────

    @Test
    @DisplayName("Unparseable PEM → 401 INVALID_CERTIFICATE")
    void invalidPem_returns401() {
        var config = new CertRotationGatewayFilterFactory.Config();
        config.setLogicalId("my-cert");
        GatewayFilter filter = factory.apply(config);

        var exchange = MockServerWebExchange.from(
                MockServerHttpRequest.get("/api/test")
                        .header("X-Client-Certificate", "garbage-data")
                        .build());

        StepVerifier.create(filter.filter(exchange, ex -> Mono.empty()))
                .verifyComplete();

        assertThat(exchange.getResponse().getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    // ─── Active match → pass ──────────────────────────────────────────────────

    @Test
    @DisplayName("Certificate matches active version → pass with X-Cert-Version and X-Cert-Fingerprint headers")
    void activeMatch_passesWithHeaders() {
        VersionedCertificate vc = VersionedCertificate.of(
                "my-cert", 3, validCert, null, "test");
        when(certificateRegistry.matchCertificate(eq("my-cert"), any()))
                .thenReturn(Optional.of(vc));

        var config = new CertRotationGatewayFilterFactory.Config();
        config.setLogicalId("my-cert");
        GatewayFilter filter = factory.apply(config);

        var exchange = MockServerWebExchange.from(
                MockServerHttpRequest.get("/api/test")
                        .header("X-Client-Certificate", TestCertificateHelper.toPem(validCert))
                        .build());

        ServerHttpRequest[] captured = {null};
        StepVerifier.create(filter.filter(exchange, ex -> {
            captured[0] = ex.getRequest();
            return Mono.empty();
        })).verifyComplete();

        assertThat(captured[0]).isNotNull();
        assertThat(captured[0].getHeaders().getFirst("X-Cert-Version")).isEqualTo("3");
        assertThat(captured[0].getHeaders().getFirst("X-Cert-Fingerprint")).isNotBlank();
    }

    // ─── Inactive match → reject ──────────────────────────────────────────────

    @Test
    @DisplayName("Certificate matches inactive version → 401 CERTIFICATE_ROTATION_REQUIRED")
    void inactiveMatch_returns401() {
        when(certificateRegistry.matchCertificate(eq("my-cert"), any()))
                .thenReturn(Optional.empty());
        VersionedCertificate inactiveVc = VersionedCertificate.of(
                "my-cert", 1, validCert, null, "test");
        when(certificateRegistry.getAllVersions("my-cert"))
                .thenReturn(List.of(inactiveVc));

        var config = new CertRotationGatewayFilterFactory.Config();
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

    // ─── Unknown cert → reject ────────────────────────────────────────────────

    @Test
    @DisplayName("Certificate not found in registry → 401 CERTIFICATE_UNKNOWN")
    void unknownCert_returns401() {
        when(certificateRegistry.matchCertificate(eq("my-cert"), any()))
                .thenReturn(Optional.empty());
        when(certificateRegistry.getAllVersions("my-cert"))
                .thenReturn(List.of());

        var config = new CertRotationGatewayFilterFactory.Config();
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

    // ─── Custom header name ───────────────────────────────────────────────────

    @Test
    @DisplayName("Custom certificateHeader name is respected")
    void customCertHeaderName() {
        VersionedCertificate vc = VersionedCertificate.of(
                "my-cert", 1, validCert, null, "test");
        when(certificateRegistry.matchCertificate(eq("my-cert"), any()))
                .thenReturn(Optional.of(vc));

        var config = new CertRotationGatewayFilterFactory.Config();
        config.setLogicalId("my-cert");
        config.setCertificateHeader("X-Custom-Cert");
        GatewayFilter filter = factory.apply(config);

        var exchange = MockServerWebExchange.from(
                MockServerHttpRequest.get("/api/test")
                        .header("X-Custom-Cert", TestCertificateHelper.toPem(validCert))
                        .build());

        ServerHttpRequest[] captured = {null};
        StepVerifier.create(filter.filter(exchange, ex -> {
            captured[0] = ex.getRequest();
            return Mono.empty();
        })).verifyComplete();

        assertThat(captured[0]).isNotNull();
        assertThat(captured[0].getHeaders().getFirst("X-Cert-Version")).isEqualTo("1");
    }
}

