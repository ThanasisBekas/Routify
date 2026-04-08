package io.routify.gateway.filter;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.cloud.gateway.filter.GatewayFilter;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.http.HttpStatus;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import java.nio.charset.StandardCharsets;
import java.util.Base64;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit tests for {@link DownstreamBasicAuthGatewayFilterFactory}.
 *
 * <p>Covers: valid credentials → Basic Authorization header injected,
 * blank username → 500, null password → 500, order is 1.
 */
class DownstreamBasicAuthGatewayFilterFactoryTest {

    private final DownstreamBasicAuthGatewayFilterFactory factory =
            new DownstreamBasicAuthGatewayFilterFactory();

    private GatewayFilterChain passThroughChain() {
        return exchange -> Mono.empty();
    }

    @Test
    @DisplayName("Valid credentials → Authorization: Basic header injected downstream")
    void validCredentials_injectsBasicHeader() {
        var config = new DownstreamBasicAuthGatewayFilterFactory.Config();
        config.setUsername("svc-account");
        config.setPassword("s3cretP@ss");
        GatewayFilter filter = factory.apply(config);

        var exchange = MockServerWebExchange.from(
                MockServerHttpRequest.get("/api/downstream").build());

        final String[] capturedAuth = {null};
        GatewayFilterChain capturingChain = ex -> {
            capturedAuth[0] = ex.getRequest().getHeaders().getFirst("Authorization");
            return Mono.empty();
        };

        StepVerifier.create(filter.filter(exchange, capturingChain))
                .verifyComplete();

        assertThat(capturedAuth[0]).isNotNull().startsWith("Basic ");

        // Verify Base64 decodes to username:password
        String decoded = new String(
                Base64.getDecoder().decode(capturedAuth[0].substring(6)),
                StandardCharsets.UTF_8);
        assertThat(decoded).isEqualTo("svc-account:s3cretP@ss");
    }

    @Test
    @DisplayName("Empty username → 500 DOWNSTREAM_AUTH_MISCONFIGURED")
    void emptyUsername_returns500() {
        var config = new DownstreamBasicAuthGatewayFilterFactory.Config();
        config.setUsername("");
        config.setPassword("pass");
        GatewayFilter filter = factory.apply(config);

        var exchange = MockServerWebExchange.from(
                MockServerHttpRequest.get("/api/downstream").build());

        StepVerifier.create(filter.filter(exchange, passThroughChain()))
                .verifyComplete();

        assertThat(exchange.getResponse().getStatusCode())
                .isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR);
    }

    @Test
    @DisplayName("Null password → 500 DOWNSTREAM_AUTH_MISCONFIGURED")
    void nullPassword_returns500() {
        var config = new DownstreamBasicAuthGatewayFilterFactory.Config();
        config.setUsername("user");
        config.setPassword(null);
        GatewayFilter filter = factory.apply(config);

        var exchange = MockServerWebExchange.from(
                MockServerHttpRequest.get("/api/downstream").build());

        StepVerifier.create(filter.filter(exchange, passThroughChain()))
                .verifyComplete();

        assertThat(exchange.getResponse().getStatusCode())
                .isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR);
    }

    @Test
    @DisplayName("Empty password → 500 DOWNSTREAM_AUTH_MISCONFIGURED")
    void emptyPassword_returns500() {
        var config = new DownstreamBasicAuthGatewayFilterFactory.Config();
        config.setUsername("user");
        config.setPassword("");
        GatewayFilter filter = factory.apply(config);

        var exchange = MockServerWebExchange.from(
                MockServerHttpRequest.get("/api/downstream").build());

        StepVerifier.create(filter.filter(exchange, passThroughChain()))
                .verifyComplete();

        assertThat(exchange.getResponse().getStatusCode())
                .isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR);
    }

    @Test
    @DisplayName("Factory order is 1")
    void factoryOrder_is1() {
        assertThat(factory.getOrder()).isEqualTo(1);
    }
}

