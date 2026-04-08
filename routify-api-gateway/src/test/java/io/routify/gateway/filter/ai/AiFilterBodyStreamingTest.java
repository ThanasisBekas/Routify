package io.routify.gateway.filter.ai;

import io.routify.common.event.QueryRequest;
import io.routify.common.event.QueryResponse;
import io.routify.gateway.client.AiServiceClient;
import io.routify.gateway.filter.AiGatewayFilterFactory;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.cloud.gateway.filter.GatewayFilter;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.core.io.buffer.DataBuffer;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Base64;
import java.util.HexFormat;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * Unit tests for AI filter inline body streaming support (initiative GF-08).
 *
 * <p>Verifies that:
 * <ul>
 *   <li>The AI filter reads the body inline without a pre-caching filter</li>
 *   <li>Body excerpt is limited to {@code maxBodyBytes}</li>
 *   <li>Binary content types are automatically skipped</li>
 *   <li>JSON content type is read and included</li>
 *   <li>Body hash is consistent for identical payloads</li>
 *   <li>Upstream services receive the original full body unchanged</li>
 *   <li>Empty body is handled gracefully</li>
 * </ul>
 */
class AiFilterBodyStreamingTest {

    private AiServiceClient aiServiceClient;
    private AiGatewayFilterFactory factory;

    @BeforeEach
    void setUp() {
        aiServiceClient = mock(AiServiceClient.class);
        factory = new AiGatewayFilterFactory(aiServiceClient);

        // Default: return ALLOW verdict
        when(aiServiceClient.evaluate(any())).thenReturn(
                new QueryResponse.AiFilterVerdict("ALLOW", "allowed", 0.95, true, false, 10L, "eval-1")
        );
    }

    private AiGatewayFilterFactory.Config configWithBody() {
        var config = new AiGatewayFilterFactory.Config();
        config.setIncludeBody(true);
        config.setMaxBodyBytes(2048);
        config.setPolicyDescription("test policy");
        return config;
    }

    // ─── Test: Inline body reading (no pre-caching filter) ────────────────────

    @Test
    @DisplayName("AI filter reads JSON body inline without pre-caching filter")
    void readsJsonBodyInlineWithoutPreCachingFilter() {
        var config = configWithBody();
        GatewayFilter filter = factory.apply(config);

        String body = """
                {"user": "test@example.com", "action": "login"}""";
        MockServerHttpRequest request = MockServerHttpRequest.post("/api/test")
                .contentType(MediaType.APPLICATION_JSON)
                .body(body);
        MockServerWebExchange exchange = MockServerWebExchange.from(request);

        ArgumentCaptor<QueryRequest.AiFilterEvaluate> captor =
                ArgumentCaptor.forClass(QueryRequest.AiFilterEvaluate.class);

        StepVerifier.create(filter.filter(exchange, ex -> Mono.empty()))
                .verifyComplete();

        verify(aiServiceClient).evaluate(captor.capture());
        QueryRequest.AiFilterEvaluate rpcRequest = captor.getValue();

        // Body excerpt should be Base64-encoded
        assertThat(rpcRequest.bodyExcerpt()).isNotNull();
        String decodedBody = new String(Base64.getDecoder().decode(rpcRequest.bodyExcerpt()), StandardCharsets.UTF_8);
        assertThat(decodedBody).isEqualTo(body);

        // Body hash should be present
        assertThat(rpcRequest.bodyHash()).isNotNull().isNotBlank();
    }

    // ─── Test: Body excerpt limited to maxBodyBytes ───────────────────────────

    @Test
    @DisplayName("Body excerpt is truncated at maxBodyBytes")
    void bodyExcerptTruncatedAtMaxBodyBytes() {
        var config = configWithBody();
        config.setMaxBodyBytes(16); // Very small limit
        GatewayFilter filter = factory.apply(config);

        String body = "A".repeat(100); // 100 bytes — much larger than limit
        MockServerHttpRequest request = MockServerHttpRequest.post("/api/test")
                .contentType(MediaType.APPLICATION_JSON)
                .body(body);
        MockServerWebExchange exchange = MockServerWebExchange.from(request);

        ArgumentCaptor<QueryRequest.AiFilterEvaluate> captor =
                ArgumentCaptor.forClass(QueryRequest.AiFilterEvaluate.class);

        StepVerifier.create(filter.filter(exchange, ex -> Mono.empty()))
                .verifyComplete();

        verify(aiServiceClient).evaluate(captor.capture());
        QueryRequest.AiFilterEvaluate rpcRequest = captor.getValue();

        byte[] excerptBytes = Base64.getDecoder().decode(rpcRequest.bodyExcerpt());
        assertThat(excerptBytes).hasSize(16);
        assertThat(new String(excerptBytes, StandardCharsets.UTF_8)).isEqualTo("A".repeat(16));
    }

    // ─── Test: Binary content types skipped ───────────────────────────────────

    @Test
    @DisplayName("Binary content type (image/png) is automatically skipped")
    void binaryContentTypeSkipped() {
        var config = configWithBody();
        GatewayFilter filter = factory.apply(config);

        MockServerHttpRequest request = MockServerHttpRequest.post("/api/upload")
                .header(HttpHeaders.CONTENT_TYPE, "image/png")
                .body("fake-binary-data");
        MockServerWebExchange exchange = MockServerWebExchange.from(request);

        ArgumentCaptor<QueryRequest.AiFilterEvaluate> captor =
                ArgumentCaptor.forClass(QueryRequest.AiFilterEvaluate.class);

        StepVerifier.create(filter.filter(exchange, ex -> Mono.empty()))
                .verifyComplete();

        verify(aiServiceClient).evaluate(captor.capture());
        QueryRequest.AiFilterEvaluate rpcRequest = captor.getValue();

        // Body excerpt and hash should be null for binary types
        assertThat(rpcRequest.bodyExcerpt()).isNull();
        assertThat(rpcRequest.bodyHash()).isNull();
    }

    @Test
    @DisplayName("Multipart content type is automatically skipped")
    void multipartContentTypeSkipped() {
        var config = configWithBody();
        GatewayFilter filter = factory.apply(config);

        MockServerHttpRequest request = MockServerHttpRequest.post("/api/upload")
                .header(HttpHeaders.CONTENT_TYPE, "multipart/form-data; boundary=----WebKitFormBoundary")
                .body("fake-multipart-data");
        MockServerWebExchange exchange = MockServerWebExchange.from(request);

        ArgumentCaptor<QueryRequest.AiFilterEvaluate> captor =
                ArgumentCaptor.forClass(QueryRequest.AiFilterEvaluate.class);

        StepVerifier.create(filter.filter(exchange, ex -> Mono.empty()))
                .verifyComplete();

        verify(aiServiceClient).evaluate(captor.capture());
        assertThat(captor.getValue().bodyExcerpt()).isNull();
        assertThat(captor.getValue().bodyHash()).isNull();
    }

    @Test
    @DisplayName("Octet-stream content type is automatically skipped")
    void octetStreamContentTypeSkipped() {
        var config = configWithBody();
        GatewayFilter filter = factory.apply(config);

        MockServerHttpRequest request = MockServerHttpRequest.post("/api/binary")
                .header(HttpHeaders.CONTENT_TYPE, "application/octet-stream")
                .body("binary-data");
        MockServerWebExchange exchange = MockServerWebExchange.from(request);

        ArgumentCaptor<QueryRequest.AiFilterEvaluate> captor =
                ArgumentCaptor.forClass(QueryRequest.AiFilterEvaluate.class);

        StepVerifier.create(filter.filter(exchange, ex -> Mono.empty()))
                .verifyComplete();

        verify(aiServiceClient).evaluate(captor.capture());
        assertThat(captor.getValue().bodyExcerpt()).isNull();
        assertThat(captor.getValue().bodyHash()).isNull();
    }

    // ─── Test: Textual content types are read ─────────────────────────────────

    @Test
    @DisplayName("text/plain content type is read and included")
    void textPlainContentTypeRead() {
        var config = configWithBody();
        GatewayFilter filter = factory.apply(config);

        String body = "Hello, world!";
        MockServerHttpRequest request = MockServerHttpRequest.post("/api/text")
                .contentType(MediaType.TEXT_PLAIN)
                .body(body);
        MockServerWebExchange exchange = MockServerWebExchange.from(request);

        ArgumentCaptor<QueryRequest.AiFilterEvaluate> captor =
                ArgumentCaptor.forClass(QueryRequest.AiFilterEvaluate.class);

        StepVerifier.create(filter.filter(exchange, ex -> Mono.empty()))
                .verifyComplete();

        verify(aiServiceClient).evaluate(captor.capture());
        assertThat(captor.getValue().bodyExcerpt()).isNotNull();
        String decoded = new String(Base64.getDecoder().decode(captor.getValue().bodyExcerpt()), StandardCharsets.UTF_8);
        assertThat(decoded).isEqualTo(body);
    }

    @Test
    @DisplayName("application/xml content type is read and included")
    void xmlContentTypeRead() {
        var config = configWithBody();
        GatewayFilter filter = factory.apply(config);

        String body = "<root><item>test</item></root>";
        MockServerHttpRequest request = MockServerHttpRequest.post("/api/xml")
                .contentType(MediaType.APPLICATION_XML)
                .body(body);
        MockServerWebExchange exchange = MockServerWebExchange.from(request);

        ArgumentCaptor<QueryRequest.AiFilterEvaluate> captor =
                ArgumentCaptor.forClass(QueryRequest.AiFilterEvaluate.class);

        StepVerifier.create(filter.filter(exchange, ex -> Mono.empty()))
                .verifyComplete();

        verify(aiServiceClient).evaluate(captor.capture());
        assertThat(captor.getValue().bodyExcerpt()).isNotNull();
    }

    // ─── Test: Body hash consistency ──────────────────────────────────────────

    @Test
    @DisplayName("Body hash is consistent (deterministic) for identical payloads")
    void bodyHashConsistentForIdenticalPayloads() {
        var config = configWithBody();

        String body = """
                {"key": "value", "number": 42}""";

        // First request
        GatewayFilter filter1 = factory.apply(config);
        MockServerHttpRequest request1 = MockServerHttpRequest.post("/api/test")
                .contentType(MediaType.APPLICATION_JSON)
                .body(body);
        MockServerWebExchange exchange1 = MockServerWebExchange.from(request1);

        ArgumentCaptor<QueryRequest.AiFilterEvaluate> captor1 =
                ArgumentCaptor.forClass(QueryRequest.AiFilterEvaluate.class);

        StepVerifier.create(filter1.filter(exchange1, ex -> Mono.empty()))
                .verifyComplete();
        verify(aiServiceClient, times(1)).evaluate(captor1.capture());
        String hash1 = captor1.getValue().bodyHash();

        // Second request with identical body
        GatewayFilter filter2 = factory.apply(config);
        MockServerHttpRequest request2 = MockServerHttpRequest.post("/api/test")
                .contentType(MediaType.APPLICATION_JSON)
                .body(body);
        MockServerWebExchange exchange2 = MockServerWebExchange.from(request2);

        ArgumentCaptor<QueryRequest.AiFilterEvaluate> captor2 =
                ArgumentCaptor.forClass(QueryRequest.AiFilterEvaluate.class);

        StepVerifier.create(filter2.filter(exchange2, ex -> Mono.empty()))
                .verifyComplete();
        verify(aiServiceClient, times(2)).evaluate(captor2.capture());
        String hash2 = captor2.getAllValues().get(1).bodyHash();

        assertThat(hash1).isNotNull().isEqualTo(hash2);
    }

    @Test
    @DisplayName("Body hash matches expected SHA-256 hex value")
    void bodyHashMatchesExpectedSha256() throws Exception {
        var config = configWithBody();
        GatewayFilter filter = factory.apply(config);

        String body = "test-payload";
        MockServerHttpRequest request = MockServerHttpRequest.post("/api/test")
                .contentType(MediaType.APPLICATION_JSON)
                .body(body);
        MockServerWebExchange exchange = MockServerWebExchange.from(request);

        ArgumentCaptor<QueryRequest.AiFilterEvaluate> captor =
                ArgumentCaptor.forClass(QueryRequest.AiFilterEvaluate.class);

        StepVerifier.create(filter.filter(exchange, ex -> Mono.empty()))
                .verifyComplete();

        verify(aiServiceClient).evaluate(captor.capture());
        String actualHash = captor.getValue().bodyHash();

        // Compute expected SHA-256 hex
        byte[] testBytes = body.getBytes(StandardCharsets.UTF_8);
        String expectedHash = HexFormat.of().formatHex(
                MessageDigest.getInstance("SHA-256").digest(testBytes)
        );

        assertThat(actualHash).isEqualTo(expectedHash);
    }

    // ─── Test: Body re-emission for downstream ────────────────────────────────

    @Test
    @DisplayName("Upstream services receive the original full body unchanged")
    void upstreamReceivesOriginalFullBody() {
        var config = configWithBody();
        GatewayFilter filter = factory.apply(config);

        String body = """
                {"message": "this is the complete body that must arrive downstream"}""";
        MockServerHttpRequest request = MockServerHttpRequest.post("/api/test")
                .contentType(MediaType.APPLICATION_JSON)
                .body(body);
        MockServerWebExchange exchange = MockServerWebExchange.from(request);

        AtomicReference<ServerWebExchange> capturedExchange = new AtomicReference<>();
        GatewayFilterChain capturingChain = ex -> {
            capturedExchange.set(ex);
            return Mono.empty();
        };

        StepVerifier.create(filter.filter(exchange, capturingChain))
                .verifyComplete();

        // Verify the downstream exchange has the full body available
        assertThat(capturedExchange.get()).isNotNull();
        ServerHttpRequest downstreamRequest = capturedExchange.get().getRequest();

        // Read the body from the decorated request
        Flux<DataBuffer> bodyFlux = downstreamRequest.getBody();
        StepVerifier.create(bodyFlux
                        .map(buf -> {
                            byte[] bytes = new byte[buf.readableByteCount()];
                            buf.read(bytes);
                            return new String(bytes, StandardCharsets.UTF_8);
                        })
                        .reduce(String::concat))
                .assertNext(downstreamBody -> assertThat(downstreamBody).isEqualTo(body))
                .verifyComplete();
    }

    // ─── Test: Empty body ─────────────────────────────────────────────────────

    @Test
    @DisplayName("Empty body is handled gracefully (body excerpt and hash are null)")
    void emptyBodyHandledGracefully() {
        var config = configWithBody();
        GatewayFilter filter = factory.apply(config);

        MockServerHttpRequest request = MockServerHttpRequest.post("/api/test")
                .contentType(MediaType.APPLICATION_JSON)
                .body("");
        MockServerWebExchange exchange = MockServerWebExchange.from(request);

        ArgumentCaptor<QueryRequest.AiFilterEvaluate> captor =
                ArgumentCaptor.forClass(QueryRequest.AiFilterEvaluate.class);

        StepVerifier.create(filter.filter(exchange, ex -> Mono.empty()))
                .verifyComplete();

        verify(aiServiceClient).evaluate(captor.capture());
        assertThat(captor.getValue().bodyExcerpt()).isNull();
        assertThat(captor.getValue().bodyHash()).isNull();
    }

    // ─── Test: includeBody=false skips body reading ───────────────────────────

    @Test
    @DisplayName("includeBody=false skips body reading entirely")
    void includeBodyFalseSkipsBodyReading() {
        var config = configWithBody();
        config.setIncludeBody(false);
        GatewayFilter filter = factory.apply(config);

        MockServerHttpRequest request = MockServerHttpRequest.post("/api/test")
                .contentType(MediaType.APPLICATION_JSON)
                .body("{\"data\": \"value\"}");
        MockServerWebExchange exchange = MockServerWebExchange.from(request);

        ArgumentCaptor<QueryRequest.AiFilterEvaluate> captor =
                ArgumentCaptor.forClass(QueryRequest.AiFilterEvaluate.class);

        StepVerifier.create(filter.filter(exchange, ex -> Mono.empty()))
                .verifyComplete();

        verify(aiServiceClient).evaluate(captor.capture());
        assertThat(captor.getValue().bodyExcerpt()).isNull();
        assertThat(captor.getValue().bodyHash()).isNull();
    }

    // ─── Test: Default maxBodyBytes is 2048 ───────────────────────────────────

    @Test
    @DisplayName("Default maxBodyBytes is 2048")
    void defaultMaxBodyBytesIs2048() {
        var config = new AiGatewayFilterFactory.Config();
        assertThat(config.getMaxBodyBytes()).isEqualTo(2048);
    }

    // ─── Test: No content-type header ─────────────────────────────────────────

    @Test
    @DisplayName("Request with no Content-Type header skips body reading")
    void noContentTypeSkipsBodyReading() {
        var config = configWithBody();
        GatewayFilter filter = factory.apply(config);

        // No content type set
        MockServerHttpRequest request = MockServerHttpRequest.post("/api/test")
                .body("some data");
        MockServerWebExchange exchange = MockServerWebExchange.from(request);

        ArgumentCaptor<QueryRequest.AiFilterEvaluate> captor =
                ArgumentCaptor.forClass(QueryRequest.AiFilterEvaluate.class);

        StepVerifier.create(filter.filter(exchange, ex -> Mono.empty()))
                .verifyComplete();

        verify(aiServiceClient).evaluate(captor.capture());
        assertThat(captor.getValue().bodyExcerpt()).isNull();
        assertThat(captor.getValue().bodyHash()).isNull();
    }
}




