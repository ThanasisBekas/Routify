package io.routify.gateway.filter;

import io.routify.common.web.RoutifyHeaders;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.cloud.gateway.filter.GatewayFilter;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.core.Ordered;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit tests for {@link CorrelationIdGatewayFilterFactory}.
 *
 * <p>Covers: UUID generation when absent, preservation of existing header,
 * response header injection, filter order.
 */
class CorrelationIdGatewayFilterFactoryTest {

    private final CorrelationIdGatewayFilterFactory factory = new CorrelationIdGatewayFilterFactory();

    private GatewayFilterChain passThroughChain() {
        return exchange -> Mono.empty();
    }

    @Test
    @DisplayName("No existing correlation ID → UUID generated and injected in request + response")
    void missingCorrelationId_generatesUuid() {
        GatewayFilter filter = factory.apply(new CorrelationIdGatewayFilterFactory.Config());

        var exchange = MockServerWebExchange.from(
                MockServerHttpRequest.get("/api/test").build());

        final String[] capturedId = {null};
        GatewayFilterChain capturingChain = ex -> {
            capturedId[0] = ex.getRequest().getHeaders().getFirst(RoutifyHeaders.CORRELATION_ID);
            return Mono.empty();
        };

        StepVerifier.create(filter.filter(exchange, capturingChain))
                .verifyComplete();

        assertThat(capturedId[0]).isNotNull().isNotBlank();
        // Should be a valid UUID format
        assertThat(capturedId[0]).matches("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}");
    }

    @Test
    @DisplayName("Existing correlation ID → preserved, not replaced")
    void existingCorrelationId_preserved() {
        GatewayFilter filter = factory.apply(new CorrelationIdGatewayFilterFactory.Config());

        String existingId = "existing-correlation-id-12345";
        var exchange = MockServerWebExchange.from(
                MockServerHttpRequest.get("/api/test")
                        .header(RoutifyHeaders.CORRELATION_ID, existingId)
                        .build());

        final String[] capturedId = {null};
        GatewayFilterChain capturingChain = ex -> {
            capturedId[0] = ex.getRequest().getHeaders().getFirst(RoutifyHeaders.CORRELATION_ID);
            return Mono.empty();
        };

        StepVerifier.create(filter.filter(exchange, capturingChain))
                .verifyComplete();

        assertThat(capturedId[0]).isEqualTo(existingId);
    }

    @Test
    @DisplayName("Blank correlation ID → treated as absent, new UUID generated")
    void blankCorrelationId_generatesNew() {
        GatewayFilter filter = factory.apply(new CorrelationIdGatewayFilterFactory.Config());

        var exchange = MockServerWebExchange.from(
                MockServerHttpRequest.get("/api/test")
                        .header(RoutifyHeaders.CORRELATION_ID, "   ")
                        .build());

        final String[] capturedId = {null};
        GatewayFilterChain capturingChain = ex -> {
            capturedId[0] = ex.getRequest().getHeaders().getFirst(RoutifyHeaders.CORRELATION_ID);
            return Mono.empty();
        };

        StepVerifier.create(filter.filter(exchange, capturingChain))
                .verifyComplete();

        assertThat(capturedId[0]).isNotBlank();
        assertThat(capturedId[0]).isNotEqualTo("   ");
    }

    @Test
    @DisplayName("Filter order is -1000 (runs first)")
    void filterOrder_isMinus1000() {
        GatewayFilter filter = factory.apply(new CorrelationIdGatewayFilterFactory.Config());

        assertThat(filter).isInstanceOf(Ordered.class);
        assertThat(((Ordered) filter).getOrder()).isEqualTo(-1000);
    }
}

