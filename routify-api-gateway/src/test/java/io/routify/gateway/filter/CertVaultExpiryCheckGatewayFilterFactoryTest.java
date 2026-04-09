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
import org.springframework.http.HttpStatus;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import java.security.cert.X509Certificate;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link CertVaultExpiryCheckGatewayFilterFactory}.
 *
 * <p>Covers 5 distinct outcomes:
 * <ul>
 *   <li>No active cert → 503 CERT_NOT_AVAILABLE</li>
 *   <li>All expired → 503 CERT_EXPIRED</li>
 *   <li>Expiring soon + rejectOnExpiringSoon=true → 503 CERT_EXPIRING_SOON</li>
 *   <li>Expiring soon + rejectOnExpiringSoon=false → pass with EXPIRING_SOON status header</li>
 *   <li>Active → pass with ACTIVE status header</li>
 * </ul>
 */
@ExtendWith(MockitoExtension.class)
class CertVaultExpiryCheckGatewayFilterFactoryTest {

    @Mock
    private CertificateRegistry certificateRegistry;

    private CertVaultExpiryCheckGatewayFilterFactory factory;

    @BeforeEach
    void setup() {
        factory = new CertVaultExpiryCheckGatewayFilterFactory(certificateRegistry);
    }

    private MockServerWebExchange simpleExchange() {
        return MockServerWebExchange.from(MockServerHttpRequest.get("/api/test").build());
    }

    // ─── Missing/blank logicalId → pass-through ──────────────────────────────

    @Test
    @DisplayName("Blank logicalId → filter disabled, pass-through")
    void blankLogicalId_passesThrough() {
        var config = new CertVaultExpiryCheckGatewayFilterFactory.Config();
        config.setLogicalId("  ");
        GatewayFilter filter = factory.apply(config);

        var exchange = simpleExchange();
        boolean[] chainCalled = {false};
        StepVerifier.create(filter.filter(exchange, ex -> {
            chainCalled[0] = true;
            return Mono.empty();
        })).verifyComplete();

        assertThat(chainCalled[0]).isTrue();
    }

    // ─── No active certificate → 503 ────────────────────────────────────────

    @Test
    @DisplayName("No active certificate for logicalId → 503 CERT_NOT_AVAILABLE")
    void noActiveCert_returns503() {
        when(certificateRegistry.getActiveCertificates("my-cert"))
                .thenReturn(List.of());

        var config = new CertVaultExpiryCheckGatewayFilterFactory.Config();
        config.setLogicalId("my-cert");
        GatewayFilter filter = factory.apply(config);

        var exchange = simpleExchange();
        StepVerifier.create(filter.filter(exchange, ex -> Mono.empty()))
                .verifyComplete();

        assertThat(exchange.getResponse().getStatusCode()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
    }

    // ─── All expired → 503 ──────────────────────────────────────────────────

    @Test
    @DisplayName("All active versions expired → 503 CERT_EXPIRED")
    void allExpired_returns503() {
        // Create cert that expired 10 days ago
        X509Certificate expiredCert = TestCertificateHelper.generateExpiredCert("expired-cert", 10);
        VersionedCertificate vc = VersionedCertificate.of(
                "my-cert", 1, expiredCert, null, "test");
        when(certificateRegistry.getActiveCertificates("my-cert"))
                .thenReturn(List.of(vc));

        var config = new CertVaultExpiryCheckGatewayFilterFactory.Config();
        config.setLogicalId("my-cert");
        GatewayFilter filter = factory.apply(config);

        var exchange = simpleExchange();
        StepVerifier.create(filter.filter(exchange, ex -> Mono.empty()))
                .verifyComplete();

        assertThat(exchange.getResponse().getStatusCode()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
    }

    // ─── Expiring soon + reject → 503 ──────────────────────────────────────

    @Nested
    @DisplayName("Expiring soon scenarios")
    class ExpiringSoon {

        @Test
        @DisplayName("Expiring soon + rejectOnExpiringSoon=true → 503 CERT_EXPIRING_SOON")
        void expiringSoonReject_returns503() {
            // Cert expires in 10 days, warning threshold is 30 days
            X509Certificate soonCert = TestCertificateHelper.generateExpiringCert("soon-cert", 10);
            VersionedCertificate vc = VersionedCertificate.of(
                    "my-cert", 1, soonCert, null, "test");
            when(certificateRegistry.getActiveCertificates("my-cert"))
                    .thenReturn(List.of(vc));

            var config = new CertVaultExpiryCheckGatewayFilterFactory.Config();
            config.setLogicalId("my-cert");
            config.setWarningDays(30);
            config.setRejectOnExpiringSoon(true);
            GatewayFilter filter = factory.apply(config);

            var exchange = simpleExchange();
            StepVerifier.create(filter.filter(exchange, ex -> Mono.empty()))
                    .verifyComplete();

            assertThat(exchange.getResponse().getStatusCode()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
        }

        @Test
        @DisplayName("Expiring soon + rejectOnExpiringSoon=false → pass with EXPIRING_SOON headers")
        void expiringSoonPass_injectsHeaders() {
            X509Certificate soonCert = TestCertificateHelper.generateExpiringCert("soon-cert", 10);
            VersionedCertificate vc = VersionedCertificate.of(
                    "my-cert", 1, soonCert, null, "test");
            when(certificateRegistry.getActiveCertificates("my-cert"))
                    .thenReturn(List.of(vc));

            var config = new CertVaultExpiryCheckGatewayFilterFactory.Config();
            config.setLogicalId("my-cert");
            config.setWarningDays(30);
            config.setRejectOnExpiringSoon(false);
            GatewayFilter filter = factory.apply(config);

            var exchange = simpleExchange();
            ServerHttpRequest[] captured = {null};
            StepVerifier.create(filter.filter(exchange, ex -> {
                captured[0] = ex.getRequest();
                return Mono.empty();
            })).verifyComplete();

            assertThat(captured[0]).isNotNull();
            var headers = captured[0].getHeaders();
            assertThat(headers.getFirst("X-Cert-Status")).isEqualTo("EXPIRING_SOON");
            assertThat(headers.getFirst("X-Cert-Expiry")).isNotBlank();
            assertThat(headers.getFirst("X-Cert-Days-Remaining")).isNotBlank();
            int daysRemaining = Integer.parseInt(headers.getFirst("X-Cert-Days-Remaining"));
            assertThat(daysRemaining).isBetween(8, 11);
            assertThat(headers.getFirst("X-Cert-Fingerprint")).isNotBlank();
        }
    }

    // ─── Active (healthy) → pass ────────────────────────────────────────────

    @Test
    @DisplayName("Active, non-expiring cert → pass with ACTIVE status header")
    void activeCert_injectsActiveHeaders() {
        X509Certificate healthyCert = TestCertificateHelper.generateExpiringCert("healthy-cert", 365);
        VersionedCertificate vc = VersionedCertificate.of(
                "my-cert", 2, healthyCert, null, "test");
        when(certificateRegistry.getActiveCertificates("my-cert"))
                .thenReturn(List.of(vc));

        var config = new CertVaultExpiryCheckGatewayFilterFactory.Config();
        config.setLogicalId("my-cert");
        config.setWarningDays(30);
        GatewayFilter filter = factory.apply(config);

        var exchange = simpleExchange();
        ServerHttpRequest[] captured = {null};
        StepVerifier.create(filter.filter(exchange, ex -> {
            captured[0] = ex.getRequest();
            return Mono.empty();
        })).verifyComplete();

        assertThat(captured[0]).isNotNull();
        var headers = captured[0].getHeaders();
        assertThat(headers.getFirst("X-Cert-Status")).isEqualTo("ACTIVE");
        assertThat(headers.getFirst("X-Cert-Expiry")).isNotBlank();
        int daysRemaining = Integer.parseInt(headers.getFirst("X-Cert-Days-Remaining"));
        assertThat(daysRemaining).isGreaterThan(300);
        assertThat(headers.getFirst("X-Cert-Fingerprint")).isNotBlank();
    }

    // ─── injectMetadataHeaders=false → no headers ───────────────────────────

    @Test
    @DisplayName("injectMetadataHeaders=false → no X-Cert-* headers, still passes through")
    void noMetadataHeaders_whenDisabled() {
        X509Certificate healthyCert = TestCertificateHelper.generateExpiringCert("healthy-cert", 365);
        VersionedCertificate vc = VersionedCertificate.of(
                "my-cert", 1, healthyCert, null, "test");
        when(certificateRegistry.getActiveCertificates("my-cert"))
                .thenReturn(List.of(vc));

        var config = new CertVaultExpiryCheckGatewayFilterFactory.Config();
        config.setLogicalId("my-cert");
        config.setInjectMetadataHeaders(false);
        GatewayFilter filter = factory.apply(config);

        var exchange = simpleExchange();
        ServerHttpRequest[] captured = {null};
        StepVerifier.create(filter.filter(exchange, ex -> {
            captured[0] = ex.getRequest();
            return Mono.empty();
        })).verifyComplete();

        assertThat(captured[0]).isNotNull();
        // No X-Cert-* headers should be injected when disabled
        assertThat(captured[0].getHeaders().getFirst("X-Cert-Status")).isNull();
        assertThat(captured[0].getHeaders().getFirst("X-Cert-Expiry")).isNull();
    }

    // ─── Multiple active versions — picks latest expiry ─────────────────────

    @Test
    @DisplayName("Multiple active versions → picks the one with latest notAfter")
    void multipleVersions_picksLatest() {
        X509Certificate soonCert = TestCertificateHelper.generateExpiringCert("soon", 10);
        X509Certificate laterCert = TestCertificateHelper.generateExpiringCert("later", 200);

        VersionedCertificate vc1 = VersionedCertificate.of("my-cert", 1, soonCert, null, "test");
        VersionedCertificate vc2 = VersionedCertificate.of("my-cert", 2, laterCert, null, "test");
        when(certificateRegistry.getActiveCertificates("my-cert"))
                .thenReturn(List.of(vc1, vc2));

        var config = new CertVaultExpiryCheckGatewayFilterFactory.Config();
        config.setLogicalId("my-cert");
        config.setWarningDays(30);
        GatewayFilter filter = factory.apply(config);

        var exchange = simpleExchange();
        ServerHttpRequest[] captured = {null};
        StepVerifier.create(filter.filter(exchange, ex -> {
            captured[0] = ex.getRequest();
            return Mono.empty();
        })).verifyComplete();

        assertThat(captured[0]).isNotNull();
        // Should pick the later-expiring cert → ACTIVE (not EXPIRING_SOON)
        assertThat(captured[0].getHeaders().getFirst("X-Cert-Status")).isEqualTo("ACTIVE");
        int daysRemaining = Integer.parseInt(captured[0].getHeaders().getFirst("X-Cert-Days-Remaining"));
        assertThat(daysRemaining).isGreaterThan(100);
    }
}

