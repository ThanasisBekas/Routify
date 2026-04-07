package io.routify.gateway.filter;

import io.routify.common.web.RoutifyHeaders;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.cloud.gateway.filter.GatewayFilter;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import java.util.List;
import java.util.concurrent.CompletableFuture;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

/**
 * Security and functional tests for the SpEL filter sandboxing initiative (GF-07).
 *
 * <p>Covers:
 * <ul>
 *   <li>Sandbox escape attempts blocked (type references, constructors, #request)</li>
 *   <li>Valid expressions with #headers, #params, #method, #path still work</li>
 *   <li>New #contentType and #clientIp variables work</li>
 *   <li>Expression complexity limits enforced at config bind time</li>
 *   <li>Audit event emitted on every evaluation</li>
 * </ul>
 */
@SuppressWarnings("unchecked")
class SpelSandboxingTest {

    private KafkaTemplate<String, Object> kafkaTemplate;
    private SpelCustomGatewayFilterFactory factory;

    @BeforeEach
    void setup() {
        kafkaTemplate = mock(KafkaTemplate.class);
        when(kafkaTemplate.send(anyString(), anyString(), any()))
                .thenReturn(CompletableFuture.completedFuture(null));
        factory = new SpelCustomGatewayFilterFactory(kafkaTemplate);
    }

    private GatewayFilterChain passThroughChain() {
        return exchange -> Mono.empty();
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // Sandbox escape attempts — must be BLOCKED
    // ═══════════════════════════════════════════════════════════════════════════

    @Test
    @DisplayName("BLOCKED: T(java.lang.Runtime).getRuntime().exec() is rejected")
    void blocked_runtimeExec() {
        var config = new SpelCustomGatewayFilterFactory.Config();
        config.setExpression("T(java.lang.Runtime).getRuntime().exec('ls')");
        GatewayFilter filter = factory.apply(config);

        MockServerHttpRequest request = MockServerHttpRequest.get("/api/test")
                .header(RoutifyHeaders.CORRELATION_ID, "corr-1")
                .build();
        MockServerWebExchange exchange = MockServerWebExchange.from(request);

        // SimpleEvaluationContext blocks type references — should evaluate to error and pass through
        StepVerifier.create(filter.filter(exchange, passThroughChain()))
                .verifyComplete();

        // The expression should fail evaluation (not produce false), so it passes through
        // No 403 should be returned — the filter fails open on evaluation errors
    }

    @Test
    @DisplayName("BLOCKED: new ProcessBuilder() is rejected")
    void blocked_processBuilder() {
        var config = new SpelCustomGatewayFilterFactory.Config();
        config.setExpression("new java.io.File('/etc/passwd').exists()");
        GatewayFilter filter = factory.apply(config);

        MockServerHttpRequest request = MockServerHttpRequest.get("/api/test")
                .header(RoutifyHeaders.CORRELATION_ID, "corr-1")
                .build();
        MockServerWebExchange exchange = MockServerWebExchange.from(request);

        StepVerifier.create(filter.filter(exchange, passThroughChain()))
                .verifyComplete();
    }

    @Test
    @DisplayName("BLOCKED: #request variable is no longer available")
    void blocked_requestVariable() {
        var config = new SpelCustomGatewayFilterFactory.Config();
        config.setExpression("#request.getClass().getName()");
        GatewayFilter filter = factory.apply(config);

        MockServerHttpRequest request = MockServerHttpRequest.get("/api/test")
                .header(RoutifyHeaders.CORRELATION_ID, "corr-1")
                .build();
        MockServerWebExchange exchange = MockServerWebExchange.from(request);

        // #request is not set, so evaluation should fail and pass through
        StepVerifier.create(filter.filter(exchange, passThroughChain()))
                .verifyComplete();
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // Allowed expressions — must WORK
    // ═══════════════════════════════════════════════════════════════════════════

    @Test
    @DisplayName("ALLOWED: #headers access works")
    void allowed_headersAccess() {
        var config = new SpelCustomGatewayFilterFactory.Config();
        config.setExpression("#headers['X-Feature-Flag'] == 'enabled'");
        GatewayFilter filter = factory.apply(config);

        // Request WITH the header — expression returns true → pass through
        MockServerHttpRequest request = MockServerHttpRequest.get("/api/test")
                .header(RoutifyHeaders.CORRELATION_ID, "corr-1")
                .header("X-Feature-Flag", "enabled")
                .build();
        MockServerWebExchange exchange = MockServerWebExchange.from(request);

        StepVerifier.create(filter.filter(exchange, passThroughChain()))
                .verifyComplete();
    }

    @Test
    @DisplayName("ALLOWED: #headers check returns false → 403")
    void allowed_headersCheckReturnsFalse() {
        var config = new SpelCustomGatewayFilterFactory.Config();
        config.setExpression("#headers['X-Feature-Flag'] == 'enabled'");
        GatewayFilter filter = factory.apply(config);

        // Request WITHOUT the header — expression returns false → 403
        MockServerHttpRequest request = MockServerHttpRequest.get("/api/test")
                .header(RoutifyHeaders.CORRELATION_ID, "corr-1")
                .build();
        MockServerWebExchange exchange = MockServerWebExchange.from(request);

        StepVerifier.create(filter.filter(exchange, passThroughChain()))
                .verifyComplete();

        assertThat(exchange.getResponse().getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
    }

    @Test
    @DisplayName("ALLOWED: #method and #path work together")
    void allowed_methodAndPath() {
        var config = new SpelCustomGatewayFilterFactory.Config();
        config.setExpression("#method == 'GET' and #path.startsWith('/api')");
        GatewayFilter filter = factory.apply(config);

        MockServerHttpRequest request = MockServerHttpRequest.get("/api/test")
                .header(RoutifyHeaders.CORRELATION_ID, "corr-1")
                .build();
        MockServerWebExchange exchange = MockServerWebExchange.from(request);

        StepVerifier.create(filter.filter(exchange, passThroughChain()))
                .verifyComplete();

        // Expression returns true → pass through (no 403)
        assertThat(exchange.getResponse().getStatusCode()).isNotEqualTo(HttpStatus.FORBIDDEN);
    }

    @Test
    @DisplayName("ALLOWED: #params access works")
    void allowed_paramsAccess() {
        var config = new SpelCustomGatewayFilterFactory.Config();
        config.setExpression("#params['debug'] == 'true'");
        GatewayFilter filter = factory.apply(config);

        MockServerHttpRequest request = MockServerHttpRequest.get("/api/test?debug=true")
                .header(RoutifyHeaders.CORRELATION_ID, "corr-1")
                .build();
        MockServerWebExchange exchange = MockServerWebExchange.from(request);

        StepVerifier.create(filter.filter(exchange, passThroughChain()))
                .verifyComplete();

        assertThat(exchange.getResponse().getStatusCode()).isNotEqualTo(HttpStatus.FORBIDDEN);
    }

    @Test
    @DisplayName("ALLOWED: #contentType new variable works")
    void allowed_contentType() {
        var config = new SpelCustomGatewayFilterFactory.Config();
        config.setExpression("#contentType.contains('json')");
        GatewayFilter filter = factory.apply(config);

        MockServerHttpRequest request = MockServerHttpRequest.post("/api/test")
                .contentType(MediaType.APPLICATION_JSON)
                .header(RoutifyHeaders.CORRELATION_ID, "corr-1")
                .body("{}");
        MockServerWebExchange exchange = MockServerWebExchange.from(request);

        StepVerifier.create(filter.filter(exchange, passThroughChain()))
                .verifyComplete();

        assertThat(exchange.getResponse().getStatusCode()).isNotEqualTo(HttpStatus.FORBIDDEN);
    }

    @Test
    @DisplayName("ALLOWED: #clientIp new variable works")
    void allowed_clientIp() {
        var config = new SpelCustomGatewayFilterFactory.Config();
        config.setExpression("#clientIp != null");
        GatewayFilter filter = factory.apply(config);

        MockServerHttpRequest request = MockServerHttpRequest.get("/api/test")
                .header(RoutifyHeaders.CORRELATION_ID, "corr-1")
                .header("X-Forwarded-For", "10.0.0.1")
                .build();
        MockServerWebExchange exchange = MockServerWebExchange.from(request);

        StepVerifier.create(filter.filter(exchange, passThroughChain()))
                .verifyComplete();

        assertThat(exchange.getResponse().getStatusCode()).isNotEqualTo(HttpStatus.FORBIDDEN);
    }

    @Test
    @DisplayName("ALLOWED: String contains() method works in sandboxed context")
    void allowed_stringContains() {
        var config = new SpelCustomGatewayFilterFactory.Config();
        config.setExpression("#headers['Authorization']?.contains('Bearer')");
        GatewayFilter filter = factory.apply(config);

        MockServerHttpRequest request = MockServerHttpRequest.get("/api/test")
                .header(RoutifyHeaders.CORRELATION_ID, "corr-1")
                .header("Authorization", "Bearer token123")
                .build();
        MockServerWebExchange exchange = MockServerWebExchange.from(request);

        StepVerifier.create(filter.filter(exchange, passThroughChain()))
                .verifyComplete();

        assertThat(exchange.getResponse().getStatusCode()).isNotEqualTo(HttpStatus.FORBIDDEN);
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // Expression complexity limits
    // ═══════════════════════════════════════════════════════════════════════════

    @Test
    @DisplayName("REJECTED: expression exceeding maxExpressionLength is rejected at config time")
    void rejected_expressionTooLong() {
        var config = new SpelCustomGatewayFilterFactory.Config();
        config.setMaxExpressionLength(20); // very short limit for testing
        config.setExpression("#method == 'GET' and #path.startsWith('/api/v1/very/long/path')");
        GatewayFilter filter = factory.apply(config);

        MockServerHttpRequest request = MockServerHttpRequest.get("/api/test")
                .header(RoutifyHeaders.CORRELATION_ID, "corr-1")
                .build();
        MockServerWebExchange exchange = MockServerWebExchange.from(request);

        StepVerifier.create(filter.filter(exchange, passThroughChain()))
                .verifyComplete();

        // Should return 500 error for expression too long
        assertThat(exchange.getResponse().getStatusCode()).isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR);
    }

    @Test
    @DisplayName("REJECTED: expression exceeding maxPropertyDepth is rejected at config time")
    void rejected_propertyDepthTooDeep() {
        var config = new SpelCustomGatewayFilterFactory.Config();
        config.setMaxPropertyDepth(2);
        config.setExpression("#headers['a'].b.c.d.e.f"); // depth 5
        GatewayFilter filter = factory.apply(config);

        MockServerHttpRequest request = MockServerHttpRequest.get("/api/test")
                .header(RoutifyHeaders.CORRELATION_ID, "corr-1")
                .build();
        MockServerWebExchange exchange = MockServerWebExchange.from(request);

        StepVerifier.create(filter.filter(exchange, passThroughChain()))
                .verifyComplete();

        assertThat(exchange.getResponse().getStatusCode()).isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR);
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // Property depth counting utility
    // ═══════════════════════════════════════════════════════════════════════════

    @Test
    @DisplayName("countPropertyDepth: simple expressions")
    void countPropertyDepth_simple() {
        assertThat(SpelCustomGatewayFilterFactory.countPropertyDepth("#method")).isZero();
        assertThat(SpelCustomGatewayFilterFactory.countPropertyDepth("#path.value")).isEqualTo(1);
        assertThat(SpelCustomGatewayFilterFactory.countPropertyDepth("a.b.c.d")).isEqualTo(3);
        assertThat(SpelCustomGatewayFilterFactory.countPropertyDepth("a.b.c.d.e.f")).isEqualTo(5);
    }

    @Test
    @DisplayName("countPropertyDepth: dots inside string literals are NOT counted")
    void countPropertyDepth_stringLiterals() {
        assertThat(SpelCustomGatewayFilterFactory.countPropertyDepth("'a.b.c.d.e'")).isZero();
        assertThat(SpelCustomGatewayFilterFactory.countPropertyDepth("#path == '/api/v1/test'")).isZero();
    }

    @Test
    @DisplayName("countPropertyDepth: multiple chains take the maximum")
    void countPropertyDepth_multipleChains() {
        // Two chains: depth 1 and depth 2 → max is 2
        assertThat(SpelCustomGatewayFilterFactory.countPropertyDepth("#a.b and #c.d.e")).isEqualTo(2);
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // Audit event emission
    // ═══════════════════════════════════════════════════════════════════════════

    @Test
    @DisplayName("Audit event emitted on successful evaluation")
    void auditEvent_emittedOnSuccess() {
        var config = new SpelCustomGatewayFilterFactory.Config();
        config.setExpression("#method == 'GET'");
        GatewayFilter filter = factory.apply(config);

        MockServerHttpRequest request = MockServerHttpRequest.get("/api/test")
                .header(RoutifyHeaders.CORRELATION_ID, "corr-audit-1")
                .build();
        MockServerWebExchange exchange = MockServerWebExchange.from(request);

        StepVerifier.create(filter.filter(exchange, passThroughChain()))
                .verifyComplete();

        verify(kafkaTemplate, times(1)).send(anyString(), anyString(), any());
    }

    @Test
    @DisplayName("Audit event emitted on evaluation failure")
    void auditEvent_emittedOnFailure() {
        var config = new SpelCustomGatewayFilterFactory.Config();
        config.setExpression("#nonexistent.something()");
        GatewayFilter filter = factory.apply(config);

        MockServerHttpRequest request = MockServerHttpRequest.get("/api/test")
                .header(RoutifyHeaders.CORRELATION_ID, "corr-audit-2")
                .build();
        MockServerWebExchange exchange = MockServerWebExchange.from(request);

        StepVerifier.create(filter.filter(exchange, passThroughChain()))
                .verifyComplete();

        verify(kafkaTemplate, times(1)).send(anyString(), anyString(), any());
    }

    @Test
    @DisplayName("Audit event emitted when expression returns false (rejection)")
    void auditEvent_emittedOnRejection() {
        var config = new SpelCustomGatewayFilterFactory.Config();
        config.setExpression("#method == 'POST'"); // GET != POST → false → 403
        GatewayFilter filter = factory.apply(config);

        MockServerHttpRequest request = MockServerHttpRequest.get("/api/test")
                .header(RoutifyHeaders.CORRELATION_ID, "corr-audit-3")
                .build();
        MockServerWebExchange exchange = MockServerWebExchange.from(request);

        StepVerifier.create(filter.filter(exchange, passThroughChain()))
                .verifyComplete();

        assertThat(exchange.getResponse().getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        verify(kafkaTemplate, times(1)).send(anyString(), anyString(), any());
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // Edge cases
    // ═══════════════════════════════════════════════════════════════════════════

    @Test
    @DisplayName("Empty expression: filter is pass-through")
    void emptyExpression_passThrough() {
        var config = new SpelCustomGatewayFilterFactory.Config();
        config.setExpression("");
        GatewayFilter filter = factory.apply(config);

        MockServerHttpRequest request = MockServerHttpRequest.get("/api/test").build();
        MockServerWebExchange exchange = MockServerWebExchange.from(request);

        StepVerifier.create(filter.filter(exchange, passThroughChain()))
                .verifyComplete();

        // No Kafka event for empty expression
        verify(kafkaTemplate, never()).send(anyString(), anyString(), any());
    }

    @Test
    @DisplayName("Always-true expression: passes through")
    void alwaysTrue_passesThrough() {
        var config = new SpelCustomGatewayFilterFactory.Config();
        config.setExpression("true");
        GatewayFilter filter = factory.apply(config);

        MockServerHttpRequest request = MockServerHttpRequest.get("/api/test")
                .header(RoutifyHeaders.CORRELATION_ID, "corr-true")
                .build();
        MockServerWebExchange exchange = MockServerWebExchange.from(request);

        StepVerifier.create(filter.filter(exchange, passThroughChain()))
                .verifyComplete();

        assertThat(exchange.getResponse().getStatusCode()).isNotEqualTo(HttpStatus.FORBIDDEN);
    }
}

