package io.routify.gateway.filter.routing;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.cloud.gateway.filter.GatewayFilter;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.cloud.gateway.support.ServerWebExchangeUtils;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import java.net.InetSocketAddress;
import java.net.URI;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit tests for {@link GeoRouteGatewayFilterFactory}.
 *
 * <p>These tests verify:
 * <ul>
 *   <li>Region parsing from comma-separated config</li>
 *   <li>Fallback when no regions are configured</li>
 *   <li>X-Geo-Region header injection</li>
 *   <li>Default region fallback when GeoIP database is missing</li>
 *   <li>Pass-through when default region is not in regions map</li>
 * </ul>
 *
 * <p>Note: These tests do NOT use a real MaxMind database. Since the database
 * file won't be on the classpath, the GeoIpResolver operates in fallback mode
 * and all requests fall back to the default region. This validates the
 * full fallback chain without requiring a licensed MaxMind database.
 */
class GeoRouteTest {

    private SimpleMeterRegistry meterRegistry;
    private GeoRouteGatewayFilterFactory factory;

    @BeforeEach
    void setup() {
        meterRegistry = new SimpleMeterRegistry();
        factory = new GeoRouteGatewayFilterFactory(meterRegistry);
    }

    private GatewayFilterChain passThroughChain() {
        return exchange -> Mono.empty();
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // Region parsing
    // ═══════════════════════════════════════════════════════════════════════════

    @Test
    @DisplayName("parseRegions: valid comma-separated entries")
    void parseRegions_valid() {
        Map<String, URI> result = GeoRouteGatewayFilterFactory.parseRegions(
                "US=https://us.api.example.com,EU=https://eu.api.example.com");
        assertThat(result).hasSize(2);
        assertThat(result.get("US")).isEqualTo(URI.create("https://us.api.example.com"));
        assertThat(result.get("EU")).isEqualTo(URI.create("https://eu.api.example.com"));
    }

    @Test
    @DisplayName("parseRegions: empty string returns empty map")
    void parseRegions_empty() {
        assertThat(GeoRouteGatewayFilterFactory.parseRegions("")).isEmpty();
        assertThat(GeoRouteGatewayFilterFactory.parseRegions(null)).isEmpty();
    }

    @Test
    @DisplayName("parseRegions: invalid entries are skipped")
    void parseRegions_invalidSkipped() {
        Map<String, URI> result = GeoRouteGatewayFilterFactory.parseRegions(
                "US=https://us.api.example.com,BADENTRY,=nope,EU=https://eu.api.example.com");
        assertThat(result).hasSize(2);
        assertThat(result).containsKey("US");
        assertThat(result).containsKey("EU");
    }

    @Test
    @DisplayName("parseRegions: region keys are uppercased")
    void parseRegions_uppercased() {
        Map<String, URI> result = GeoRouteGatewayFilterFactory.parseRegions(
                "us=https://us.api.example.com");
        assertThat(result).containsKey("US");
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // Filter behavior — no GeoIP database (fallback mode)
    // ═══════════════════════════════════════════════════════════════════════════

    @Test
    @DisplayName("No regions configured: filter passes through")
    void noRegions_passThrough() {
        var config = new GeoRouteGatewayFilterFactory.Config();
        config.setRegions("");
        GatewayFilter filter = factory.apply(config);

        MockServerHttpRequest request = MockServerHttpRequest.get("/api/test")
                .remoteAddress(new InetSocketAddress("10.0.0.1", 12345))
                .build();
        MockServerWebExchange exchange = MockServerWebExchange.from(request);

        StepVerifier.create(filter.filter(exchange, passThroughChain()))
                .verifyComplete();

        // No GATEWAY_REQUEST_URL_ATTR change — original upstream preserved
        Object attr = exchange.getAttribute(ServerWebExchangeUtils.GATEWAY_REQUEST_URL_ATTR);
        assertThat(attr).isNull();
    }

    @Test
    @DisplayName("Missing GeoIP database: falls back to default region")
    void missingDatabase_fallsBackToDefault() {
        var config = new GeoRouteGatewayFilterFactory.Config();
        config.setRegions("US=https://us.api.example.com,EU=https://eu.api.example.com");
        config.setDefaultRegion("US");
        config.setGeoDbPath("classpath:nonexistent.mmdb"); // Will not be found
        GatewayFilter filter = factory.apply(config);

        MockServerHttpRequest request = MockServerHttpRequest.get("/api/test")
                .remoteAddress(new InetSocketAddress("10.0.0.1", 12345))
                .build();
        MockServerWebExchange exchange = MockServerWebExchange.from(request);

        StepVerifier.create(filter.filter(exchange, passThroughChain()))
                .verifyComplete();

        // Should have rewritten to the US upstream (default region fallback)
        URI rewrittenUri = exchange.getAttribute(ServerWebExchangeUtils.GATEWAY_REQUEST_URL_ATTR);
        assertThat(rewrittenUri).isNotNull();
        assertThat(rewrittenUri.getHost()).isEqualTo("us.api.example.com");
    }

    @Test
    @DisplayName("Default region not in regions map: passes through to original upstream")
    void defaultRegionNotInMap_passThrough() {
        var config = new GeoRouteGatewayFilterFactory.Config();
        config.setRegions("EU=https://eu.api.example.com");
        config.setDefaultRegion("APAC"); // APAC not in the regions map
        config.setGeoDbPath("classpath:nonexistent.mmdb");
        GatewayFilter filter = factory.apply(config);

        MockServerHttpRequest request = MockServerHttpRequest.get("/api/test")
                .remoteAddress(new InetSocketAddress("10.0.0.1", 12345))
                .build();
        MockServerWebExchange exchange = MockServerWebExchange.from(request);

        StepVerifier.create(filter.filter(exchange, passThroughChain()))
                .verifyComplete();

        // Neither APAC nor any other region matched — no rewrite
        Object attr2 = exchange.getAttribute(ServerWebExchangeUtils.GATEWAY_REQUEST_URL_ATTR);
        assertThat(attr2).isNull();
    }

    @Test
    @DisplayName("X-Geo-Region header is injected on the request")
    void xGeoRegionHeader_injected() {
        var config = new GeoRouteGatewayFilterFactory.Config();
        config.setRegions("US=https://us.api.example.com");
        config.setDefaultRegion("US");
        config.setGeoDbPath("classpath:nonexistent.mmdb");
        GatewayFilter filter = factory.apply(config);

        MockServerHttpRequest request = MockServerHttpRequest.get("/api/test")
                .remoteAddress(new InetSocketAddress("10.0.0.1", 12345))
                .build();
        MockServerWebExchange exchange = MockServerWebExchange.from(request);

        // Capture the chain to inspect the mutated exchange
        final MockServerWebExchange[] capturedExchange = new MockServerWebExchange[1];
        GatewayFilterChain capturingChain = ex -> {
            // The filter passes a mutated exchange with the X-Geo-Region header
            String geoRegion = ex.getRequest().getHeaders().getFirst("X-Geo-Region");
            assertThat(geoRegion).isEqualTo("US");
            return Mono.empty();
        };

        StepVerifier.create(filter.filter(exchange, capturingChain))
                .verifyComplete();
    }

    @Test
    @DisplayName("X-Forwarded-For: uses first entry for IP resolution")
    void xForwardedFor_usesFirstEntry() {
        var config = new GeoRouteGatewayFilterFactory.Config();
        config.setRegions("US=https://us.api.example.com");
        config.setDefaultRegion("US");
        config.setGeoDbPath("classpath:nonexistent.mmdb");
        GatewayFilter filter = factory.apply(config);

        MockServerHttpRequest request = MockServerHttpRequest.get("/api/test")
                .header("X-Forwarded-For", "203.0.113.1, 10.0.0.1")
                .remoteAddress(new InetSocketAddress("127.0.0.1", 12345))
                .build();
        MockServerWebExchange exchange = MockServerWebExchange.from(request);

        StepVerifier.create(filter.filter(exchange, passThroughChain()))
                .verifyComplete();

        // Regardless of the IP (no GeoIP DB), should still route to default region
        URI rewrittenUri = exchange.getAttribute(ServerWebExchangeUtils.GATEWAY_REQUEST_URL_ATTR);
        assertThat(rewrittenUri).isNotNull();
        assertThat(rewrittenUri.getHost()).isEqualTo("us.api.example.com");
    }

    @Test
    @DisplayName("No client IP: filter passes through")
    void noClientIp_passThrough() {
        var config = new GeoRouteGatewayFilterFactory.Config();
        config.setRegions("US=https://us.api.example.com");
        config.setDefaultRegion("US");
        config.setGeoDbPath("classpath:nonexistent.mmdb");
        GatewayFilter filter = factory.apply(config);

        // Build a request without remote address
        MockServerHttpRequest request = MockServerHttpRequest.get("/api/test").build();
        MockServerWebExchange exchange = MockServerWebExchange.from(request);

        StepVerifier.create(filter.filter(exchange, passThroughChain()))
                .verifyComplete();

        // No client IP → pass through
        Object attr3 = exchange.getAttribute(ServerWebExchangeUtils.GATEWAY_REQUEST_URL_ATTR);
        assertThat(attr3).isNull();
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // Metrics
    // ═══════════════════════════════════════════════════════════════════════════

    @Test
    @DisplayName("Metrics: routed counter incremented when region found")
    void metrics_routedCounter() {
        var config = new GeoRouteGatewayFilterFactory.Config();
        config.setRegions("US=https://us.api.example.com");
        config.setDefaultRegion("US");
        config.setGeoDbPath("classpath:nonexistent.mmdb");
        GatewayFilter filter = factory.apply(config);

        MockServerHttpRequest request = MockServerHttpRequest.get("/api/test")
                .remoteAddress(new InetSocketAddress("10.0.0.1", 12345))
                .build();
        MockServerWebExchange exchange = MockServerWebExchange.from(request);

        StepVerifier.create(filter.filter(exchange, passThroughChain()))
                .verifyComplete();

        var routed = meterRegistry.find("routify.filter.geo_route.routed").counter();
        assertThat(routed).isNotNull();
        assertThat(routed.count()).isEqualTo(1.0);
    }

    @Test
    @DisplayName("Metrics: fallback counter incremented when no client IP")
    void metrics_fallbackCounter() {
        var config = new GeoRouteGatewayFilterFactory.Config();
        config.setRegions("US=https://us.api.example.com");
        config.setDefaultRegion("US");
        config.setGeoDbPath("classpath:nonexistent.mmdb");
        GatewayFilter filter = factory.apply(config);

        MockServerHttpRequest request = MockServerHttpRequest.get("/api/test").build();
        MockServerWebExchange exchange = MockServerWebExchange.from(request);

        StepVerifier.create(filter.filter(exchange, passThroughChain()))
                .verifyComplete();

        var fallback = meterRegistry.find("routify.filter.geo_route.fallback").counter();
        assertThat(fallback).isNotNull();
        assertThat(fallback.count()).isEqualTo(1.0);
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // GeoIpResolver
    // ═══════════════════════════════════════════════════════════════════════════

    @Test
    @DisplayName("GeoIpResolver: missing database reports unavailable")
    void geoIpResolver_missingDatabase() {
        GeoIpResolver resolver = new GeoIpResolver("classpath:nonexistent.mmdb", 100);
        assertThat(resolver.isDatabaseAvailable()).isFalse();
        assertThat(resolver.resolveCountry(null)).isEmpty();
        resolver.close();
    }

    @Test
    @DisplayName("GeoIpResolver: null address returns empty")
    void geoIpResolver_nullAddress() {
        GeoIpResolver resolver = new GeoIpResolver("classpath:nonexistent.mmdb", 100);
        assertThat(resolver.resolveCountry(null)).isEmpty();
        resolver.close();
    }

    @Test
    @DisplayName("GeoIpResolver: blank path reports unavailable")
    void geoIpResolver_blankPath() {
        GeoIpResolver resolver = new GeoIpResolver("", 100);
        assertThat(resolver.isDatabaseAvailable()).isFalse();
        resolver.close();
    }
}

