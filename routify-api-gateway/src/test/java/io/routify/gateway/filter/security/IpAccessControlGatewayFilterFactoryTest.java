package io.routify.gateway.filter.security;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.cloud.gateway.filter.GatewayFilter;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.core.Ordered;
import org.springframework.http.HttpStatus;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit tests for {@link IpAccessControlGatewayFilterFactory} — covers denylist,
 * allowlist, X-Forwarded-For parsing, rejection behaviour, and edge cases.
 */
class IpAccessControlGatewayFilterFactoryTest {

    private MeterRegistry meterRegistry;
    private IpAccessControlGatewayFilterFactory factory;

    @BeforeEach
    void setup() {
        meterRegistry = new SimpleMeterRegistry();
        factory = new IpAccessControlGatewayFilterFactory(meterRegistry);
    }

    private GatewayFilterChain passThroughChain() {
        return exchange -> Mono.empty();
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // DENYLIST mode
    // ═══════════════════════════════════════════════════════════════════════════

    @Test
    @DisplayName("DENYLIST: blocks matching IP")
    void denylist_blocksMatchingIp() {
        var config = new IpAccessControlGatewayFilterFactory.Config();
        config.setMode("DENYLIST");
        config.setAddresses("192.168.1.100");
        config.setTrustProxy(false);
        GatewayFilter filter = factory.apply(config);

        MockServerHttpRequest request = MockServerHttpRequest.get("/api/test")
                .remoteAddress(new java.net.InetSocketAddress("192.168.1.100", 12345))
                .build();
        MockServerWebExchange exchange = MockServerWebExchange.from(request);

        StepVerifier.create(filter.filter(exchange, passThroughChain()))
                .verifyComplete();

        assertThat(exchange.getResponse().getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
    }

    @Test
    @DisplayName("DENYLIST: allows non-matching IP")
    void denylist_allowsNonMatchingIp() {
        var config = new IpAccessControlGatewayFilterFactory.Config();
        config.setMode("DENYLIST");
        config.setAddresses("192.168.1.100");
        config.setTrustProxy(false);
        GatewayFilter filter = factory.apply(config);

        MockServerHttpRequest request = MockServerHttpRequest.get("/api/test")
                .remoteAddress(new java.net.InetSocketAddress("10.0.0.1", 12345))
                .build();
        MockServerWebExchange exchange = MockServerWebExchange.from(request);

        StepVerifier.create(filter.filter(exchange, passThroughChain()))
                .verifyComplete();

        assertThat(exchange.getResponse().getStatusCode()).isNotEqualTo(HttpStatus.FORBIDDEN);
    }

    @Test
    @DisplayName("DENYLIST: CIDR range blocks matching IP")
    void denylist_cidrRangeBlocksMatchingIp() {
        var config = new IpAccessControlGatewayFilterFactory.Config();
        config.setMode("DENYLIST");
        config.setAddresses("10.0.0.0/8");
        config.setTrustProxy(false);
        GatewayFilter filter = factory.apply(config);

        MockServerHttpRequest request = MockServerHttpRequest.get("/api/test")
                .remoteAddress(new java.net.InetSocketAddress("10.5.3.2", 12345))
                .build();
        MockServerWebExchange exchange = MockServerWebExchange.from(request);

        StepVerifier.create(filter.filter(exchange, passThroughChain()))
                .verifyComplete();

        assertThat(exchange.getResponse().getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
    }

    @Test
    @DisplayName("DENYLIST: empty addresses list allows all")
    void denylist_emptyAddressesAllowsAll() {
        var config = new IpAccessControlGatewayFilterFactory.Config();
        config.setMode("DENYLIST");
        config.setAddresses("");
        config.setTrustProxy(false);
        GatewayFilter filter = factory.apply(config);

        MockServerHttpRequest request = MockServerHttpRequest.get("/api/test")
                .remoteAddress(new java.net.InetSocketAddress("1.2.3.4", 12345))
                .build();
        MockServerWebExchange exchange = MockServerWebExchange.from(request);

        StepVerifier.create(filter.filter(exchange, passThroughChain()))
                .verifyComplete();

        assertThat(exchange.getResponse().getStatusCode()).isNotEqualTo(HttpStatus.FORBIDDEN);
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // ALLOWLIST mode
    // ═══════════════════════════════════════════════════════════════════════════

    @Test
    @DisplayName("ALLOWLIST: allows matching IP")
    void allowlist_allowsMatchingIp() {
        var config = new IpAccessControlGatewayFilterFactory.Config();
        config.setMode("ALLOWLIST");
        config.setAddresses("10.0.0.0/8");
        config.setTrustProxy(false);
        GatewayFilter filter = factory.apply(config);

        MockServerHttpRequest request = MockServerHttpRequest.get("/api/test")
                .remoteAddress(new java.net.InetSocketAddress("10.1.2.3", 12345))
                .build();
        MockServerWebExchange exchange = MockServerWebExchange.from(request);

        StepVerifier.create(filter.filter(exchange, passThroughChain()))
                .verifyComplete();

        assertThat(exchange.getResponse().getStatusCode()).isNotEqualTo(HttpStatus.FORBIDDEN);
    }

    @Test
    @DisplayName("ALLOWLIST: blocks non-matching IP")
    void allowlist_blocksNonMatchingIp() {
        var config = new IpAccessControlGatewayFilterFactory.Config();
        config.setMode("ALLOWLIST");
        config.setAddresses("10.0.0.0/8");
        config.setTrustProxy(false);
        GatewayFilter filter = factory.apply(config);

        MockServerHttpRequest request = MockServerHttpRequest.get("/api/test")
                .remoteAddress(new java.net.InetSocketAddress("192.168.1.1", 12345))
                .build();
        MockServerWebExchange exchange = MockServerWebExchange.from(request);

        StepVerifier.create(filter.filter(exchange, passThroughChain()))
                .verifyComplete();

        assertThat(exchange.getResponse().getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
    }

    @Test
    @DisplayName("ALLOWLIST: empty addresses list blocks all")
    void allowlist_emptyAddressesBlocksAll() {
        var config = new IpAccessControlGatewayFilterFactory.Config();
        config.setMode("ALLOWLIST");
        config.setAddresses("");
        config.setTrustProxy(false);
        GatewayFilter filter = factory.apply(config);

        MockServerHttpRequest request = MockServerHttpRequest.get("/api/test")
                .remoteAddress(new java.net.InetSocketAddress("1.2.3.4", 12345))
                .build();
        MockServerWebExchange exchange = MockServerWebExchange.from(request);

        StepVerifier.create(filter.filter(exchange, passThroughChain()))
                .verifyComplete();

        assertThat(exchange.getResponse().getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // X-Forwarded-For parsing
    // ═══════════════════════════════════════════════════════════════════════════

    @Test
    @DisplayName("X-Forwarded-For: depth=1 uses rightmost IP")
    void xff_depth1_usesRightmostIp() {
        var config = new IpAccessControlGatewayFilterFactory.Config();
        config.setMode("DENYLIST");
        config.setAddresses("203.0.113.50");
        config.setTrustProxy(true);
        config.setProxyDepth(1);
        GatewayFilter filter = factory.apply(config);

        // The XFF chain: client → proxy1 → proxy2(rightmost)
        MockServerHttpRequest request = MockServerHttpRequest.get("/api/test")
                .header("X-Forwarded-For", "10.0.0.1, 172.16.0.1, 203.0.113.50")
                .remoteAddress(new java.net.InetSocketAddress("127.0.0.1", 12345))
                .build();
        MockServerWebExchange exchange = MockServerWebExchange.from(request);

        StepVerifier.create(filter.filter(exchange, passThroughChain()))
                .verifyComplete();

        assertThat(exchange.getResponse().getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
    }

    @Test
    @DisplayName("X-Forwarded-For: depth=2 uses second-to-last IP")
    void xff_depth2_usesSecondToLastIp() {
        var config = new IpAccessControlGatewayFilterFactory.Config();
        config.setMode("DENYLIST");
        config.setAddresses("172.16.0.1");
        config.setTrustProxy(true);
        config.setProxyDepth(2);
        GatewayFilter filter = factory.apply(config);

        MockServerHttpRequest request = MockServerHttpRequest.get("/api/test")
                .header("X-Forwarded-For", "10.0.0.1, 172.16.0.1, 203.0.113.50")
                .remoteAddress(new java.net.InetSocketAddress("127.0.0.1", 12345))
                .build();
        MockServerWebExchange exchange = MockServerWebExchange.from(request);

        StepVerifier.create(filter.filter(exchange, passThroughChain()))
                .verifyComplete();

        assertThat(exchange.getResponse().getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
    }

    @Test
    @DisplayName("X-Forwarded-For: falls back to remote address when header absent")
    void xff_fallsBackToRemoteAddress() {
        var config = new IpAccessControlGatewayFilterFactory.Config();
        config.setMode("DENYLIST");
        config.setAddresses("192.168.1.1");
        config.setTrustProxy(true);
        GatewayFilter filter = factory.apply(config);

        MockServerHttpRequest request = MockServerHttpRequest.get("/api/test")
                .remoteAddress(new java.net.InetSocketAddress("192.168.1.1", 12345))
                .build();
        MockServerWebExchange exchange = MockServerWebExchange.from(request);

        StepVerifier.create(filter.filter(exchange, passThroughChain()))
                .verifyComplete();

        assertThat(exchange.getResponse().getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
    }

    @Test
    @DisplayName("trustProxy=false ignores X-Forwarded-For")
    void trustProxyFalse_ignoresXff() {
        var config = new IpAccessControlGatewayFilterFactory.Config();
        config.setMode("DENYLIST");
        config.setAddresses("10.0.0.1");
        config.setTrustProxy(false);
        GatewayFilter filter = factory.apply(config);

        // XFF says 10.0.0.1 (blocked), but remote address is 192.168.1.1 (not blocked)
        MockServerHttpRequest request = MockServerHttpRequest.get("/api/test")
                .header("X-Forwarded-For", "10.0.0.1")
                .remoteAddress(new java.net.InetSocketAddress("192.168.1.1", 12345))
                .build();
        MockServerWebExchange exchange = MockServerWebExchange.from(request);

        StepVerifier.create(filter.filter(exchange, passThroughChain()))
                .verifyComplete();

        // Should NOT be blocked because trustProxy=false → uses remote address
        assertThat(exchange.getResponse().getStatusCode()).isNotEqualTo(HttpStatus.FORBIDDEN);
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // Custom rejection settings
    // ═══════════════════════════════════════════════════════════════════════════

    @Test
    @DisplayName("Custom reject status code (401)")
    void customRejectStatus() {
        var config = new IpAccessControlGatewayFilterFactory.Config();
        config.setMode("DENYLIST");
        config.setAddresses("10.0.0.1");
        config.setTrustProxy(false);
        config.setRejectStatus(401);
        GatewayFilter filter = factory.apply(config);

        MockServerHttpRequest request = MockServerHttpRequest.get("/api/test")
                .remoteAddress(new java.net.InetSocketAddress("10.0.0.1", 12345))
                .build();
        MockServerWebExchange exchange = MockServerWebExchange.from(request);

        StepVerifier.create(filter.filter(exchange, passThroughChain()))
                .verifyComplete();

        assertThat(exchange.getResponse().getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    @Test
    @DisplayName("RFC 9457 ProblemDetail content type on rejection")
    void rejectContentType_isProblemJson() {
        var config = new IpAccessControlGatewayFilterFactory.Config();
        config.setMode("DENYLIST");
        config.setAddresses("10.0.0.1");
        config.setTrustProxy(false);
        GatewayFilter filter = factory.apply(config);

        MockServerHttpRequest request = MockServerHttpRequest.get("/api/test")
                .remoteAddress(new java.net.InetSocketAddress("10.0.0.1", 12345))
                .build();
        MockServerWebExchange exchange = MockServerWebExchange.from(request);

        StepVerifier.create(filter.filter(exchange, passThroughChain()))
                .verifyComplete();

        assertThat(exchange.getResponse().getHeaders().getContentType())
                .isNotNull();
        assertThat(exchange.getResponse().getHeaders().getContentType().toString())
                .contains("application/problem+json");
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // Multiple addresses
    // ═══════════════════════════════════════════════════════════════════════════

    @Test
    @DisplayName("Multiple CIDR ranges: matches any")
    void multipleCidrRanges_matchesAny() {
        var config = new IpAccessControlGatewayFilterFactory.Config();
        config.setMode("DENYLIST");
        config.setAddresses("10.0.0.0/8, 172.16.0.0/12, 192.168.0.0/16");
        config.setTrustProxy(false);
        GatewayFilter filter = factory.apply(config);

        // Test with an IP from the third range
        MockServerHttpRequest request = MockServerHttpRequest.get("/api/test")
                .remoteAddress(new java.net.InetSocketAddress("192.168.5.5", 12345))
                .build();
        MockServerWebExchange exchange = MockServerWebExchange.from(request);

        StepVerifier.create(filter.filter(exchange, passThroughChain()))
                .verifyComplete();

        assertThat(exchange.getResponse().getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // Order
    // ═══════════════════════════════════════════════════════════════════════════

    @Test
    @DisplayName("Filter order is -1500 (before auth filters)")
    void filterOrder_isMinus1500() {
        var config = new IpAccessControlGatewayFilterFactory.Config();
        GatewayFilter filter = factory.apply(config);

        assertThat(filter).isInstanceOf(Ordered.class);
        assertThat(((Ordered) filter).getOrder()).isEqualTo(-1500);
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // Metrics
    // ═══════════════════════════════════════════════════════════════════════════

    @Test
    @DisplayName("Metrics: blocked counter incremented on rejection")
    void metrics_blockedCounterIncremented() {
        var config = new IpAccessControlGatewayFilterFactory.Config();
        config.setMode("DENYLIST");
        config.setAddresses("10.0.0.1");
        config.setTrustProxy(false);
        GatewayFilter filter = factory.apply(config);

        MockServerHttpRequest request = MockServerHttpRequest.get("/api/test")
                .remoteAddress(new java.net.InetSocketAddress("10.0.0.1", 12345))
                .build();
        MockServerWebExchange exchange = MockServerWebExchange.from(request);

        StepVerifier.create(filter.filter(exchange, passThroughChain()))
                .verifyComplete();

        var blocked = meterRegistry.find("routify.filter.ip_access_control.blocked").counter();
        assertThat(blocked).isNotNull();
        assertThat(blocked.count()).isEqualTo(1.0);
    }

    @Test
    @DisplayName("Metrics: allowed counter incremented on pass")
    void metrics_allowedCounterIncremented() {
        var config = new IpAccessControlGatewayFilterFactory.Config();
        config.setMode("DENYLIST");
        config.setAddresses("10.0.0.1");
        config.setTrustProxy(false);
        GatewayFilter filter = factory.apply(config);

        MockServerHttpRequest request = MockServerHttpRequest.get("/api/test")
                .remoteAddress(new java.net.InetSocketAddress("192.168.1.1", 12345))
                .build();
        MockServerWebExchange exchange = MockServerWebExchange.from(request);

        StepVerifier.create(filter.filter(exchange, passThroughChain()))
                .verifyComplete();

        var allowed = meterRegistry.find("routify.filter.ip_access_control.allowed").counter();
        assertThat(allowed).isNotNull();
        assertThat(allowed.count()).isEqualTo(1.0);
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // Invalid config handling
    // ═══════════════════════════════════════════════════════════════════════════

    @Test
    @DisplayName("Invalid CIDR entries are skipped gracefully")
    void invalidCidrEntries_skippedGracefully() {
        var config = new IpAccessControlGatewayFilterFactory.Config();
        config.setMode("DENYLIST");
        config.setAddresses("invalid-address, 10.0.0.0/8, also-invalid");
        config.setTrustProxy(false);
        GatewayFilter filter = factory.apply(config);

        // 10.0.0.0/8 is the only valid entry — should still block
        MockServerHttpRequest request = MockServerHttpRequest.get("/api/test")
                .remoteAddress(new java.net.InetSocketAddress("10.5.5.5", 12345))
                .build();
        MockServerWebExchange exchange = MockServerWebExchange.from(request);

        StepVerifier.create(filter.filter(exchange, passThroughChain()))
                .verifyComplete();

        assertThat(exchange.getResponse().getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
    }
}

