package gr.routify.gateway.filter;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.cloud.gateway.filter.GatewayFilter;
import org.springframework.http.HttpStatus;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit tests for {@link RequestTimeoutGatewayFilterFactory}.
 *
 * <p>Tests the per-route timeout enforcement: upstream responses within the deadline pass,
 * and slow upstreams trigger a 504 Gateway Timeout.
 */
class RequestTimeoutGatewayFilterFactoryTest {

    @Test
    @DisplayName("Fast upstream passes through within timeout")
    void fastUpstream_passesThrough() {
        var factory = new RequestTimeoutGatewayFilterFactory();
        var config = new RequestTimeoutGatewayFilterFactory.Config();
        config.setTimeoutMs(5000);
        GatewayFilter filter = factory.apply(config);

        MockServerHttpRequest request = MockServerHttpRequest.get("/api/test").build();
        MockServerWebExchange exchange = MockServerWebExchange.from(request);

        // Chain completes immediately (fast upstream)
        StepVerifier.create(filter.filter(exchange, ex -> Mono.empty()))
                .verifyComplete();

        // No error status — request passed through
        assertThat(exchange.getResponse().getStatusCode()).isNull();
    }

    @Test
    @DisplayName("Slow upstream triggers 504 Gateway Timeout")
    void slowUpstream_returns504() {
        var factory = new RequestTimeoutGatewayFilterFactory();
        var config = new RequestTimeoutGatewayFilterFactory.Config();
        config.setTimeoutMs(100); // 100ms timeout
        GatewayFilter filter = factory.apply(config);

        MockServerHttpRequest request = MockServerHttpRequest.get("/api/test").build();
        MockServerWebExchange exchange = MockServerWebExchange.from(request);

        // Chain takes 500ms (simulates slow upstream)
        StepVerifier.create(filter.filter(exchange, ex -> Mono.delay(Duration.ofMillis(500)).then()))
                .verifyComplete();

        assertThat(exchange.getResponse().getStatusCode()).isEqualTo(HttpStatus.GATEWAY_TIMEOUT);
    }

    @Test
    @DisplayName("Default timeout is 30 seconds")
    void defaultTimeout_is30Seconds() {
        var config = new RequestTimeoutGatewayFilterFactory.Config();
        assertThat(config.getTimeoutMs()).isEqualTo(30_000L);
    }

    @Test
    @DisplayName("504 response body is ProblemDetail JSON")
    void timeoutResponse_isProblemDetailJson() {
        var factory = new RequestTimeoutGatewayFilterFactory();
        var config = new RequestTimeoutGatewayFilterFactory.Config();
        config.setTimeoutMs(50);
        GatewayFilter filter = factory.apply(config);

        MockServerHttpRequest request = MockServerHttpRequest.get("/api/test").build();
        MockServerWebExchange exchange = MockServerWebExchange.from(request);

        StepVerifier.create(filter.filter(exchange, ex -> Mono.delay(Duration.ofMillis(300)).then()))
                .verifyComplete();

        assertThat(exchange.getResponse().getHeaders().getFirst("Content-Type"))
                .isEqualTo("application/problem+json");
    }
}

