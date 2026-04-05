package gr.routify.gateway.filter;

import gr.routify.common.web.RoutifyHeaders;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.cloud.gateway.filter.GatewayFilter;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit tests for {@link CorrelationIdGatewayFilterFactory},
 * {@link RequestHeaderModifyGatewayFilterFactory}, and
 * {@link ResponseHeaderModifyGatewayFilterFactory}.
 *
 * <p>These filter factories have no external dependencies (no Redis, no Kafka)
 * and can be tested purely with {@link MockServerWebExchange}.
 */
class HeaderFilterFactoriesTest {

    private GatewayFilterChain passThroughChain() {
        return exchange -> Mono.empty();
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // CorrelationIdGatewayFilterFactory
    // ═══════════════════════════════════════════════════════════════════════════

    @Test
    @DisplayName("CorrelationId: generates UUID when no header present")
    void correlationId_generatesUuidWhenAbsent() {
        var factory = new CorrelationIdGatewayFilterFactory();
        GatewayFilter filter = factory.apply(new CorrelationIdGatewayFilterFactory.Config());

        MockServerHttpRequest request = MockServerHttpRequest.get("/api/test").build();
        MockServerWebExchange exchange = MockServerWebExchange.from(request);

        final ServerHttpRequest[] captured = {null};
        GatewayFilterChain capturingChain = ex -> {
            captured[0] = ex.getRequest();
            return Mono.empty();
        };

        StepVerifier.create(filter.filter(exchange, capturingChain))
                .verifyComplete();

        assertThat(captured[0]).isNotNull();
        String correlationId = captured[0].getHeaders().getFirst(RoutifyHeaders.CORRELATION_ID);
        assertThat(correlationId).isNotNull().isNotBlank();
        // Should be a valid UUID format
        assertThat(correlationId).matches("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}");
    }

    @Test
    @DisplayName("CorrelationId: preserves existing header")
    void correlationId_preservesExistingHeader() {
        var factory = new CorrelationIdGatewayFilterFactory();
        GatewayFilter filter = factory.apply(new CorrelationIdGatewayFilterFactory.Config());

        String existingId = "my-custom-correlation-id";
        MockServerHttpRequest request = MockServerHttpRequest.get("/api/test")
                .header(RoutifyHeaders.CORRELATION_ID, existingId)
                .build();
        MockServerWebExchange exchange = MockServerWebExchange.from(request);

        final ServerHttpRequest[] captured = {null};
        GatewayFilterChain capturingChain = ex -> {
            captured[0] = ex.getRequest();
            return Mono.empty();
        };

        StepVerifier.create(filter.filter(exchange, capturingChain))
                .verifyComplete();

        assertThat(captured[0]).isNotNull();
        assertThat(captured[0].getHeaders().getFirst(RoutifyHeaders.CORRELATION_ID))
                .isEqualTo(existingId);
    }

    @Test
    @DisplayName("CorrelationId: sets response header")
    void correlationId_setsResponseHeader() {
        var factory = new CorrelationIdGatewayFilterFactory();
        GatewayFilter filter = factory.apply(new CorrelationIdGatewayFilterFactory.Config());

        MockServerHttpRequest request = MockServerHttpRequest.get("/api/test").build();
        MockServerWebExchange exchange = MockServerWebExchange.from(request);

        final ServerHttpRequest[] captured = {null};
        GatewayFilterChain capturingChain = ex -> {
            captured[0] = ex.getRequest();
            // Trigger beforeCommit callbacks by setting response complete
            return ex.getResponse().setComplete();
        };

        StepVerifier.create(filter.filter(exchange, capturingChain))
                .verifyComplete();

        String responseCorrelationId = exchange.getResponse().getHeaders()
                .getFirst(RoutifyHeaders.CORRELATION_ID);
        assertThat(responseCorrelationId).isNotNull().isNotBlank();

        // Request and response correlation IDs should match
        String requestCorrelationId = captured[0].getHeaders().getFirst(RoutifyHeaders.CORRELATION_ID);
        assertThat(responseCorrelationId).isEqualTo(requestCorrelationId);
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // RequestHeaderModifyGatewayFilterFactory
    // ═══════════════════════════════════════════════════════════════════════════

    @Test
    @DisplayName("RequestHeaderModify: sets (overwrites) headers")
    void requestHeaderModify_setsHeaders() {
        var factory = new RequestHeaderModifyGatewayFilterFactory();
        var config = new RequestHeaderModifyGatewayFilterFactory.Config();
        config.setSet(Map.of("X-Custom-Header", "custom-value", "X-Another", "another-value"));
        GatewayFilter filter = factory.apply(config);

        MockServerHttpRequest request = MockServerHttpRequest.get("/api/test").build();
        MockServerWebExchange exchange = MockServerWebExchange.from(request);

        final ServerHttpRequest[] captured = {null};
        GatewayFilterChain capturingChain = ex -> {
            captured[0] = ex.getRequest();
            return Mono.empty();
        };

        StepVerifier.create(filter.filter(exchange, capturingChain))
                .verifyComplete();

        assertThat(captured[0].getHeaders().getFirst("X-Custom-Header")).isEqualTo("custom-value");
        assertThat(captured[0].getHeaders().getFirst("X-Another")).isEqualTo("another-value");
    }

    @Test
    @DisplayName("RequestHeaderModify: removes headers")
    void requestHeaderModify_removesHeaders() {
        var factory = new RequestHeaderModifyGatewayFilterFactory();
        var config = new RequestHeaderModifyGatewayFilterFactory.Config();
        config.setRemove(Map.of("X-Remove-Me", "ignored"));
        GatewayFilter filter = factory.apply(config);

        MockServerHttpRequest request = MockServerHttpRequest.get("/api/test")
                .header("X-Remove-Me", "some-value")
                .build();
        MockServerWebExchange exchange = MockServerWebExchange.from(request);

        final ServerHttpRequest[] captured = {null};
        GatewayFilterChain capturingChain = ex -> {
            captured[0] = ex.getRequest();
            return Mono.empty();
        };

        StepVerifier.create(filter.filter(exchange, capturingChain))
                .verifyComplete();

        assertThat(captured[0].getHeaders().getFirst("X-Remove-Me")).isNull();
    }

    @Test
    @DisplayName("RequestHeaderModify: adds headers (appends without removing)")
    void requestHeaderModify_addsHeaders() {
        var factory = new RequestHeaderModifyGatewayFilterFactory();
        var config = new RequestHeaderModifyGatewayFilterFactory.Config();
        config.setAdd(Map.of("X-Added", "added-value"));
        GatewayFilter filter = factory.apply(config);

        MockServerHttpRequest request = MockServerHttpRequest.get("/api/test")
                .header("X-Added", "existing-value")
                .build();
        MockServerWebExchange exchange = MockServerWebExchange.from(request);

        final ServerHttpRequest[] captured = {null};
        GatewayFilterChain capturingChain = ex -> {
            captured[0] = ex.getRequest();
            return Mono.empty();
        };

        StepVerifier.create(filter.filter(exchange, capturingChain))
                .verifyComplete();

        // Both values should be present (append)
        assertThat(captured[0].getHeaders().get("X-Added")).contains("existing-value", "added-value");
    }

    @Test
    @DisplayName("RequestHeaderModify: null config maps are safe (no-op)")
    void requestHeaderModify_nullConfigIsNoOp() {
        var factory = new RequestHeaderModifyGatewayFilterFactory();
        var config = new RequestHeaderModifyGatewayFilterFactory.Config();
        // All maps are null by default
        GatewayFilter filter = factory.apply(config);

        MockServerHttpRequest request = MockServerHttpRequest.get("/api/test")
                .header("X-Keep", "value")
                .build();
        MockServerWebExchange exchange = MockServerWebExchange.from(request);

        final ServerHttpRequest[] captured = {null};
        GatewayFilterChain capturingChain = ex -> {
            captured[0] = ex.getRequest();
            return Mono.empty();
        };

        StepVerifier.create(filter.filter(exchange, capturingChain))
                .verifyComplete();

        assertThat(captured[0].getHeaders().getFirst("X-Keep")).isEqualTo("value");
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // ResponseHeaderModifyGatewayFilterFactory
    // ═══════════════════════════════════════════════════════════════════════════

    @Test
    @DisplayName("ResponseHeaderModify: sets response headers")
    void responseHeaderModify_setsHeaders() {
        var factory = new ResponseHeaderModifyGatewayFilterFactory();
        var config = new ResponseHeaderModifyGatewayFilterFactory.Config();
        config.setSet(Map.of("X-Response-Custom", "resp-value"));
        GatewayFilter filter = factory.apply(config);

        MockServerHttpRequest request = MockServerHttpRequest.get("/api/test").build();
        MockServerWebExchange exchange = MockServerWebExchange.from(request);

        // The chain must complete for the .then() to run
        StepVerifier.create(filter.filter(exchange, passThroughChain()))
                .verifyComplete();

        assertThat(exchange.getResponse().getHeaders().getFirst("X-Response-Custom"))
                .isEqualTo("resp-value");
    }

    @Test
    @DisplayName("ResponseHeaderModify: removes response headers")
    void responseHeaderModify_removesHeaders() {
        var factory = new ResponseHeaderModifyGatewayFilterFactory();
        var config = new ResponseHeaderModifyGatewayFilterFactory.Config();
        config.setRemove(Map.of("X-Unwanted", "ignored"));
        GatewayFilter filter = factory.apply(config);

        MockServerHttpRequest request = MockServerHttpRequest.get("/api/test").build();
        MockServerWebExchange exchange = MockServerWebExchange.from(request);
        // Pre-set a response header to be removed
        exchange.getResponse().getHeaders().set("X-Unwanted", "should-be-removed");

        StepVerifier.create(filter.filter(exchange, passThroughChain()))
                .verifyComplete();

        assertThat(exchange.getResponse().getHeaders().getFirst("X-Unwanted")).isNull();
    }

    @Test
    @DisplayName("ResponseHeaderModify: adds response headers")
    void responseHeaderModify_addsHeaders() {
        var factory = new ResponseHeaderModifyGatewayFilterFactory();
        var config = new ResponseHeaderModifyGatewayFilterFactory.Config();
        config.setAdd(Map.of("X-Added-Resp", "new-value"));
        GatewayFilter filter = factory.apply(config);

        MockServerHttpRequest request = MockServerHttpRequest.get("/api/test").build();
        MockServerWebExchange exchange = MockServerWebExchange.from(request);

        StepVerifier.create(filter.filter(exchange, passThroughChain()))
                .verifyComplete();

        assertThat(exchange.getResponse().getHeaders().getFirst("X-Added-Resp"))
                .isEqualTo("new-value");
    }
}

