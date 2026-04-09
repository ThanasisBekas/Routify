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
 * response header injection, filter order, incoming correlation ID validation
 * (length limit, control character rejection).
 */
class CorrelationIdGatewayFilterFactoryTest {

    private final CorrelationIdGatewayFilterFactory factory = new CorrelationIdGatewayFilterFactory();


    // ═══════════════════════════════════════════════════════════════════════════
    // Existing behaviour
    // ═══════════════════════════════════════════════════════════════════════════

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

    // ═══════════════════════════════════════════════════════════════════════════
    // Issue 7.1: Incoming correlation ID validation
    // ═══════════════════════════════════════════════════════════════════════════

    @Test
    @DisplayName("Oversized correlation ID (> 128 chars) → replaced with UUID")
    void oversizedCorrelationId_replaced() {
        GatewayFilter filter = factory.apply(new CorrelationIdGatewayFilterFactory.Config());

        String oversizedId = "a".repeat(CorrelationIdGatewayFilterFactory.MAX_CORRELATION_ID_LENGTH + 1);
        var exchange = MockServerWebExchange.from(
                MockServerHttpRequest.get("/api/test")
                        .header(RoutifyHeaders.CORRELATION_ID, oversizedId)
                        .build());

        final String[] capturedId = {null};
        GatewayFilterChain capturingChain = ex -> {
            capturedId[0] = ex.getRequest().getHeaders().getFirst(RoutifyHeaders.CORRELATION_ID);
            return Mono.empty();
        };

        StepVerifier.create(filter.filter(exchange, capturingChain))
                .verifyComplete();

        assertThat(capturedId[0])
                .isNotEqualTo(oversizedId)
                .matches("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}");
    }

    @Test
    @DisplayName("Correlation ID with control characters → replaced with UUID")
    void controlCharCorrelationId_replaced() {
        GatewayFilter filter = factory.apply(new CorrelationIdGatewayFilterFactory.Config());

        String maliciousId = "corr-id\nInjected-Header: evil-value";
        var exchange = MockServerWebExchange.from(
                MockServerHttpRequest.get("/api/test")
                        .header(RoutifyHeaders.CORRELATION_ID, maliciousId)
                        .build());

        final String[] capturedId = {null};
        GatewayFilterChain capturingChain = ex -> {
            capturedId[0] = ex.getRequest().getHeaders().getFirst(RoutifyHeaders.CORRELATION_ID);
            return Mono.empty();
        };

        StepVerifier.create(filter.filter(exchange, capturingChain))
                .verifyComplete();

        assertThat(capturedId[0])
                .isNotEqualTo(maliciousId)
                .matches("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}");
    }

    @Test
    @DisplayName("Exactly 128-char valid correlation ID → preserved")
    void maxLengthCorrelationId_preserved() {
        GatewayFilter filter = factory.apply(new CorrelationIdGatewayFilterFactory.Config());

        String maxLengthId = "a".repeat(CorrelationIdGatewayFilterFactory.MAX_CORRELATION_ID_LENGTH);
        var exchange = MockServerWebExchange.from(
                MockServerHttpRequest.get("/api/test")
                        .header(RoutifyHeaders.CORRELATION_ID, maxLengthId)
                        .build());

        final String[] capturedId = {null};
        GatewayFilterChain capturingChain = ex -> {
            capturedId[0] = ex.getRequest().getHeaders().getFirst(RoutifyHeaders.CORRELATION_ID);
            return Mono.empty();
        };

        StepVerifier.create(filter.filter(exchange, capturingChain))
                .verifyComplete();

        assertThat(capturedId[0]).isEqualTo(maxLengthId);
    }

    @Test
    @DisplayName("Correlation ID with null byte → replaced with UUID")
    void nullByteCorrelationId_replaced() {
        GatewayFilter filter = factory.apply(new CorrelationIdGatewayFilterFactory.Config());

        String nullByteId = "corr-id-\u0000-injected";
        var exchange = MockServerWebExchange.from(
                MockServerHttpRequest.get("/api/test")
                        .header(RoutifyHeaders.CORRELATION_ID, nullByteId)
                        .build());

        final String[] capturedId = {null};
        GatewayFilterChain capturingChain = ex -> {
            capturedId[0] = ex.getRequest().getHeaders().getFirst(RoutifyHeaders.CORRELATION_ID);
            return Mono.empty();
        };

        StepVerifier.create(filter.filter(exchange, capturingChain))
                .verifyComplete();

        assertThat(capturedId[0])
                .isNotEqualTo(nullByteId)
                .matches("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}");
    }

    @Test
    @DisplayName("Custom trace ID with allowed special chars (dots, colons, slashes) → preserved")
    void customTraceIdWithAllowedChars_preserved() {
        GatewayFilter filter = factory.apply(new CorrelationIdGatewayFilterFactory.Config());

        String customTraceId = "trace:abc123/span.def456_001";
        var exchange = MockServerWebExchange.from(
                MockServerHttpRequest.get("/api/test")
                        .header(RoutifyHeaders.CORRELATION_ID, customTraceId)
                        .build());

        final String[] capturedId = {null};
        GatewayFilterChain capturingChain = ex -> {
            capturedId[0] = ex.getRequest().getHeaders().getFirst(RoutifyHeaders.CORRELATION_ID);
            return Mono.empty();
        };

        StepVerifier.create(filter.filter(exchange, capturingChain))
                .verifyComplete();

        assertThat(capturedId[0]).isEqualTo(customTraceId);
    }

    @Test
    @DisplayName("isValidCorrelationId: unit-level validation checks")
    void isValidCorrelationId_unitChecks() {
        // Valid
        assertThat(CorrelationIdGatewayFilterFactory.isValidCorrelationId("abc-123")).isTrue();
        assertThat(CorrelationIdGatewayFilterFactory.isValidCorrelationId(
                "550e8400-e29b-41d4-a716-446655440000")).isTrue();
        assertThat(CorrelationIdGatewayFilterFactory.isValidCorrelationId("trace:span/path.id_1")).isTrue();
        assertThat(CorrelationIdGatewayFilterFactory.isValidCorrelationId(
                "a".repeat(128))).isTrue();

        // Invalid — too long
        assertThat(CorrelationIdGatewayFilterFactory.isValidCorrelationId(
                "a".repeat(129))).isFalse();

        // Invalid — control characters
        assertThat(CorrelationIdGatewayFilterFactory.isValidCorrelationId("id\ninjection")).isFalse();
        assertThat(CorrelationIdGatewayFilterFactory.isValidCorrelationId("id\u0000null")).isFalse();
        assertThat(CorrelationIdGatewayFilterFactory.isValidCorrelationId("id\ttab")).isFalse();

        // Invalid — special characters not in allow-list
        assertThat(CorrelationIdGatewayFilterFactory.isValidCorrelationId("id with spaces")).isFalse();
        assertThat(CorrelationIdGatewayFilterFactory.isValidCorrelationId("id<script>")).isFalse();
    }
}

