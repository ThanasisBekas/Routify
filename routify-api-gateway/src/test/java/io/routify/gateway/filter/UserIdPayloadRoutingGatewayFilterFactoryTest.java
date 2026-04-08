package io.routify.gateway.filter;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.cloud.gateway.filter.GatewayFilter;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.cloud.gateway.support.ServerWebExchangeUtils;
import org.springframework.http.HttpStatus;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import java.net.URI;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit tests for {@link UserIdPayloadRoutingGatewayFilterFactory}.
 *
 * <p>Covers: matching userId → URI rewritten, non-matching → unchanged,
 * missing cached body → 500, wrong cached body type → 500, enabled=false → passthrough.
 */
class UserIdPayloadRoutingGatewayFilterFactoryTest {

    private final UserIdPayloadRoutingGatewayFilterFactory factory =
            new UserIdPayloadRoutingGatewayFilterFactory();

    private GatewayFilterChain passThroughChain() {
        return exchange -> Mono.empty();
    }

    @Test
    @DisplayName("Matching userId in body → URI rewritten to alternativeUri")
    void matchingUserId_rewritesUri() {
        var config = new UserIdPayloadRoutingGatewayFilterFactory.Config();
        config.setEnabled(true);
        config.setUserIdField("userId");
        config.setAllowlistUserIds(List.of("user-123", "user-456"));
        config.setAlternativeUri("http://alt-backend:9090");
        GatewayFilter filter = factory.apply(config);

        var exchange = MockServerWebExchange.from(
                MockServerHttpRequest.post("/api/process").build());

        // Set the cached body attribute (simulates CacheRequestBody filter)
        exchange.getAttributes().put(
                ServerWebExchangeUtils.CACHED_REQUEST_BODY_ATTR,
                Map.of("userId", "user-123", "data", "payload"));

        // Set the original route URI attribute (simulates RouteToRequestUrlFilter)
        URI originalRouteUri = URI.create("http://original:8080/api/process");
        exchange.getAttributes().put(
                ServerWebExchangeUtils.GATEWAY_REQUEST_URL_ATTR, originalRouteUri);

        StepVerifier.create(filter.filter(exchange, passThroughChain()))
                .verifyComplete();

        URI rewrittenUri = exchange.getAttribute(ServerWebExchangeUtils.GATEWAY_REQUEST_URL_ATTR);
        assertThat(rewrittenUri).isNotNull();
        assertThat(rewrittenUri.getHost()).isEqualTo("alt-backend");
        assertThat(rewrittenUri.getPort()).isEqualTo(9090);
    }

    @Test
    @DisplayName("Non-matching userId → URI unchanged")
    void nonMatchingUserId_unchanged() {
        var config = new UserIdPayloadRoutingGatewayFilterFactory.Config();
        config.setEnabled(true);
        config.setUserIdField("userId");
        config.setAllowlistUserIds(List.of("user-123"));
        config.setAlternativeUri("http://alt-backend:9090");
        GatewayFilter filter = factory.apply(config);

        var exchange = MockServerWebExchange.from(
                MockServerHttpRequest.post("/api/process").build());

        exchange.getAttributes().put(
                ServerWebExchangeUtils.CACHED_REQUEST_BODY_ATTR,
                Map.of("userId", "user-999"));

        URI originalUri = URI.create("http://original:8080/api/process");
        exchange.getAttributes().put(
                ServerWebExchangeUtils.GATEWAY_REQUEST_URL_ATTR, originalUri);

        StepVerifier.create(filter.filter(exchange, passThroughChain()))
                .verifyComplete();

        URI resultUri = exchange.getAttribute(ServerWebExchangeUtils.GATEWAY_REQUEST_URL_ATTR);
        assertThat(resultUri).isEqualTo(originalUri);
    }

    @Test
    @DisplayName("No cached body → 500 ROUTING_MISCONFIGURED")
    void noCachedBody_returns500() {
        var config = new UserIdPayloadRoutingGatewayFilterFactory.Config();
        config.setEnabled(true);
        GatewayFilter filter = factory.apply(config);

        var exchange = MockServerWebExchange.from(
                MockServerHttpRequest.post("/api/process").build());

        StepVerifier.create(filter.filter(exchange, passThroughChain()))
                .verifyComplete();

        assertThat(exchange.getResponse().getStatusCode())
                .isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR);
    }

    @Test
    @DisplayName("Cached body wrong type (not Map) → 500 ROUTING_MISCONFIGURED")
    void wrongBodyType_returns500() {
        var config = new UserIdPayloadRoutingGatewayFilterFactory.Config();
        config.setEnabled(true);
        GatewayFilter filter = factory.apply(config);

        var exchange = MockServerWebExchange.from(
                MockServerHttpRequest.post("/api/process").build());

        exchange.getAttributes().put(
                ServerWebExchangeUtils.CACHED_REQUEST_BODY_ATTR, "not-a-map");

        StepVerifier.create(filter.filter(exchange, passThroughChain()))
                .verifyComplete();

        assertThat(exchange.getResponse().getStatusCode())
                .isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR);
    }

    @Test
    @DisplayName("enabled=false → passthrough, no body read")
    void disabled_passesThrough() {
        var config = new UserIdPayloadRoutingGatewayFilterFactory.Config();
        config.setEnabled(false);
        GatewayFilter filter = factory.apply(config);

        var exchange = MockServerWebExchange.from(
                MockServerHttpRequest.post("/api/process").build());

        StepVerifier.create(filter.filter(exchange, passThroughChain()))
                .verifyComplete();

        assertThat(exchange.getResponse().getStatusCode()).isNull();
    }

    @Test
    @DisplayName("Factory order is 10001 (after RouteToRequestUrlFilter)")
    void factoryOrder_is10001() {
        assertThat(factory.getOrder()).isEqualTo(10001);
    }
}

