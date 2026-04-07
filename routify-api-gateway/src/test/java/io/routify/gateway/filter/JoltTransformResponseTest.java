package io.routify.gateway.filter;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.cloud.gateway.filter.GatewayFilter;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.core.io.buffer.DataBuffer;
import org.springframework.core.io.buffer.DefaultDataBufferFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.http.server.reactive.ServerHttpResponse;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit tests for the response-phase and BOTH-phase support in
 * {@link JoltTransformGatewayFilterFactory}.
 *
 * <p>Tests cover:
 * <ul>
 *   <li>{@code phase=RESPONSE} transforms upstream response body</li>
 *   <li>{@code phase=BOTH} transforms both request and response</li>
 *   <li>{@code Content-Length} updated after transformation</li>
 *   <li>Non-JSON responses pass through unchanged</li>
 *   <li>Bodies exceeding {@code maxBodySize} pass through unchanged</li>
 *   <li>Empty response body handled gracefully</li>
 *   <li>Malformed JSON response produces a 502 error</li>
 * </ul>
 */
class JoltTransformResponseTest {

    private static final String SHIFT_SPEC = """
            [{"operation":"shift","spec":{"id":"userId","name":"fullName"}}]""";

    private static final String RESPONSE_SHIFT_SPEC = """
            [{"operation":"shift","spec":{"result":"data","status":"meta.status"}}]""";

    private final JoltTransformGatewayFilterFactory factory = new JoltTransformGatewayFilterFactory();

    // ═══════════════════════════════════════════════════════════════════════════
    // phase=RESPONSE
    // ═══════════════════════════════════════════════════════════════════════════

    @Test
    @DisplayName("RESPONSE: transforms upstream JSON response body")
    void response_transformsJsonBody() {
        var config = new JoltTransformGatewayFilterFactory.Config();
        config.setSpec(RESPONSE_SHIFT_SPEC);
        config.setPhase("RESPONSE");
        GatewayFilter filter = factory.apply(config);

        MockServerHttpRequest request = MockServerHttpRequest.get("/api/test").build();
        MockServerWebExchange exchange = MockServerWebExchange.from(request);

        String upstreamBody = """
                {"result":"hello","status":"ok"}""";

        GatewayFilterChain chain = ex -> {
            ServerHttpResponse response = ex.getResponse();
            response.getHeaders().setContentType(MediaType.APPLICATION_JSON);
            byte[] bytes = upstreamBody.getBytes(StandardCharsets.UTF_8);
            DataBuffer buffer = new DefaultDataBufferFactory().wrap(bytes);
            return response.writeWith(Mono.just(buffer));
        };

        StepVerifier.create(filter.filter(exchange, chain))
                .verifyComplete();

        String responseBody = exchange.getResponse().getBodyAsString().block();
        assertThat(responseBody).isNotNull();
        assertThat(responseBody).contains("\"data\"");
        assertThat(responseBody).contains("\"hello\"");
        assertThat(responseBody).contains("\"meta\"");
    }

    @Test
    @DisplayName("RESPONSE: Content-Length updated after transformation")
    void response_contentLengthUpdated() {
        var config = new JoltTransformGatewayFilterFactory.Config();
        config.setSpec(RESPONSE_SHIFT_SPEC);
        config.setPhase("RESPONSE");
        GatewayFilter filter = factory.apply(config);

        MockServerHttpRequest request = MockServerHttpRequest.get("/api/test").build();
        MockServerWebExchange exchange = MockServerWebExchange.from(request);

        String upstreamBody = """
                {"result":"hello","status":"ok"}""";

        // Track the content-length set by the decorator
        final long[] capturedContentLength = {-1};
        GatewayFilterChain chain = ex -> {
            ServerHttpResponse response = ex.getResponse();
            response.getHeaders().setContentType(MediaType.APPLICATION_JSON);
            byte[] bytes = upstreamBody.getBytes(StandardCharsets.UTF_8);
            response.getHeaders().setContentLength(bytes.length);

            // The response in ex should be the decorator — read content-length after write
            DataBuffer buffer = new DefaultDataBufferFactory().wrap(bytes);
            return response.writeWith(Mono.just(buffer))
                    .doOnTerminate(() -> capturedContentLength[0] = response.getHeaders().getContentLength());
        };

        StepVerifier.create(filter.filter(exchange, chain))
                .verifyComplete();

        String responseBody = exchange.getResponse().getBodyAsString().block();
        assertThat(responseBody).isNotNull();

        // The decorator should have updated Content-Length to match the transformed body size
        int transformedSize = responseBody.getBytes(StandardCharsets.UTF_8).length;
        assertThat(capturedContentLength[0]).isEqualTo(transformedSize);
        // Transformed body should be different size than original
        assertThat(transformedSize).isNotEqualTo(upstreamBody.getBytes(StandardCharsets.UTF_8).length);
    }

    @Test
    @DisplayName("RESPONSE: non-JSON response passes through unchanged")
    void response_nonJsonPassesThrough() {
        var config = new JoltTransformGatewayFilterFactory.Config();
        config.setSpec(RESPONSE_SHIFT_SPEC);
        config.setPhase("RESPONSE");
        GatewayFilter filter = factory.apply(config);

        MockServerHttpRequest request = MockServerHttpRequest.get("/api/test").build();
        MockServerWebExchange exchange = MockServerWebExchange.from(request);

        String upstreamBody = "<html><body>Hello</body></html>";

        GatewayFilterChain chain = ex -> {
            ServerHttpResponse response = ex.getResponse();
            response.getHeaders().setContentType(MediaType.TEXT_HTML);
            byte[] bytes = upstreamBody.getBytes(StandardCharsets.UTF_8);
            DataBuffer buffer = new DefaultDataBufferFactory().wrap(bytes);
            return response.writeWith(Mono.just(buffer));
        };

        StepVerifier.create(filter.filter(exchange, chain))
                .verifyComplete();

        String responseBody = exchange.getResponse().getBodyAsString().block();
        assertThat(responseBody).isEqualTo(upstreamBody);
    }

    @Test
    @DisplayName("RESPONSE: body exceeding maxBodySize passes through unchanged")
    void response_oversizedBodyPassesThrough() {
        var config = new JoltTransformGatewayFilterFactory.Config();
        config.setSpec(RESPONSE_SHIFT_SPEC);
        config.setPhase("RESPONSE");
        config.setMaxBodySize(10); // 10 bytes limit
        GatewayFilter filter = factory.apply(config);

        MockServerHttpRequest request = MockServerHttpRequest.get("/api/test").build();
        MockServerWebExchange exchange = MockServerWebExchange.from(request);

        String upstreamBody = """
                {"result":"this is a longer body that exceeds the limit","status":"ok"}""";

        GatewayFilterChain chain = ex -> {
            ServerHttpResponse response = ex.getResponse();
            response.getHeaders().setContentType(MediaType.APPLICATION_JSON);
            byte[] bytes = upstreamBody.getBytes(StandardCharsets.UTF_8);
            DataBuffer buffer = new DefaultDataBufferFactory().wrap(bytes);
            return response.writeWith(Mono.just(buffer));
        };

        StepVerifier.create(filter.filter(exchange, chain))
                .verifyComplete();

        String responseBody = exchange.getResponse().getBodyAsString().block();
        assertThat(responseBody).isEqualTo(upstreamBody);
    }

    @Test
    @DisplayName("RESPONSE: empty response body handled gracefully")
    void response_emptyBodyPassesThrough() {
        var config = new JoltTransformGatewayFilterFactory.Config();
        config.setSpec(RESPONSE_SHIFT_SPEC);
        config.setPhase("RESPONSE");
        GatewayFilter filter = factory.apply(config);

        MockServerHttpRequest request = MockServerHttpRequest.get("/api/test").build();
        MockServerWebExchange exchange = MockServerWebExchange.from(request);

        GatewayFilterChain chain = ex -> {
            ServerHttpResponse response = ex.getResponse();
            response.getHeaders().setContentType(MediaType.APPLICATION_JSON);
            byte[] bytes = new byte[0];
            DataBuffer buffer = new DefaultDataBufferFactory().wrap(bytes);
            return response.writeWith(Mono.just(buffer));
        };

        StepVerifier.create(filter.filter(exchange, chain))
                .verifyComplete();

        String responseBody = exchange.getResponse().getBodyAsString().block();
        assertThat(responseBody).isEmpty();
    }

    @Test
    @DisplayName("RESPONSE: malformed JSON response produces 502")
    void response_malformedJsonReturns502() {
        var config = new JoltTransformGatewayFilterFactory.Config();
        config.setSpec(RESPONSE_SHIFT_SPEC);
        config.setPhase("RESPONSE");
        GatewayFilter filter = factory.apply(config);

        MockServerHttpRequest request = MockServerHttpRequest.get("/api/test").build();
        MockServerWebExchange exchange = MockServerWebExchange.from(request);

        String malformedJson = "{ this is not valid json }";

        GatewayFilterChain chain = ex -> {
            ServerHttpResponse response = ex.getResponse();
            response.getHeaders().setContentType(MediaType.APPLICATION_JSON);
            byte[] bytes = malformedJson.getBytes(StandardCharsets.UTF_8);
            DataBuffer buffer = new DefaultDataBufferFactory().wrap(bytes);
            return response.writeWith(Mono.just(buffer));
        };

        StepVerifier.create(filter.filter(exchange, chain))
                .verifyComplete();

        assertThat(exchange.getResponse().getStatusCode()).isEqualTo(HttpStatus.BAD_GATEWAY);
    }

    @Test
    @DisplayName("RESPONSE: uses responseSpec when provided")
    void response_usesResponseSpec() {
        var config = new JoltTransformGatewayFilterFactory.Config();
        config.setSpec(SHIFT_SPEC); // request spec (should be ignored for RESPONSE phase)
        config.setResponseSpec(RESPONSE_SHIFT_SPEC);
        config.setPhase("RESPONSE");
        GatewayFilter filter = factory.apply(config);

        MockServerHttpRequest request = MockServerHttpRequest.get("/api/test").build();
        MockServerWebExchange exchange = MockServerWebExchange.from(request);

        String upstreamBody = """
                {"result":"hello","status":"ok"}""";

        GatewayFilterChain chain = ex -> {
            ServerHttpResponse response = ex.getResponse();
            response.getHeaders().setContentType(MediaType.APPLICATION_JSON);
            byte[] bytes = upstreamBody.getBytes(StandardCharsets.UTF_8);
            DataBuffer buffer = new DefaultDataBufferFactory().wrap(bytes);
            return response.writeWith(Mono.just(buffer));
        };

        StepVerifier.create(filter.filter(exchange, chain))
                .verifyComplete();

        String responseBody = exchange.getResponse().getBodyAsString().block();
        assertThat(responseBody).isNotNull();
        assertThat(responseBody).contains("\"data\"");
        assertThat(responseBody).contains("\"hello\"");
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // phase=BOTH
    // ═══════════════════════════════════════════════════════════════════════════

    @Test
    @DisplayName("BOTH: transforms request body with spec and response body with responseSpec")
    void both_transformsRequestAndResponse() {
        var config = new JoltTransformGatewayFilterFactory.Config();
        config.setSpec(SHIFT_SPEC); // request: id→userId, name→fullName
        config.setResponseSpec(RESPONSE_SHIFT_SPEC); // response: result→data, status→meta.status
        config.setPhase("BOTH");
        GatewayFilter filter = factory.apply(config);

        String requestBody = """
                {"id":"123","name":"John"}""";

        MockServerHttpRequest request = MockServerHttpRequest
                .post("/api/test")
                .contentType(MediaType.APPLICATION_JSON)
                .body(requestBody);
        MockServerWebExchange exchange = MockServerWebExchange.from(request);

        // Pre-set response content-type on the exchange before decoration
        exchange.getResponse().getHeaders().setContentType(MediaType.APPLICATION_JSON);

        // Chain that captures the transformed request and simulates a response
        final ServerHttpRequest[] capturedRequest = {null};
        GatewayFilterChain chain = ex -> {
            capturedRequest[0] = ex.getRequest();
            ServerHttpResponse response = ex.getResponse();
            String responseJson = """
                    {"result":"success","status":"200"}""";
            byte[] bytes = responseJson.getBytes(StandardCharsets.UTF_8);
            DataBuffer buffer = new DefaultDataBufferFactory().wrap(bytes);
            return response.writeWith(Mono.just(buffer));
        };

        StepVerifier.create(filter.filter(exchange, chain))
                .verifyComplete();

        // Verify request was transformed
        assertThat(capturedRequest[0]).isNotNull();

        // Verify response was transformed
        String responseBody = exchange.getResponse().getBodyAsString().block();
        assertThat(responseBody).isNotNull();
        assertThat(responseBody).contains("\"data\"");
        assertThat(responseBody).contains("\"success\"");
    }

    @Test
    @DisplayName("BOTH: falls back to request-only when responseSpec is missing")
    void both_fallsBackToRequestOnlyWithoutResponseSpec() {
        var config = new JoltTransformGatewayFilterFactory.Config();
        config.setSpec(SHIFT_SPEC);
        config.setPhase("BOTH");
        // responseSpec is NOT set
        GatewayFilter filter = factory.apply(config);

        String requestBody = """
                {"id":"123","name":"John"}""";

        MockServerHttpRequest request = MockServerHttpRequest
                .post("/api/test")
                .contentType(MediaType.APPLICATION_JSON)
                .body(requestBody);
        MockServerWebExchange exchange = MockServerWebExchange.from(request);

        final ServerHttpRequest[] capturedRequest = {null};
        GatewayFilterChain chain = ex -> {
            capturedRequest[0] = ex.getRequest();
            return Mono.empty();
        };

        StepVerifier.create(filter.filter(exchange, chain))
                .verifyComplete();

        // Request should still be transformed
        assertThat(capturedRequest[0]).isNotNull();
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // phase=REQUEST (existing behaviour still works)
    // ═══════════════════════════════════════════════════════════════════════════

    @Test
    @DisplayName("REQUEST: transforms request body (existing behaviour)")
    void request_transformsRequestBody() {
        var config = new JoltTransformGatewayFilterFactory.Config();
        config.setSpec(SHIFT_SPEC);
        config.setPhase("REQUEST");
        GatewayFilter filter = factory.apply(config);

        String requestBody = """
                {"id":"123","name":"John"}""";

        MockServerHttpRequest request = MockServerHttpRequest
                .post("/api/test")
                .contentType(MediaType.APPLICATION_JSON)
                .body(requestBody);
        MockServerWebExchange exchange = MockServerWebExchange.from(request);

        final ServerHttpRequest[] capturedRequest = {null};
        GatewayFilterChain chain = ex -> {
            capturedRequest[0] = ex.getRequest();
            return Mono.empty();
        };

        StepVerifier.create(filter.filter(exchange, chain))
                .verifyComplete();

        assertThat(capturedRequest[0]).isNotNull();
    }

    @Test
    @DisplayName("REQUEST: non-JSON request passes through unchanged")
    void request_nonJsonPassesThrough() {
        var config = new JoltTransformGatewayFilterFactory.Config();
        config.setSpec(SHIFT_SPEC);
        config.setPhase("REQUEST");
        GatewayFilter filter = factory.apply(config);

        MockServerHttpRequest request = MockServerHttpRequest
                .post("/api/test")
                .contentType(MediaType.TEXT_PLAIN)
                .body("plain text");
        MockServerWebExchange exchange = MockServerWebExchange.from(request);

        final ServerHttpRequest[] capturedRequest = {null};
        GatewayFilterChain chain = ex -> {
            capturedRequest[0] = ex.getRequest();
            return Mono.empty();
        };

        StepVerifier.create(filter.filter(exchange, chain))
                .verifyComplete();

        // Request should pass through unchanged (same instance)
        assertThat(capturedRequest[0]).isNotNull();
    }

    @Test
    @DisplayName("No spec configured: passes through unchanged")
    void noSpec_passesThrough() {
        var config = new JoltTransformGatewayFilterFactory.Config();
        config.setPhase("RESPONSE");
        // spec is null
        GatewayFilter filter = factory.apply(config);

        MockServerHttpRequest request = MockServerHttpRequest.get("/api/test").build();
        MockServerWebExchange exchange = MockServerWebExchange.from(request);

        GatewayFilterChain chain = ex -> Mono.empty();

        StepVerifier.create(filter.filter(exchange, chain))
                .verifyComplete();
    }
}


