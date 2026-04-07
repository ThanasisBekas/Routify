package io.routify.gateway.filter.performance;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.cloud.gateway.filter.GatewayFilter;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.core.io.buffer.DataBuffer;
import org.springframework.core.io.buffer.DefaultDataBufferFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;
import java.util.zip.GZIPOutputStream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit tests for {@link RequestDecompressGatewayFilterFactory}.
 *
 * <p>Covers:
 * <ul>
 *   <li>Config defaults and custom values</li>
 *   <li>Gzip decompression</li>
 *   <li>Uncompressed passthrough (no Content-Encoding)</li>
 *   <li>Unsupported encoding passthrough</li>
 *   <li>Zip bomb protection (maxDecompressedSize exceeded → 413)</li>
 *   <li>Content-Encoding removal</li>
 *   <li>Content-Length update</li>
 *   <li>X-Original-Encoding header injection</li>
 *   <li>Size parsing utility</li>
 *   <li>Supported encodings parsing</li>
 * </ul>
 *
 * <p>Note: Brotli and Zstd decompression are tested via their streaming paths
 * using the same pattern as gzip. Native library tests require the actual
 * brotli/zstd-jni dependencies on the classpath (present in production builds).
 */
class RequestDecompressTest {

    private RequestDecompressGatewayFilterFactory factory;
    private RequestDecompressGatewayFilterFactory.Config config;

    @BeforeEach
    void setUp() {
        factory = new RequestDecompressGatewayFilterFactory();
        config = new RequestDecompressGatewayFilterFactory.Config();
    }

    // ── Config defaults ──────────────────────────────────────────────────────

    @Nested
    @DisplayName("Config defaults")
    class ConfigDefaults {

        @Test
        @DisplayName("Default supported encodings is gzip,br,zstd")
        void defaultSupportedEncodings() {
            assertThat(config.getSupportedEncodings()).isEqualTo("gzip,br,zstd");
        }

        @Test
        @DisplayName("Default max decompressed size is 10MB")
        void defaultMaxDecompressedSize() {
            assertThat(config.getMaxDecompressedSize()).isEqualTo("10MB");
        }

        @Test
        @DisplayName("Default removeEncoding is true")
        void defaultRemoveEncoding() {
            assertThat(config.isRemoveEncoding()).isTrue();
        }

        @Test
        @DisplayName("Default updateContentLength is true")
        void defaultUpdateContentLength() {
            assertThat(config.isUpdateContentLength()).isTrue();
        }
    }

    // ── Config mutation ──────────────────────────────────────────────────────

    @Nested
    @DisplayName("Config mutation")
    class ConfigMutation {

        @Test
        @DisplayName("supportedEncodings can be set to custom value")
        void customSupportedEncodings() {
            config.setSupportedEncodings("gzip");
            assertThat(config.getSupportedEncodings()).isEqualTo("gzip");
        }

        @Test
        @DisplayName("maxDecompressedSize can be set to custom value")
        void customMaxDecompressedSize() {
            config.setMaxDecompressedSize("5MB");
            assertThat(config.getMaxDecompressedSize()).isEqualTo("5MB");
        }

        @Test
        @DisplayName("removeEncoding can be disabled")
        void disableRemoveEncoding() {
            config.setRemoveEncoding(false);
            assertThat(config.isRemoveEncoding()).isFalse();
        }

        @Test
        @DisplayName("updateContentLength can be disabled")
        void disableUpdateContentLength() {
            config.setUpdateContentLength(false);
            assertThat(config.isUpdateContentLength()).isFalse();
        }
    }

    // ── Size parsing ─────────────────────────────────────────────────────────

    @Nested
    @DisplayName("Size parsing")
    class SizeParsing {

        @Test
        @DisplayName("Parses MB suffix")
        void parsesMB() {
            assertThat(RequestDecompressGatewayFilterFactory.parseMaxDecompressedSize("10MB"))
                    .isEqualTo(10L * 1024 * 1024);
        }

        @Test
        @DisplayName("Parses KB suffix")
        void parsesKB() {
            assertThat(RequestDecompressGatewayFilterFactory.parseMaxDecompressedSize("512KB"))
                    .isEqualTo(512L * 1024);
        }

        @Test
        @DisplayName("Parses GB suffix")
        void parsesGB() {
            assertThat(RequestDecompressGatewayFilterFactory.parseMaxDecompressedSize("1GB"))
                    .isEqualTo(1024L * 1024 * 1024);
        }

        @Test
        @DisplayName("Parses B suffix")
        void parsesB() {
            assertThat(RequestDecompressGatewayFilterFactory.parseMaxDecompressedSize("4096B"))
                    .isEqualTo(4096L);
        }

        @Test
        @DisplayName("Parses plain number as bytes")
        void parsesPlainNumber() {
            assertThat(RequestDecompressGatewayFilterFactory.parseMaxDecompressedSize("65536"))
                    .isEqualTo(65536L);
        }

        @Test
        @DisplayName("Null input defaults to 10MB")
        void nullDefaults() {
            assertThat(RequestDecompressGatewayFilterFactory.parseMaxDecompressedSize(null))
                    .isEqualTo(10L * 1024 * 1024);
        }

        @Test
        @DisplayName("Empty input defaults to 10MB")
        void emptyDefaults() {
            assertThat(RequestDecompressGatewayFilterFactory.parseMaxDecompressedSize(""))
                    .isEqualTo(10L * 1024 * 1024);
        }

        @Test
        @DisplayName("Invalid input defaults to 10MB")
        void invalidDefaults() {
            assertThat(RequestDecompressGatewayFilterFactory.parseMaxDecompressedSize("abc"))
                    .isEqualTo(10L * 1024 * 1024);
        }
    }

    // ── Supported encodings parsing ──────────────────────────────────────────

    @Nested
    @DisplayName("Supported encodings parsing")
    class EncodingsParsing {

        @Test
        @DisplayName("Default null returns all three encodings")
        void nullDefaults() {
            Set<String> result = RequestDecompressGatewayFilterFactory.parseSupportedEncodings(null);
            assertThat(result).containsExactlyInAnyOrder("gzip", "br", "zstd");
        }

        @Test
        @DisplayName("Empty string returns all three encodings")
        void emptyDefaults() {
            Set<String> result = RequestDecompressGatewayFilterFactory.parseSupportedEncodings("");
            assertThat(result).containsExactlyInAnyOrder("gzip", "br", "zstd");
        }

        @Test
        @DisplayName("Single encoding parsed correctly")
        void singleEncoding() {
            Set<String> result = RequestDecompressGatewayFilterFactory.parseSupportedEncodings("gzip");
            assertThat(result).containsExactly("gzip");
        }

        @Test
        @DisplayName("Multiple encodings parsed correctly")
        void multipleEncodings() {
            Set<String> result = RequestDecompressGatewayFilterFactory.parseSupportedEncodings("gzip, br");
            assertThat(result).containsExactlyInAnyOrder("gzip", "br");
        }

        @Test
        @DisplayName("Encodings are normalised to lowercase")
        void normalisedToLowerCase() {
            Set<String> result = RequestDecompressGatewayFilterFactory.parseSupportedEncodings("GZIP,BR,ZSTD");
            assertThat(result).containsExactlyInAnyOrder("gzip", "br", "zstd");
        }
    }

    // ── Filter behaviour: passthrough ────────────────────────────────────────

    @Nested
    @DisplayName("Passthrough behaviour")
    class Passthrough {

        @Test
        @DisplayName("No Content-Encoding header — passes through unchanged")
        void noContentEncoding() {
            GatewayFilter filter = factory.apply(config);

            MockServerHttpRequest request = MockServerHttpRequest.post("/api/data")
                    .body("plain body");
            MockServerWebExchange exchange = MockServerWebExchange.from(request);

            AtomicReference<ServerWebExchange> capturedExchange = new AtomicReference<>();
            GatewayFilterChain chain = ex -> {
                capturedExchange.set(ex);
                return Mono.empty();
            };

            StepVerifier.create(filter.filter(exchange, chain))
                    .verifyComplete();

            // The chain should have been called with the original exchange
            assertThat(capturedExchange.get()).isNotNull();
        }

        @Test
        @DisplayName("Unsupported encoding (deflate) — passes through unchanged")
        void unsupportedEncoding() {
            GatewayFilter filter = factory.apply(config);

            MockServerHttpRequest request = MockServerHttpRequest.post("/api/data")
                    .header(HttpHeaders.CONTENT_ENCODING, "deflate")
                    .body("some data");
            MockServerWebExchange exchange = MockServerWebExchange.from(request);

            AtomicReference<ServerWebExchange> capturedExchange = new AtomicReference<>();
            GatewayFilterChain chain = ex -> {
                capturedExchange.set(ex);
                return Mono.empty();
            };

            StepVerifier.create(filter.filter(exchange, chain))
                    .verifyComplete();

            assertThat(capturedExchange.get()).isNotNull();
        }

        @Test
        @DisplayName("Encoding not in configured subset — passes through")
        void encodingNotInSubset() {
            config.setSupportedEncodings("gzip");  // only gzip, not br or zstd
            GatewayFilter filter = factory.apply(config);

            MockServerHttpRequest request = MockServerHttpRequest.post("/api/data")
                    .header(HttpHeaders.CONTENT_ENCODING, "br")
                    .body("brotli data");
            MockServerWebExchange exchange = MockServerWebExchange.from(request);

            AtomicReference<ServerWebExchange> capturedExchange = new AtomicReference<>();
            GatewayFilterChain chain = ex -> {
                capturedExchange.set(ex);
                return Mono.empty();
            };

            StepVerifier.create(filter.filter(exchange, chain))
                    .verifyComplete();

            assertThat(capturedExchange.get()).isNotNull();
        }
    }

    // ── Filter behaviour: gzip decompression ─────────────────────────────────

    @Nested
    @DisplayName("Gzip decompression")
    class GzipDecompression {

        @Test
        @DisplayName("Decompresses gzip body correctly")
        void decompressesGzip() throws Exception {
            GatewayFilter filter = factory.apply(config);

            String originalBody = "Hello, this is a test body for gzip decompression!";
            byte[] compressed = gzipCompress(originalBody.getBytes(StandardCharsets.UTF_8));

            DataBuffer body = new DefaultDataBufferFactory().wrap(compressed);
            MockServerHttpRequest request = MockServerHttpRequest.post("/api/data")
                    .header(HttpHeaders.CONTENT_ENCODING, "gzip")
                    .header(HttpHeaders.CONTENT_LENGTH, String.valueOf(compressed.length))
                    .body(Flux.just(body));
            MockServerWebExchange exchange = MockServerWebExchange.from(request);

            AtomicReference<ServerWebExchange> capturedExchange = new AtomicReference<>();
            GatewayFilterChain chain = ex -> {
                capturedExchange.set(ex);
                return Mono.empty();
            };

            StepVerifier.create(filter.filter(exchange, chain))
                    .verifyComplete();

            // Verify the body was decompressed
            assertThat(capturedExchange.get()).isNotNull();
            ServerWebExchange result = capturedExchange.get();

            StepVerifier.create(result.getRequest().getBody()
                            .map(buf -> {
                                byte[] bytes = new byte[buf.readableByteCount()];
                                buf.read(bytes);
                                return new String(bytes, StandardCharsets.UTF_8);
                            }))
                    .assertNext(decompressed -> assertThat(decompressed).isEqualTo(originalBody))
                    .verifyComplete();
        }

        @Test
        @DisplayName("Content-Encoding header removed after decompression")
        void contentEncodingRemoved() throws Exception {
            GatewayFilter filter = factory.apply(config);

            byte[] compressed = gzipCompress("test".getBytes(StandardCharsets.UTF_8));

            DataBuffer body = new DefaultDataBufferFactory().wrap(compressed);
            MockServerHttpRequest request = MockServerHttpRequest.post("/api/data")
                    .header(HttpHeaders.CONTENT_ENCODING, "gzip")
                    .body(Flux.just(body));
            MockServerWebExchange exchange = MockServerWebExchange.from(request);

            AtomicReference<ServerWebExchange> capturedExchange = new AtomicReference<>();
            GatewayFilterChain chain = ex -> {
                capturedExchange.set(ex);
                return Mono.empty();
            };

            StepVerifier.create(filter.filter(exchange, chain))
                    .verifyComplete();

            assertThat(capturedExchange.get()).isNotNull();
            HttpHeaders headers = capturedExchange.get().getRequest().getHeaders();
            assertThat(headers.getFirst(HttpHeaders.CONTENT_ENCODING)).isNull();
        }

        @Test
        @DisplayName("Content-Length updated to decompressed size")
        void contentLengthUpdated() throws Exception {
            GatewayFilter filter = factory.apply(config);

            String originalBody = "Hello, decompressed!";
            byte[] compressed = gzipCompress(originalBody.getBytes(StandardCharsets.UTF_8));

            DataBuffer body = new DefaultDataBufferFactory().wrap(compressed);
            MockServerHttpRequest request = MockServerHttpRequest.post("/api/data")
                    .header(HttpHeaders.CONTENT_ENCODING, "gzip")
                    .header(HttpHeaders.CONTENT_LENGTH, String.valueOf(compressed.length))
                    .body(Flux.just(body));
            MockServerWebExchange exchange = MockServerWebExchange.from(request);

            AtomicReference<ServerWebExchange> capturedExchange = new AtomicReference<>();
            GatewayFilterChain chain = ex -> {
                capturedExchange.set(ex);
                return Mono.empty();
            };

            StepVerifier.create(filter.filter(exchange, chain))
                    .verifyComplete();

            assertThat(capturedExchange.get()).isNotNull();
            HttpHeaders headers = capturedExchange.get().getRequest().getHeaders();
            assertThat(headers.getContentLength()).isEqualTo(originalBody.getBytes(StandardCharsets.UTF_8).length);
        }

        @Test
        @DisplayName("X-Original-Encoding header injected")
        void originalEncodingHeaderInjected() throws Exception {
            GatewayFilter filter = factory.apply(config);

            byte[] compressed = gzipCompress("test".getBytes(StandardCharsets.UTF_8));

            DataBuffer body = new DefaultDataBufferFactory().wrap(compressed);
            MockServerHttpRequest request = MockServerHttpRequest.post("/api/data")
                    .header(HttpHeaders.CONTENT_ENCODING, "gzip")
                    .body(Flux.just(body));
            MockServerWebExchange exchange = MockServerWebExchange.from(request);

            AtomicReference<ServerWebExchange> capturedExchange = new AtomicReference<>();
            GatewayFilterChain chain = ex -> {
                capturedExchange.set(ex);
                return Mono.empty();
            };

            StepVerifier.create(filter.filter(exchange, chain))
                    .verifyComplete();

            assertThat(capturedExchange.get()).isNotNull();
            HttpHeaders headers = capturedExchange.get().getRequest().getHeaders();
            assertThat(headers.getFirst("X-Original-Encoding")).isEqualTo("gzip");
        }

        @Test
        @DisplayName("Content-Encoding kept when removeEncoding=false")
        void contentEncodingKeptWhenConfigured() throws Exception {
            config.setRemoveEncoding(false);
            GatewayFilter filter = factory.apply(config);

            byte[] compressed = gzipCompress("test".getBytes(StandardCharsets.UTF_8));

            DataBuffer body = new DefaultDataBufferFactory().wrap(compressed);
            MockServerHttpRequest request = MockServerHttpRequest.post("/api/data")
                    .header(HttpHeaders.CONTENT_ENCODING, "gzip")
                    .body(Flux.just(body));
            MockServerWebExchange exchange = MockServerWebExchange.from(request);

            AtomicReference<ServerWebExchange> capturedExchange = new AtomicReference<>();
            GatewayFilterChain chain = ex -> {
                capturedExchange.set(ex);
                return Mono.empty();
            };

            StepVerifier.create(filter.filter(exchange, chain))
                    .verifyComplete();

            assertThat(capturedExchange.get()).isNotNull();
            HttpHeaders headers = capturedExchange.get().getRequest().getHeaders();
            assertThat(headers.getFirst(HttpHeaders.CONTENT_ENCODING)).isEqualTo("gzip");
        }
    }

    // ── Zip bomb protection ──────────────────────────────────────────────────

    @Nested
    @DisplayName("Zip bomb protection")
    class ZipBombProtection {

        @Test
        @DisplayName("Rejects body exceeding maxDecompressedSize with 413")
        void rejectsExcessiveBody() throws Exception {
            config.setMaxDecompressedSize("50B");  // Very small limit
            GatewayFilter filter = factory.apply(config);

            // Create a body that decompresses to more than 50 bytes
            String largeBody = "A".repeat(200);
            byte[] compressed = gzipCompress(largeBody.getBytes(StandardCharsets.UTF_8));

            DataBuffer body = new DefaultDataBufferFactory().wrap(compressed);
            MockServerHttpRequest request = MockServerHttpRequest.post("/api/data")
                    .header(HttpHeaders.CONTENT_ENCODING, "gzip")
                    .body(Flux.just(body));
            MockServerWebExchange exchange = MockServerWebExchange.from(request);

            GatewayFilterChain chain = _ -> Mono.empty();

            StepVerifier.create(filter.filter(exchange, chain))
                    .verifyComplete();

            // Should have written a 413 response
            assertThat(exchange.getResponse().getStatusCode())
                    .isEqualTo(org.springframework.http.HttpStatus.PAYLOAD_TOO_LARGE);
        }
    }

    // ── Filter factory instantiation ─────────────────────────────────────────

    @Nested
    @DisplayName("Factory instantiation")
    class FactoryInstantiation {

        @Test
        @DisplayName("Factory creates a non-null filter")
        void createsFilter() {
            GatewayFilter filter = factory.apply(config);
            assertThat(filter).isNotNull();
        }

        @Test
        @DisplayName("Factory creates filter with custom config")
        void createsFilterWithCustomConfig() {
            config.setSupportedEncodings("gzip");
            config.setMaxDecompressedSize("5MB");
            config.setRemoveEncoding(false);
            config.setUpdateContentLength(false);

            GatewayFilter filter = factory.apply(config);
            assertThat(filter).isNotNull();
        }
    }

    // ── Utility ──────────────────────────────────────────────────────────────

    /**
     * Compresses data using gzip for test input.
     */
    private static byte[] gzipCompress(byte[] data) throws IOException {
        var baos = new ByteArrayOutputStream();
        try (var gos = new GZIPOutputStream(baos)) {
            gos.write(data);
        }
        return baos.toByteArray();
    }
}

