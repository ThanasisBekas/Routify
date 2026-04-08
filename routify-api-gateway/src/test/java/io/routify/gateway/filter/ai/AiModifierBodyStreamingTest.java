package io.routify.gateway.filter.ai;

import io.routify.common.event.QueryRequest;
import io.routify.common.event.QueryResponse;
import io.routify.gateway.client.AiServiceClient;
import io.routify.gateway.filter.AiModifierGatewayFilterFactory;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
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
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * Unit tests for AI modifier inline body streaming support (initiative P-26).
 *
 * <p>Verifies that:
 * <ul>
 *   <li>The AI modifier reads the body inline using {@code DataBufferUtils.join()}</li>
 *   <li>Body excerpt is limited to {@code maxBodyBytes}</li>
 *   <li>Binary content types are automatically skipped — headers-only evaluation</li>
 *   <li>Textual content types ({@code text/plain}, {@code application/xml}) are read</li>
 *   <li>Body hash is consistent (deterministic SHA-256) for identical payloads</li>
 *   <li>Downstream receives original full body when {@code mutationApplied=false}</li>
 *   <li>Downstream receives mutated body when {@code mutationApplied=true}</li>
 *   <li>Empty body is handled gracefully</li>
 *   <li>Default {@code maxBodyBytes} is 2048</li>
 *   <li>No Content-Type header skips body reading</li>
 * </ul>
 */
class AiModifierBodyStreamingTest {

    private AiServiceClient aiServiceClient;
    private AiModifierGatewayFilterFactory factory;

    @BeforeEach
    void setUp() {
        aiServiceClient = mock(AiServiceClient.class);
        factory = new AiModifierGatewayFilterFactory(aiServiceClient);

        // Default: return passthrough verdict (no mutation applied)
        when(aiServiceClient.modify(any())).thenReturn(
                QueryResponse.AiModifierVerdict.passthrough("no mutation needed", 15L)
        );
    }

    private AiModifierGatewayFilterFactory.Config configWithBody() {
        var config = new AiModifierGatewayFilterFactory.Config();
        config.setIncludeBody(true);
        config.setMaxBodyBytes(2048);
        config.setModificationPrompt("Scrub PII from the request body");
        return config;
    }

    // ─── Inline body reading ──────────────────────────────────────────────────

    @Nested
    @DisplayName("Inline body reading")
    class InlineBodyReading {

        @Test
        @DisplayName("Reads JSON body inline and passes Base64-encoded excerpt to AI service")
        void readsJsonBodyInline() {
            var config = configWithBody();
            GatewayFilter filter = factory.apply(config);

            String body = """
                    {"user": "test@example.com", "ssn": "123-45-6789"}""";
            MockServerHttpRequest request = MockServerHttpRequest.post("/api/test")
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(body);
            MockServerWebExchange exchange = MockServerWebExchange.from(request);

            ArgumentCaptor<QueryRequest.AiModifierEvaluate> captor =
                    ArgumentCaptor.forClass(QueryRequest.AiModifierEvaluate.class);

            StepVerifier.create(filter.filter(exchange, ex -> Mono.empty()))
                    .verifyComplete();

            verify(aiServiceClient).modify(captor.capture());
            QueryRequest.AiModifierEvaluate rpcRequest = captor.getValue();

            // Body excerpt should be Base64-encoded
            assertThat(rpcRequest.bodyBase64()).isNotNull();
            String decodedBody = new String(
                    Base64.getDecoder().decode(rpcRequest.bodyBase64()), StandardCharsets.UTF_8);
            assertThat(decodedBody).isEqualTo(body);

            // Body hash should be present
            assertThat(rpcRequest.bodyHash()).isNotNull().isNotBlank();
        }

        @Test
        @DisplayName("text/plain content type is read and included")
        void textPlainContentTypeRead() {
            var config = configWithBody();
            GatewayFilter filter = factory.apply(config);

            String body = "Hello, world! This contains PII.";
            MockServerHttpRequest request = MockServerHttpRequest.post("/api/text")
                    .contentType(MediaType.TEXT_PLAIN)
                    .body(body);
            MockServerWebExchange exchange = MockServerWebExchange.from(request);

            ArgumentCaptor<QueryRequest.AiModifierEvaluate> captor =
                    ArgumentCaptor.forClass(QueryRequest.AiModifierEvaluate.class);

            StepVerifier.create(filter.filter(exchange, ex -> Mono.empty()))
                    .verifyComplete();

            verify(aiServiceClient).modify(captor.capture());
            assertThat(captor.getValue().bodyBase64()).isNotNull();
            String decoded = new String(
                    Base64.getDecoder().decode(captor.getValue().bodyBase64()), StandardCharsets.UTF_8);
            assertThat(decoded).isEqualTo(body);
        }

        @Test
        @DisplayName("application/xml content type is read and included")
        void xmlContentTypeRead() {
            var config = configWithBody();
            GatewayFilter filter = factory.apply(config);

            String body = "<user><name>John Doe</name><ssn>123-45-6789</ssn></user>";
            MockServerHttpRequest request = MockServerHttpRequest.post("/api/xml")
                    .contentType(MediaType.APPLICATION_XML)
                    .body(body);
            MockServerWebExchange exchange = MockServerWebExchange.from(request);

            ArgumentCaptor<QueryRequest.AiModifierEvaluate> captor =
                    ArgumentCaptor.forClass(QueryRequest.AiModifierEvaluate.class);

            StepVerifier.create(filter.filter(exchange, ex -> Mono.empty()))
                    .verifyComplete();

            verify(aiServiceClient).modify(captor.capture());
            assertThat(captor.getValue().bodyBase64()).isNotNull();
        }
    }

    // ─── Body truncation ──────────────────────────────────────────────────────

    @Nested
    @DisplayName("Body truncation")
    class BodyTruncation {

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

            ArgumentCaptor<QueryRequest.AiModifierEvaluate> captor =
                    ArgumentCaptor.forClass(QueryRequest.AiModifierEvaluate.class);

            StepVerifier.create(filter.filter(exchange, ex -> Mono.empty()))
                    .verifyComplete();

            verify(aiServiceClient).modify(captor.capture());
            QueryRequest.AiModifierEvaluate rpcRequest = captor.getValue();

            byte[] excerptBytes = Base64.getDecoder().decode(rpcRequest.bodyBase64());
            assertThat(excerptBytes).hasSize(16);
            assertThat(new String(excerptBytes, StandardCharsets.UTF_8)).isEqualTo("A".repeat(16));
        }
    }

    // ─── Binary content types skipped ─────────────────────────────────────────

    @Nested
    @DisplayName("Binary content type handling")
    class BinaryContentTypes {

        @Test
        @DisplayName("image/png is automatically skipped — headers-only evaluation")
        void imagePngSkipped() {
            var config = configWithBody();
            GatewayFilter filter = factory.apply(config);

            MockServerHttpRequest request = MockServerHttpRequest.post("/api/upload")
                    .header(HttpHeaders.CONTENT_TYPE, "image/png")
                    .body("fake-binary-data");
            MockServerWebExchange exchange = MockServerWebExchange.from(request);

            ArgumentCaptor<QueryRequest.AiModifierEvaluate> captor =
                    ArgumentCaptor.forClass(QueryRequest.AiModifierEvaluate.class);

            StepVerifier.create(filter.filter(exchange, ex -> Mono.empty()))
                    .verifyComplete();

            verify(aiServiceClient).modify(captor.capture());
            assertThat(captor.getValue().bodyBase64()).isNull();
            assertThat(captor.getValue().bodyHash()).isNull();
        }

        @Test
        @DisplayName("multipart/form-data is automatically skipped")
        void multipartSkipped() {
            var config = configWithBody();
            GatewayFilter filter = factory.apply(config);

            MockServerHttpRequest request = MockServerHttpRequest.post("/api/upload")
                    .header(HttpHeaders.CONTENT_TYPE, "multipart/form-data; boundary=----Boundary")
                    .body("fake-multipart-data");
            MockServerWebExchange exchange = MockServerWebExchange.from(request);

            ArgumentCaptor<QueryRequest.AiModifierEvaluate> captor =
                    ArgumentCaptor.forClass(QueryRequest.AiModifierEvaluate.class);

            StepVerifier.create(filter.filter(exchange, ex -> Mono.empty()))
                    .verifyComplete();

            verify(aiServiceClient).modify(captor.capture());
            assertThat(captor.getValue().bodyBase64()).isNull();
            assertThat(captor.getValue().bodyHash()).isNull();
        }

        @Test
        @DisplayName("application/octet-stream is automatically skipped")
        void octetStreamSkipped() {
            var config = configWithBody();
            GatewayFilter filter = factory.apply(config);

            MockServerHttpRequest request = MockServerHttpRequest.post("/api/binary")
                    .header(HttpHeaders.CONTENT_TYPE, "application/octet-stream")
                    .body("binary-data");
            MockServerWebExchange exchange = MockServerWebExchange.from(request);

            ArgumentCaptor<QueryRequest.AiModifierEvaluate> captor =
                    ArgumentCaptor.forClass(QueryRequest.AiModifierEvaluate.class);

            StepVerifier.create(filter.filter(exchange, ex -> Mono.empty()))
                    .verifyComplete();

            verify(aiServiceClient).modify(captor.capture());
            assertThat(captor.getValue().bodyBase64()).isNull();
            assertThat(captor.getValue().bodyHash()).isNull();
        }

        @Test
        @DisplayName("Request with no Content-Type header skips body reading")
        void noContentTypeSkipsBody() {
            var config = configWithBody();
            GatewayFilter filter = factory.apply(config);

            MockServerHttpRequest request = MockServerHttpRequest.post("/api/test")
                    .body("some data");
            MockServerWebExchange exchange = MockServerWebExchange.from(request);

            ArgumentCaptor<QueryRequest.AiModifierEvaluate> captor =
                    ArgumentCaptor.forClass(QueryRequest.AiModifierEvaluate.class);

            StepVerifier.create(filter.filter(exchange, ex -> Mono.empty()))
                    .verifyComplete();

            verify(aiServiceClient).modify(captor.capture());
            assertThat(captor.getValue().bodyBase64()).isNull();
            assertThat(captor.getValue().bodyHash()).isNull();
        }
    }

    // ─── Body hash consistency ────────────────────────────────────────────────

    @Nested
    @DisplayName("Body hash")
    class BodyHash {

        @Test
        @DisplayName("Body hash is consistent (deterministic) for identical payloads")
        void bodyHashConsistentForIdenticalPayloads() {
            var config = configWithBody();

            String body = """
                    {"key": "value", "secret": "s3cr3t"}""";

            // First request
            GatewayFilter filter1 = factory.apply(config);
            MockServerHttpRequest request1 = MockServerHttpRequest.post("/api/test")
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(body);
            MockServerWebExchange exchange1 = MockServerWebExchange.from(request1);

            ArgumentCaptor<QueryRequest.AiModifierEvaluate> captor1 =
                    ArgumentCaptor.forClass(QueryRequest.AiModifierEvaluate.class);

            StepVerifier.create(filter1.filter(exchange1, ex -> Mono.empty()))
                    .verifyComplete();
            verify(aiServiceClient, times(1)).modify(captor1.capture());
            String hash1 = captor1.getValue().bodyHash();

            // Second request with identical body
            GatewayFilter filter2 = factory.apply(config);
            MockServerHttpRequest request2 = MockServerHttpRequest.post("/api/test")
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(body);
            MockServerWebExchange exchange2 = MockServerWebExchange.from(request2);

            ArgumentCaptor<QueryRequest.AiModifierEvaluate> captor2 =
                    ArgumentCaptor.forClass(QueryRequest.AiModifierEvaluate.class);

            StepVerifier.create(filter2.filter(exchange2, ex -> Mono.empty()))
                    .verifyComplete();
            verify(aiServiceClient, times(2)).modify(captor2.capture());
            String hash2 = captor2.getAllValues().get(1).bodyHash();

            assertThat(hash1).isNotNull().isEqualTo(hash2);
        }

        @Test
        @DisplayName("Body hash matches expected SHA-256 hex value")
        void bodyHashMatchesExpectedSha256() throws Exception {
            var config = configWithBody();
            GatewayFilter filter = factory.apply(config);

            String body = "test-payload-for-modification";
            MockServerHttpRequest request = MockServerHttpRequest.post("/api/test")
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(body);
            MockServerWebExchange exchange = MockServerWebExchange.from(request);

            ArgumentCaptor<QueryRequest.AiModifierEvaluate> captor =
                    ArgumentCaptor.forClass(QueryRequest.AiModifierEvaluate.class);

            StepVerifier.create(filter.filter(exchange, ex -> Mono.empty()))
                    .verifyComplete();

            verify(aiServiceClient).modify(captor.capture());
            String actualHash = captor.getValue().bodyHash();

            // Compute expected SHA-256 hex
            byte[] testBytes = body.getBytes(StandardCharsets.UTF_8);
            String expectedHash = HexFormat.of().formatHex(
                    MessageDigest.getInstance("SHA-256").digest(testBytes)
            );

            assertThat(actualHash).isEqualTo(expectedHash);
        }
    }

    // ─── Downstream body handling ─────────────────────────────────────────────

    @Nested
    @DisplayName("Downstream body handling")
    class DownstreamBody {

        @Test
        @DisplayName("Passthrough: downstream receives original full body unchanged")
        void passthroughPreservesOriginalBody() {
            var config = configWithBody();
            GatewayFilter filter = factory.apply(config);

            String body = """
                    {"message": "this is the complete body that must arrive downstream unchanged"}""";
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

        @Test
        @DisplayName("Mutation applied: downstream receives mutated body")
        void mutationAppliedReplacesBody() {
            var config = configWithBody();
            GatewayFilter filter = factory.apply(config);

            String originalBody = """
                    {"user": "test@example.com", "ssn": "123-45-6789"}""";
            String mutatedBody = """
                    {"user": "[REDACTED]", "ssn": "[REDACTED]"}""";

            // Return a verdict with mutation applied
            when(aiServiceClient.modify(any())).thenReturn(
                    new QueryResponse.AiModifierVerdict(
                            "mutation-001", true, "PII_SCRUB",
                            Map.of(), mutatedBody, "PII scrubbed", false, 50L)
            );

            MockServerHttpRequest request = MockServerHttpRequest.post("/api/test")
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(originalBody);
            MockServerWebExchange exchange = MockServerWebExchange.from(request);

            AtomicReference<ServerWebExchange> capturedExchange = new AtomicReference<>();
            GatewayFilterChain capturingChain = ex -> {
                capturedExchange.set(ex);
                return Mono.empty();
            };

            StepVerifier.create(filter.filter(exchange, capturingChain))
                    .verifyComplete();

            assertThat(capturedExchange.get()).isNotNull();
            ServerHttpRequest downstreamRequest = capturedExchange.get().getRequest();

            // Downstream should receive the mutated body
            Flux<DataBuffer> bodyFlux = downstreamRequest.getBody();
            StepVerifier.create(bodyFlux
                            .map(buf -> {
                                byte[] bytes = new byte[buf.readableByteCount()];
                                buf.read(bytes);
                                return new String(bytes, StandardCharsets.UTF_8);
                            })
                            .reduce(String::concat))
                    .assertNext(downstreamBody -> assertThat(downstreamBody).isEqualTo(mutatedBody))
                    .verifyComplete();

            // Verify observability headers are injected
            assertThat(downstreamRequest.getHeaders().getFirst("X-AI-Modifier-Applied")).isEqualTo("true");
            assertThat(downstreamRequest.getHeaders().getFirst("X-AI-Modifier-Id")).isEqualTo("mutation-001");
            assertThat(downstreamRequest.getHeaders().getFirst("X-AI-Modifier-Type")).isEqualTo("PII_SCRUB");
        }

        @Test
        @DisplayName("Mutation applied: Content-Length header updated to match mutated body")
        void mutationUpdatesContentLength() {
            var config = configWithBody();
            GatewayFilter filter = factory.apply(config);

            String originalBody = """
                    {"user": "test@example.com"}""";
            String mutatedBody = """
                    {"user": "[REDACTED]"}""";

            when(aiServiceClient.modify(any())).thenReturn(
                    new QueryResponse.AiModifierVerdict(
                            "mutation-002", true, "PII_SCRUB",
                            Map.of(), mutatedBody, "PII scrubbed", false, 30L)
            );

            MockServerHttpRequest request = MockServerHttpRequest.post("/api/test")
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(originalBody);
            MockServerWebExchange exchange = MockServerWebExchange.from(request);

            AtomicReference<ServerWebExchange> capturedExchange = new AtomicReference<>();
            GatewayFilterChain capturingChain = ex -> {
                capturedExchange.set(ex);
                return Mono.empty();
            };

            StepVerifier.create(filter.filter(exchange, capturingChain))
                    .verifyComplete();

            assertThat(capturedExchange.get()).isNotNull();
            String contentLength = capturedExchange.get().getRequest().getHeaders()
                    .getFirst(HttpHeaders.CONTENT_LENGTH);
            assertThat(contentLength).isEqualTo(
                    String.valueOf(mutatedBody.getBytes(StandardCharsets.UTF_8).length));
        }
    }

    // ─── Edge cases ───────────────────────────────────────────────────────────

    @Nested
    @DisplayName("Edge cases")
    class EdgeCases {

        @Test
        @DisplayName("Empty body is handled gracefully")
        void emptyBodyHandledGracefully() {
            var config = configWithBody();
            GatewayFilter filter = factory.apply(config);

            MockServerHttpRequest request = MockServerHttpRequest.post("/api/test")
                    .contentType(MediaType.APPLICATION_JSON)
                    .body("");
            MockServerWebExchange exchange = MockServerWebExchange.from(request);

            ArgumentCaptor<QueryRequest.AiModifierEvaluate> captor =
                    ArgumentCaptor.forClass(QueryRequest.AiModifierEvaluate.class);

            StepVerifier.create(filter.filter(exchange, ex -> Mono.empty()))
                    .verifyComplete();

            verify(aiServiceClient).modify(captor.capture());
            // Empty body with includeBody=true → bodyBase64 should be null (0-length check)
            assertThat(captor.getValue().bodyBase64()).isNull();
            assertThat(captor.getValue().bodyHash()).isNull();
        }

        @Test
        @DisplayName("includeBody=false skips body excerpt even for JSON content")
        void includeBodyFalseSkipsExcerpt() {
            var config = configWithBody();
            config.setIncludeBody(false);
            GatewayFilter filter = factory.apply(config);

            MockServerHttpRequest request = MockServerHttpRequest.post("/api/test")
                    .contentType(MediaType.APPLICATION_JSON)
                    .body("{\"data\": \"sensitive\"}");
            MockServerWebExchange exchange = MockServerWebExchange.from(request);

            ArgumentCaptor<QueryRequest.AiModifierEvaluate> captor =
                    ArgumentCaptor.forClass(QueryRequest.AiModifierEvaluate.class);

            StepVerifier.create(filter.filter(exchange, ex -> Mono.empty()))
                    .verifyComplete();

            verify(aiServiceClient).modify(captor.capture());
            // includeBody=false but the modifier still buffers the body for re-emission;
            // however the bodyBase64 should be null because includeBody is false
            assertThat(captor.getValue().bodyBase64()).isNull();
        }

        @Test
        @DisplayName("Default maxBodyBytes is 2048")
        void defaultMaxBodyBytesIs2048() {
            var config = new AiModifierGatewayFilterFactory.Config();
            assertThat(config.getMaxBodyBytes()).isEqualTo(2048);
        }
    }
}

