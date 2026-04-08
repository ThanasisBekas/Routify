package io.routify.gateway.filter;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.cloud.gateway.filter.GatewayFilter;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit tests for {@link CustomMetricGatewayFilterFactory}.
 *
 * <p>Covers: static tag counter, dynamic $header.* tag resolution, missing header
 * → "unknown", default metric name, config defaults.
 */
class CustomMetricGatewayFilterFactoryTest {

    private SimpleMeterRegistry meterRegistry;
    private CustomMetricGatewayFilterFactory factory;

    @BeforeEach
    void setUp() {
        meterRegistry = new SimpleMeterRegistry();
        factory = new CustomMetricGatewayFilterFactory(meterRegistry);
    }

    private GatewayFilterChain passThroughChain() {
        return exchange -> Mono.empty();
    }

    @Test
    @DisplayName("Static tags → counter registered and incremented")
    void staticTags_counterIncremented() {
        var config = new CustomMetricGatewayFilterFactory.Config();
        config.setMetricName("routify.custom.test");
        config.setTags(Map.of("route", "user-api"));
        GatewayFilter filter = factory.apply(config);

        var exchange = MockServerWebExchange.from(
                MockServerHttpRequest.get("/api/users").build());

        StepVerifier.create(filter.filter(exchange, passThroughChain()))
                .verifyComplete();

        Counter counter = meterRegistry.find("routify.custom.test")
                .tag("route", "user-api")
                .counter();
        assertThat(counter).isNotNull();
        assertThat(counter.count()).isEqualTo(1.0);
    }

    @Test
    @DisplayName("Static tags — multiple requests increment the same counter")
    void staticTags_multipleRequests() {
        var config = new CustomMetricGatewayFilterFactory.Config();
        config.setMetricName("routify.custom.hits");
        config.setTags(Map.of("env", "prod"));
        GatewayFilter filter = factory.apply(config);

        for (int i = 0; i < 5; i++) {
            var exchange = MockServerWebExchange.from(
                    MockServerHttpRequest.get("/api/test").build());
            StepVerifier.create(filter.filter(exchange, passThroughChain()))
                    .verifyComplete();
        }

        Counter counter = meterRegistry.find("routify.custom.hits")
                .tag("env", "prod")
                .counter();
        assertThat(counter).isNotNull();
        assertThat(counter.count()).isEqualTo(5.0);
    }

    @Test
    @DisplayName("Dynamic $header.* tag → resolved from request header")
    void dynamicTag_resolvedFromHeader() {
        var config = new CustomMetricGatewayFilterFactory.Config();
        config.setMetricName("routify.custom.by_tenant");
        config.setTags(Map.of("tenant", "$header.X-Tenant-Id"));
        GatewayFilter filter = factory.apply(config);

        var exchange = MockServerWebExchange.from(
                MockServerHttpRequest.get("/api/test")
                        .header("X-Tenant-Id", "acme-corp")
                        .build());

        StepVerifier.create(filter.filter(exchange, passThroughChain()))
                .verifyComplete();

        Counter counter = meterRegistry.find("routify.custom.by_tenant")
                .tag("tenant", "acme-corp")
                .counter();
        assertThat(counter).isNotNull();
        assertThat(counter.count()).isEqualTo(1.0);
    }

    @Test
    @DisplayName("Dynamic $header.* tag — missing header → 'unknown' tag value")
    void dynamicTag_missingHeader_unknown() {
        var config = new CustomMetricGatewayFilterFactory.Config();
        config.setMetricName("routify.custom.by_tenant");
        config.setTags(Map.of("tenant", "$header.X-Tenant-Id"));
        GatewayFilter filter = factory.apply(config);

        var exchange = MockServerWebExchange.from(
                MockServerHttpRequest.get("/api/test").build());

        StepVerifier.create(filter.filter(exchange, passThroughChain()))
                .verifyComplete();

        Counter counter = meterRegistry.find("routify.custom.by_tenant")
                .tag("tenant", "unknown")
                .counter();
        assertThat(counter).isNotNull();
        assertThat(counter.count()).isEqualTo(1.0);
    }

    @Test
    @DisplayName("Default metric name when metricName is blank")
    void defaultMetricName() {
        var config = new CustomMetricGatewayFilterFactory.Config();
        config.setMetricName("");
        GatewayFilter filter = factory.apply(config);

        var exchange = MockServerWebExchange.from(
                MockServerHttpRequest.get("/api/test").build());

        StepVerifier.create(filter.filter(exchange, passThroughChain()))
                .verifyComplete();

        Counter counter = meterRegistry.find("routify.gateway.custom_metric").counter();
        assertThat(counter).isNotNull();
        assertThat(counter.count()).isEqualTo(1.0);
    }

    @Test
    @DisplayName("Null tags → no tags on counter, counter still works")
    void nullTags_counterStillWorks() {
        var config = new CustomMetricGatewayFilterFactory.Config();
        config.setMetricName("routify.custom.no_tags");
        config.setTags(null);
        GatewayFilter filter = factory.apply(config);

        var exchange = MockServerWebExchange.from(
                MockServerHttpRequest.get("/api/test").build());

        StepVerifier.create(filter.filter(exchange, passThroughChain()))
                .verifyComplete();

        Counter counter = meterRegistry.find("routify.custom.no_tags").counter();
        assertThat(counter).isNotNull();
        assertThat(counter.count()).isEqualTo(1.0);
    }
}

