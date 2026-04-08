package io.routify.gateway.filter;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.cloud.gateway.filter.GatewayFilter;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.cloud.gateway.support.ServerWebExchangeUtils;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import java.net.URI;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit tests for {@link ConditionalRouteGatewayFilterFactory}.
 *
 * <p>Covers: header match → URI rewritten, query param match → URI rewritten,
 * no match → unchanged, blank alternativeUri → disabled, invalid regex → disabled,
 * pattern matching with specific regex.
 */
class ConditionalRouteGatewayFilterFactoryTest {

    private final ConditionalRouteGatewayFilterFactory factory = new ConditionalRouteGatewayFilterFactory();

    private GatewayFilterChain passThroughChain() {
        return exchange -> Mono.empty();
    }

    @Test
    @DisplayName("Header match → GATEWAY_REQUEST_URL_ATTR rewritten to alternativeUri")
    void headerMatch_rewritesUri() {
        var config = new ConditionalRouteGatewayFilterFactory.Config();
        config.setConditionHeader("X-Feature-Flag");
        config.setConditionPattern("beta");
        config.setAlternativeUri("http://beta-service:9090");
        GatewayFilter filter = factory.apply(config);

        var exchange = MockServerWebExchange.from(
                MockServerHttpRequest.get("http://original:8080/api/test")
                        .header("X-Feature-Flag", "beta")
                        .build());

        StepVerifier.create(filter.filter(exchange, passThroughChain()))
                .verifyComplete();

        URI rewrittenUri = exchange.getAttribute(ServerWebExchangeUtils.GATEWAY_REQUEST_URL_ATTR);
        assertThat(rewrittenUri).isNotNull();
        assertThat(rewrittenUri.getHost()).isEqualTo("beta-service");
        assertThat(rewrittenUri.getPort()).isEqualTo(9090);
    }

    @Test
    @DisplayName("Query param match → GATEWAY_REQUEST_URL_ATTR rewritten")
    void queryParamMatch_rewritesUri() {
        var config = new ConditionalRouteGatewayFilterFactory.Config();
        config.setConditionParam("variant");
        config.setConditionPattern("canary");
        config.setAlternativeUri("http://canary-backend:8081");
        GatewayFilter filter = factory.apply(config);

        var exchange = MockServerWebExchange.from(
                MockServerHttpRequest.get("http://original:8080/api/test?variant=canary").build());

        StepVerifier.create(filter.filter(exchange, passThroughChain()))
                .verifyComplete();

        URI rewrittenUri = exchange.getAttribute(ServerWebExchangeUtils.GATEWAY_REQUEST_URL_ATTR);
        assertThat(rewrittenUri).isNotNull();
        assertThat(rewrittenUri.getHost()).isEqualTo("canary-backend");
    }

    @Test
    @DisplayName("No match — neither header nor param matches → unchanged")
    void noMatch_unchanged() {
        var config = new ConditionalRouteGatewayFilterFactory.Config();
        config.setConditionHeader("X-Feature-Flag");
        config.setConditionPattern("beta");
        config.setAlternativeUri("http://beta-service:9090");
        GatewayFilter filter = factory.apply(config);

        var exchange = MockServerWebExchange.from(
                MockServerHttpRequest.get("http://original:8080/api/test")
                        .header("X-Feature-Flag", "stable")
                        .build());

        StepVerifier.create(filter.filter(exchange, passThroughChain()))
                .verifyComplete();

        URI rewrittenUri = exchange.getAttribute(ServerWebExchangeUtils.GATEWAY_REQUEST_URL_ATTR);
        assertThat(rewrittenUri).isNull();
    }

    @Test
    @DisplayName("Blank alternativeUri → filter disabled, passthrough")
    void blankAlternativeUri_disabled() {
        var config = new ConditionalRouteGatewayFilterFactory.Config();
        config.setConditionHeader("X-Test");
        config.setAlternativeUri("");
        GatewayFilter filter = factory.apply(config);

        var exchange = MockServerWebExchange.from(
                MockServerHttpRequest.get("/api/test")
                        .header("X-Test", "value")
                        .build());

        StepVerifier.create(filter.filter(exchange, passThroughChain()))
                .verifyComplete();

        assertThat(exchange.getResponse().getStatusCode()).isNull();
    }

    @Test
    @DisplayName("Invalid regex pattern → filter disabled, passthrough")
    void invalidRegex_disabled() {
        var config = new ConditionalRouteGatewayFilterFactory.Config();
        config.setConditionHeader("X-Test");
        config.setConditionPattern("[invalid(");
        config.setAlternativeUri("http://alternative:8081");
        GatewayFilter filter = factory.apply(config);

        var exchange = MockServerWebExchange.from(
                MockServerHttpRequest.get("/api/test")
                        .header("X-Test", "value")
                        .build());

        StepVerifier.create(filter.filter(exchange, passThroughChain()))
                .verifyComplete();

        assertThat(exchange.getResponse().getStatusCode()).isNull();
    }

    @Test
    @DisplayName("Header match takes precedence over query param match")
    void headerMatchPrecedence() {
        var config = new ConditionalRouteGatewayFilterFactory.Config();
        config.setConditionHeader("X-Version");
        config.setConditionParam("version");
        config.setConditionPattern("v2");
        config.setAlternativeUri("http://v2-service:9090");
        GatewayFilter filter = factory.apply(config);

        var exchange = MockServerWebExchange.from(
                MockServerHttpRequest.get("http://original:8080/api/test?version=v2")
                        .header("X-Version", "v2")
                        .build());

        StepVerifier.create(filter.filter(exchange, passThroughChain()))
                .verifyComplete();

        URI rewrittenUri = exchange.getAttribute(ServerWebExchangeUtils.GATEWAY_REQUEST_URL_ATTR);
        assertThat(rewrittenUri).isNotNull();
        assertThat(rewrittenUri.getHost()).isEqualTo("v2-service");
    }

    @Test
    @DisplayName("Default conditionPattern .* matches any non-null value")
    void defaultPattern_matchesAnyValue() {
        var config = new ConditionalRouteGatewayFilterFactory.Config();
        config.setConditionHeader("X-Flag");
        config.setAlternativeUri("http://alt:9090");
        // conditionPattern defaults to ".*"
        GatewayFilter filter = factory.apply(config);

        var exchange = MockServerWebExchange.from(
                MockServerHttpRequest.get("http://original:8080/test")
                        .header("X-Flag", "anything-at-all")
                        .build());

        StepVerifier.create(filter.filter(exchange, passThroughChain()))
                .verifyComplete();

        URI rewrittenUri = exchange.getAttribute(ServerWebExchangeUtils.GATEWAY_REQUEST_URL_ATTR);
        assertThat(rewrittenUri).isNotNull();
        assertThat(rewrittenUri.getHost()).isEqualTo("alt");
    }
}

