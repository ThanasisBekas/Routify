package io.routify.gateway.filter.performance;

import io.routify.gateway.filter.shared.GatewayProblemResponse;
import lombok.Data;
import lombok.extern.slf4j.Slf4j;
import org.springframework.cloud.gateway.filter.GatewayFilter;
import org.springframework.cloud.gateway.filter.factory.AbstractGatewayFilterFactory;
import org.springframework.cloud.gateway.support.ServerWebExchangeUtils;
import org.springframework.core.io.buffer.DataBuffer;
import org.springframework.core.io.buffer.DataBufferFactory;
import org.springframework.core.io.buffer.DataBufferUtils;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.http.server.reactive.ServerHttpRequestDecorator;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import java.io.*;
import java.util.*;
import java.util.regex.Pattern;
import java.util.zip.GZIPInputStream;

/**
 * Transparently decompresses {@code gzip}, {@code br} (Brotli), and {@code zstd}
 * encoded request bodies before forwarding to upstream services.
 *
 * <h3>Use case:</h3>
 * Mobile and IoT clients often compress request payloads to reduce bandwidth.
 * Many backend services do not support compressed request bodies. This filter
 * bridges the gap by decompressing at the gateway layer.
 *
 * <h3>Zip bomb protection:</h3>
 * A running byte counter tracks the decompressed size. If the total exceeds
 * {@code maxDecompressedSize}, the stream is aborted with HTTP 413 Payload Too Large.
 *
 * <h3>Header cleanup:</h3>
 * <ul>
 *   <li>{@code Content-Encoding} is removed (when {@code removeEncoding=true})</li>
 *   <li>{@code Content-Length} is updated to the decompressed size (when {@code updateContentLength=true})</li>
 *   <li>{@code X-Original-Encoding: <encoding>} is injected for downstream observability</li>
 * </ul>
 *
 * <p>Filter type: {@code REQUEST_DECOMPRESS}
 */
@Slf4j
@Component
public class RequestDecompressGatewayFilterFactory
        extends AbstractGatewayFilterFactory<RequestDecompressGatewayFilterFactory.Config> {

    private static final Pattern COMMA_SPLIT = Pattern.compile("\\s*,\\s*");

    /** Supported encoding constants. */
    private static final String ENC_GZIP = "gzip";
    private static final String ENC_BR   = "br";
    private static final String ENC_ZSTD = "zstd";

    public RequestDecompressGatewayFilterFactory() {
        super(Config.class);
    }

    @Override
    public GatewayFilter apply(Config config) {
        Set<String> supported = parseSupportedEncodings(config.getSupportedEncodings());
        long maxBytes = parseMaxDecompressedSize(config.getMaxDecompressedSize());

        return (exchange, chain) -> {
            ServerHttpRequest request = exchange.getRequest();
            String encoding = request.getHeaders().getFirst(HttpHeaders.CONTENT_ENCODING);

            // No encoding or unsupported encoding → pass through unchanged
            if (encoding == null || encoding.isBlank()) {
                return chain.filter(exchange);
            }

            String normalised = encoding.trim().toLowerCase(Locale.ROOT);
            if (!supported.contains(normalised)) {
                log.debug("Unsupported Content-Encoding '{}' — passing through unchanged", encoding);
                return chain.filter(exchange);
            }

            log.debug("Decompressing request body: encoding={} maxDecompressedSize={}", normalised, maxBytes);

            // Read and decompress the full body (streaming with size limit)
            return DataBufferUtils.join(request.getBody())
                    .flatMap(originalBuffer -> {
                        byte[] compressed = new byte[originalBuffer.readableByteCount()];
                        originalBuffer.read(compressed);
                        DataBufferUtils.release(originalBuffer);

                        byte[] decompressed;
                        try {
                            decompressed = decompress(compressed, normalised, maxBytes);
                        } catch (DecompressionLimitExceededException e) {
                            log.warn("Zip bomb detected: decompressed body exceeds {} bytes for encoding '{}'",
                                    maxBytes, normalised);
                            return GatewayProblemResponse.status(HttpStatus.PAYLOAD_TOO_LARGE)
                                    .errorCode("DECOMPRESSED_SIZE_EXCEEDED")
                                    .detail("Decompressed request body exceeds the maximum allowed size of %s",
                                            config.getMaxDecompressedSize())
                                    .write(exchange);
                        } catch (IOException e) {
                            log.error("Failed to decompress request body with encoding '{}': {}",
                                    normalised, e.getMessage());
                            return GatewayProblemResponse.status(HttpStatus.BAD_REQUEST)
                                    .errorCode("DECOMPRESSION_FAILED")
                                    .detail("Failed to decompress request body: %s", e.getMessage())
                                    .write(exchange);
                        }

                        // Build decorated request with decompressed body
                        DataBufferFactory bufferFactory = exchange.getResponse().bufferFactory();
                        DataBuffer decompressedBuffer = bufferFactory.wrap(decompressed);

                        ServerHttpRequest decoratedRequest = new ServerHttpRequestDecorator(request) {
                            @Override
                            public Flux<DataBuffer> getBody() {
                                return Flux.just(decompressedBuffer);
                            }

                            @Override
                            public HttpHeaders getHeaders() {
                                HttpHeaders headers = new HttpHeaders();
                                headers.putAll(super.getHeaders());

                                // Remove Content-Encoding
                                if (config.isRemoveEncoding()) {
                                    headers.remove(HttpHeaders.CONTENT_ENCODING);
                                }

                                // Update Content-Length
                                if (config.isUpdateContentLength()) {
                                    headers.setContentLength(decompressed.length);
                                }

                                // Inject X-Original-Encoding
                                headers.set("X-Original-Encoding", normalised);

                                return headers;
                            }
                        };

                        ServerWebExchange mutatedExchange = exchange.mutate()
                                .request(decoratedRequest)
                                .build();

                        return chain.filter(mutatedExchange);
                    })
                    // If no body is present, pass through
                    .switchIfEmpty(chain.filter(exchange));
        };
    }

    /**
     * Decompresses the given bytes using the specified encoding.
     *
     * @param compressed the compressed bytes
     * @param encoding   the encoding type (gzip, br, zstd)
     * @param maxBytes   max decompressed size for zip bomb protection
     * @return the decompressed bytes
     * @throws IOException                        if decompression fails
     * @throws DecompressionLimitExceededException if decompressed size exceeds maxBytes
     */
    private byte[] decompress(byte[] compressed, String encoding, long maxBytes) throws IOException {
        return switch (encoding) {
            case ENC_GZIP -> decompressGzip(compressed, maxBytes);
            case ENC_BR   -> decompressBrotli(compressed, maxBytes);
            case ENC_ZSTD -> decompressZstd(compressed, maxBytes);
            default -> throw new IOException("Unsupported encoding: " + encoding);
        };
    }

    /**
     * Decompresses gzip data using {@link GZIPInputStream}.
     */
    private byte[] decompressGzip(byte[] compressed, long maxBytes) throws IOException {
        try (var bais = new ByteArrayInputStream(compressed);
             var gis = new GZIPInputStream(bais);
             var baos = new ByteArrayOutputStream()) {

            byte[] buf = new byte[8192];
            long total = 0;
            int n;
            while ((n = gis.read(buf)) != -1) {
                total += n;
                if (total > maxBytes) {
                    throw new DecompressionLimitExceededException(maxBytes);
                }
                baos.write(buf, 0, n);
            }
            return baos.toByteArray();
        }
    }

    /**
     * Decompresses Brotli data using {@code org.brotli.dec.BrotliInputStream}.
     */
    private byte[] decompressBrotli(byte[] compressed, long maxBytes) throws IOException {
        try (var bais = new ByteArrayInputStream(compressed);
             var bris = new org.brotli.dec.BrotliInputStream(bais);
             var baos = new ByteArrayOutputStream()) {

            byte[] buf = new byte[8192];
            long total = 0;
            int n;
            while ((n = bris.read(buf)) != -1) {
                total += n;
                if (total > maxBytes) {
                    throw new DecompressionLimitExceededException(maxBytes);
                }
                baos.write(buf, 0, n);
            }
            return baos.toByteArray();
        }
    }

    /**
     * Decompresses Zstandard data using {@code com.github.luben.zstd.ZstdInputStream}.
     */
    private byte[] decompressZstd(byte[] compressed, long maxBytes) throws IOException {
        try (var bais = new ByteArrayInputStream(compressed);
             var zis = new com.github.luben.zstd.ZstdInputStream(bais);
             var baos = new ByteArrayOutputStream()) {

            byte[] buf = new byte[8192];
            long total = 0;
            int n;
            while ((n = zis.read(buf)) != -1) {
                total += n;
                if (total > maxBytes) {
                    throw new DecompressionLimitExceededException(maxBytes);
                }
                baos.write(buf, 0, n);
            }
            return baos.toByteArray();
        }
    }

    // ─── Size parsing ─────────────────────────────────────────────────────────

    /**
     * Parses a human-readable size string like "10MB", "512KB", "1GB" into bytes.
     */
    static long parseMaxDecompressedSize(String size) {
        if (size == null || size.isBlank()) return 10L * 1024 * 1024; // 10 MB default

        String s = size.trim().toUpperCase(Locale.ROOT);
        try {
            if (s.endsWith("GB")) {
                return Long.parseLong(s.substring(0, s.length() - 2).trim()) * 1024L * 1024L * 1024L;
            } else if (s.endsWith("MB")) {
                return Long.parseLong(s.substring(0, s.length() - 2).trim()) * 1024L * 1024L;
            } else if (s.endsWith("KB")) {
                return Long.parseLong(s.substring(0, s.length() - 2).trim()) * 1024L;
            } else if (s.endsWith("B")) {
                return Long.parseLong(s.substring(0, s.length() - 1).trim());
            } else {
                return Long.parseLong(s); // plain bytes
            }
        } catch (NumberFormatException e) {
            log.warn("Could not parse maxDecompressedSize '{}' — using default 10MB", size);
            return 10L * 1024 * 1024;
        }
    }

    /**
     * Parses the comma-separated supported encodings into a set of normalised lowercase values.
     */
    static Set<String> parseSupportedEncodings(String encodings) {
        if (encodings == null || encodings.isBlank()) {
            return Set.of(ENC_GZIP, ENC_BR, ENC_ZSTD);
        }
        Set<String> result = new LinkedHashSet<>();
        for (String enc : COMMA_SPLIT.split(encodings)) {
            String normalised = enc.trim().toLowerCase(Locale.ROOT);
            if (!normalised.isEmpty()) {
                result.add(normalised);
            }
        }
        return result.isEmpty() ? Set.of(ENC_GZIP, ENC_BR, ENC_ZSTD) : Collections.unmodifiableSet(result);
    }

    // ─── Exception ────────────────────────────────────────────────────────────

    /**
     * Thrown when decompressed data exceeds the configured maximum size.
     */
    static class DecompressionLimitExceededException extends IOException {
        DecompressionLimitExceededException(long maxBytes) {
            super("Decompressed data exceeds maximum allowed size of " + maxBytes + " bytes");
        }
    }

    // ─── Config ───────────────────────────────────────────────────────────────

    @Data
    public static class Config {
        /**
         * Comma-separated Content-Encoding types to decompress.
         * Default: "gzip,br,zstd"
         */
        private String supportedEncodings = "gzip,br,zstd";

        /**
         * Maximum decompressed body size. Accepts suffixes: KB, MB, GB.
         * Default: "10MB"
         */
        private String maxDecompressedSize = "10MB";

        /**
         * Whether to remove the {@code Content-Encoding} header after decompression.
         * Default: true
         */
        private boolean removeEncoding = true;

        /**
         * Whether to update the {@code Content-Length} header to the decompressed size.
         * Default: true
         */
        private boolean updateContentLength = true;
    }
}

