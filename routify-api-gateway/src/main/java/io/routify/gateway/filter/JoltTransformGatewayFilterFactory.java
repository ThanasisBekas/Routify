package io.routify.gateway.filter;

import com.bazaarvoice.jolt.Chainr;
import com.bazaarvoice.jolt.JsonUtils;
import io.routify.gateway.filter.shared.GatewayProblemResponse;
import lombok.extern.slf4j.Slf4j;
import org.reactivestreams.Publisher;
import org.springframework.cloud.gateway.filter.GatewayFilter;
import org.springframework.cloud.gateway.filter.factory.AbstractGatewayFilterFactory;
import org.springframework.core.io.buffer.DataBuffer;
import org.springframework.core.io.buffer.DataBufferUtils;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.http.server.reactive.ServerHttpRequestDecorator;
import org.springframework.http.server.reactive.ServerHttpResponse;
import org.springframework.http.server.reactive.ServerHttpResponseDecorator;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.nio.charset.StandardCharsets;
import java.util.List;

/**
 * Gateway filter factory that performs a Jolt JSON-to-JSON transformation on the
 * request body, response body, or both, before forwarding/returning.
 *
 * <p>The filter reads the full body, applies the Jolt spec, and replaces the body
 * with the transformed JSON. Only bodies with a JSON content-type are transformed;
 * all others pass through unchanged.
 *
 * <h3>Config params:</h3>
 * <ul>
 *   <li>{@code spec} — the Jolt spec as a JSON string (required for REQUEST/BOTH).
 *       Must be a valid Chainr spec array, e.g. {@code [{"operation":"shift","spec":{...}}]}</li>
 *   <li>{@code responseSpec} — the Jolt spec for response transformation (required for BOTH;
 *       optional for RESPONSE — falls back to {@code spec} if not set).</li>
 *   <li>{@code phase} — {@code REQUEST} (default), {@code RESPONSE}, or {@code BOTH}.</li>
 *   <li>{@code maxBodySize} — maximum response body size in bytes to transform (default 1 MB).
 *       Bodies exceeding this limit pass through unchanged with a warning log.</li>
 * </ul>
 *
 * <h3>Example filter config (stored in the route's filter config JSON):</h3>
 * <pre>{@code
 * {
 *   "spec": "[{\"operation\":\"shift\",\"spec\":{\"id\":\"userId\",\"name\":\"fullName\"}}]",
 *   "phase": "BOTH",
 *   "responseSpec": "[{\"operation\":\"shift\",\"spec\":{\"result\":\"data\"}}]",
 *   "maxBodySize": 1048576
 * }
 * }</pre>
 */
@Slf4j
@Component
public class JoltTransformGatewayFilterFactory
        extends AbstractGatewayFilterFactory<JoltTransformGatewayFilterFactory.Config> {

    /** Default max body size for response transformation: 1 MB. */
    private static final int DEFAULT_MAX_BODY_SIZE = 1_048_576;

    public JoltTransformGatewayFilterFactory() {
        super(Config.class);
    }

    @Override
    public GatewayFilter apply(Config config) {
        String phase = config.getPhase() != null ? config.getPhase().toUpperCase() : "REQUEST";

        // Build Chainr instances once at route-load time
        Chainr requestChainr = buildChainr(config.getSpec(), "spec");
        Chainr responseChainr = resolveResponseChainr(config, phase);

        int maxBodySize = config.getMaxBodySize() > 0 ? config.getMaxBodySize() : DEFAULT_MAX_BODY_SIZE;

        return (exchange, chain) -> switch (phase) {
            case "REQUEST" -> {
                if (requestChainr == null) {
                    log.warn("JoltTransform: no valid spec configured — passing through unchanged");
                    yield chain.filter(exchange);
                }
                yield applyRequestTransform(exchange, chain, requestChainr);
            }
            case "RESPONSE" -> {
                Chainr effectiveChainr = responseChainr != null ? responseChainr : requestChainr;
                if (effectiveChainr == null) {
                    log.warn("JoltTransform: no valid spec configured for RESPONSE phase — passing through unchanged");
                    yield chain.filter(exchange);
                }
                yield applyResponseTransform(exchange, chain, effectiveChainr, maxBodySize);
            }
            case "BOTH" -> {
                if (requestChainr == null) {
                    log.warn("JoltTransform: no valid spec configured for REQUEST phase — passing through unchanged");
                    yield chain.filter(exchange);
                }
                if (responseChainr == null) {
                    log.warn("JoltTransform: no valid responseSpec configured for BOTH phase — "
                            + "only request transformation will be applied");
                    yield applyRequestTransform(exchange, chain, requestChainr);
                }
                yield applyBothTransform(exchange, chain, requestChainr, responseChainr, maxBodySize);
            }
            default -> {
                log.warn("JoltTransform: unknown phase '{}' — defaulting to REQUEST", phase);
                if (requestChainr == null) {
                    yield chain.filter(exchange);
                }
                yield applyRequestTransform(exchange, chain, requestChainr);
            }
        };
    }

    // ─── Request-phase transformation (existing behaviour) ─────────────────────

    private Mono<Void> applyRequestTransform(ServerWebExchange exchange,
                                              org.springframework.cloud.gateway.filter.GatewayFilterChain chain,
                                              Chainr chainr) {
        MediaType contentType = exchange.getRequest().getHeaders().getContentType();
        if (!isJson(contentType)) {
            log.debug("JoltTransform: request content-type '{}' is not JSON — skipping transformation", contentType);
            return chain.filter(exchange);
        }

        return DataBufferUtils.join(exchange.getRequest().getBody())
                .flatMap(dataBuffer -> {
                    byte[] originalBytes = new byte[dataBuffer.readableByteCount()];
                    dataBuffer.read(originalBytes);
                    DataBufferUtils.release(dataBuffer);

                    String originalJson = new String(originalBytes, StandardCharsets.UTF_8);
                    String transformedJson;
                    try {
                        Object input = JsonUtils.jsonToObject(originalJson);
                        Object output = chainr.transform(input);
                        transformedJson = JsonUtils.toJsonString(output);
                        log.debug("JoltTransform: request body transformed ({} -> {} bytes)",
                                originalBytes.length, transformedJson.length());
                    } catch (Exception e) {
                        log.error("JoltTransform: request transformation failed — passing original body. Error: {}",
                                e.getMessage(), e);
                        transformedJson = originalJson;
                    }

                    byte[] transformedBytes = transformedJson.getBytes(StandardCharsets.UTF_8);
                    DataBuffer transformedBuffer = exchange.getResponse()
                            .bufferFactory()
                            .wrap(transformedBytes);

                    ServerHttpRequest mutatedRequest = new ServerHttpRequestDecorator(exchange.getRequest()) {
                        @Override
                        public Flux<DataBuffer> getBody() {
                            return Flux.just(transformedBuffer);
                        }

                        @Override
                        public HttpHeaders getHeaders() {
                            HttpHeaders headers = new HttpHeaders();
                            headers.putAll(super.getHeaders());
                            headers.setContentLength(transformedBytes.length);
                            return headers;
                        }
                    };

                    return chain.filter(exchange.mutate().request(mutatedRequest).build());
                })
                .switchIfEmpty(chain.filter(exchange));
    }

    // ─── Response-phase transformation ─────────────────────────────────────────

    private Mono<Void> applyResponseTransform(ServerWebExchange exchange,
                                               org.springframework.cloud.gateway.filter.GatewayFilterChain chain,
                                               Chainr chainr,
                                               int maxBodySize) {
        ServerHttpResponseDecorator decoratedResponse = createResponseDecorator(
                exchange, exchange.getResponse(), chainr, maxBodySize);

        return chain.filter(exchange.mutate().response(decoratedResponse).build());
    }

    // ─── BOTH phase: request transform + response transform ────────────────────

    private Mono<Void> applyBothTransform(ServerWebExchange exchange,
                                           org.springframework.cloud.gateway.filter.GatewayFilterChain chain,
                                           Chainr requestChainr,
                                           Chainr responseChainr,
                                           int maxBodySize) {
        MediaType contentType = exchange.getRequest().getHeaders().getContentType();
        if (!isJson(contentType)) {
            log.debug("JoltTransform BOTH: request content-type '{}' is not JSON — "
                    + "skipping request transformation, still applying response transformation", contentType);
            return applyResponseTransform(exchange, chain, responseChainr, maxBodySize);
        }

        return DataBufferUtils.join(exchange.getRequest().getBody())
                .flatMap(dataBuffer -> {
                    byte[] originalBytes = new byte[dataBuffer.readableByteCount()];
                    dataBuffer.read(originalBytes);
                    DataBufferUtils.release(dataBuffer);

                    String originalJson = new String(originalBytes, StandardCharsets.UTF_8);
                    String transformedJson;
                    try {
                        Object input = JsonUtils.jsonToObject(originalJson);
                        Object output = requestChainr.transform(input);
                        transformedJson = JsonUtils.toJsonString(output);
                        log.debug("JoltTransform BOTH: request body transformed ({} -> {} bytes)",
                                originalBytes.length, transformedJson.length());
                    } catch (Exception e) {
                        log.error("JoltTransform BOTH: request transformation failed — passing original body. Error: {}",
                                e.getMessage(), e);
                        transformedJson = originalJson;
                    }

                    byte[] transformedBytes = transformedJson.getBytes(StandardCharsets.UTF_8);
                    DataBuffer transformedBuffer = exchange.getResponse()
                            .bufferFactory()
                            .wrap(transformedBytes);

                    ServerHttpRequest mutatedRequest = new ServerHttpRequestDecorator(exchange.getRequest()) {
                        @Override
                        public Flux<DataBuffer> getBody() {
                            return Flux.just(transformedBuffer);
                        }

                        @Override
                        public HttpHeaders getHeaders() {
                            HttpHeaders headers = new HttpHeaders();
                            headers.putAll(super.getHeaders());
                            headers.setContentLength(transformedBytes.length);
                            return headers;
                        }
                    };

                    ServerWebExchange mutatedExchange = exchange.mutate().request(mutatedRequest).build();

                    ServerHttpResponseDecorator decoratedResponse = createResponseDecorator(
                            mutatedExchange, mutatedExchange.getResponse(), responseChainr, maxBodySize);

                    return chain.filter(mutatedExchange.mutate().response(decoratedResponse).build());
                })
                .switchIfEmpty(Mono.defer(() ->
                        applyResponseTransform(exchange, chain, responseChainr, maxBodySize)));
    }

    // ─── Response decorator factory ────────────────────────────────────────────

    private ServerHttpResponseDecorator createResponseDecorator(ServerWebExchange exchange,
                                                                 ServerHttpResponse originalResponse,
                                                                 Chainr chainr,
                                                                 int maxBodySize) {
        return new ServerHttpResponseDecorator(originalResponse) {

            // Mutable header copy — allows Content-Length updates even when the
            // delegate response returns ReadOnlyHttpHeaders (common after mutate()).
            private final HttpHeaders mutableHeaders = new HttpHeaders();
            {
                mutableHeaders.putAll(originalResponse.getHeaders());
            }

            @Override
            public HttpHeaders getHeaders() {
                return mutableHeaders;
            }

            @Override
            public Mono<Void> writeWith(Publisher<? extends DataBuffer> body) {
                MediaType responseContentType = getHeaders().getContentType();
                if (!isJson(responseContentType)) {
                    log.debug("JoltTransform: response content-type '{}' is not JSON — passing through unchanged",
                            responseContentType);
                    return super.writeWith(body);
                }

                return DataBufferUtils.join(Flux.from(body))
                        .flatMap(dataBuffer -> {
                            byte[] responseBytes = new byte[dataBuffer.readableByteCount()];
                            dataBuffer.read(responseBytes);
                            DataBufferUtils.release(dataBuffer);

                            // Check maxBodySize limit
                            if (responseBytes.length > maxBodySize) {
                                log.warn("JoltTransform: response body ({} bytes) exceeds maxBodySize ({} bytes) "
                                                + "for route — passing through unchanged",
                                        responseBytes.length, maxBodySize);
                                DataBuffer originalBuffer = getDelegate().bufferFactory().wrap(responseBytes);
                                return super.writeWith(Mono.just(originalBuffer));
                            }

                            // Handle empty body
                            if (responseBytes.length == 0) {
                                log.debug("JoltTransform: empty response body — passing through unchanged");
                                DataBuffer emptyBuffer = getDelegate().bufferFactory().wrap(responseBytes);
                                return super.writeWith(Mono.just(emptyBuffer));
                            }

                            String responseJson = new String(responseBytes, StandardCharsets.UTF_8);
                            try {
                                Object input = JsonUtils.jsonToObject(responseJson);
                                Object output = chainr.transform(input);
                                String transformedJson = JsonUtils.toJsonString(output);
                                byte[] transformedBytes = transformedJson.getBytes(StandardCharsets.UTF_8);

                                log.debug("JoltTransform: response body transformed ({} -> {} bytes)",
                                        responseBytes.length, transformedBytes.length);

                                // Update Content-Length
                                getHeaders().setContentLength(transformedBytes.length);

                                DataBuffer transformedBuffer = getDelegate().bufferFactory().wrap(transformedBytes);
                                return super.writeWith(Mono.just(transformedBuffer));
                            } catch (Exception e) {
                                log.error("JoltTransform: response transformation failed — returning 502. Error: {}",
                                        e.getMessage(), e);
                                return GatewayProblemResponse.status(HttpStatus.BAD_GATEWAY)
                                        .errorCode("JOLT_TRANSFORM_FAILED")
                                        .detail("Response body transformation failed: %s", e.getMessage())
                                        .write(exchange);
                            }
                        });
            }
        };
    }

    // ─── Helpers ───────────────────────────────────────────────────────────────

    private Chainr buildChainr(String spec, String fieldName) {
        if (spec == null || spec.isBlank()) {
            log.debug("JoltTransform: '{}' config is empty or null", fieldName);
            return null;
        }
        try {
            List<Object> specList = JsonUtils.jsonToList(spec);
            return Chainr.fromSpec(specList);
        } catch (Exception e) {
            log.error("JoltTransform: failed to parse Jolt {} '{}': {}", fieldName, spec, e.getMessage(), e);
            return null;
        }
    }

    private Chainr resolveResponseChainr(Config config, String phase) {
        if ("RESPONSE".equals(phase)) {
            // For RESPONSE phase: use responseSpec if set, otherwise fall back to spec
            if (config.getResponseSpec() != null && !config.getResponseSpec().isBlank()) {
                return buildChainr(config.getResponseSpec(), "responseSpec");
            }
            return null; // will fall back to requestChainr in apply()
        }
        if ("BOTH".equals(phase)) {
            // For BOTH phase: responseSpec is required
            return buildChainr(config.getResponseSpec(), "responseSpec");
        }
        return null;
    }

    private boolean isJson(MediaType contentType) {
        if (contentType == null) return false;
        return contentType.isCompatibleWith(MediaType.APPLICATION_JSON)
                || (contentType.getSubtype() != null && contentType.getSubtype().contains("json"));
    }

    // ─── Config ───────────────────────────────────────────────────────────────

    /**
     * Configuration for the JoltTransform gateway filter.
     */
    public static class Config {

        /**
         * Jolt Chainr spec as a JSON array string (used for REQUEST phase and
         * as fallback for RESPONSE phase when {@code responseSpec} is not set).
         * Example: {@code [{"operation":"shift","spec":{"id":"userId"}}]}
         */
        private String spec;

        /**
         * Which phase to apply the transformation: REQUEST (default), RESPONSE, or BOTH.
         */
        private String phase = "REQUEST";

        /**
         * Jolt Chainr spec for response transformation. Used when {@code phase=BOTH}
         * or optionally when {@code phase=RESPONSE} (overrides {@code spec} for responses).
         */
        private String responseSpec;

        /**
         * Maximum response body size in bytes to transform.
         * Bodies exceeding this limit pass through unchanged with a warning log.
         * Default: 1 MB (1048576 bytes).
         */
        private int maxBodySize = DEFAULT_MAX_BODY_SIZE;

        public String getSpec()                      { return spec; }
        public void setSpec(String spec)             { this.spec = spec; }
        public String getPhase()                     { return phase; }
        public void setPhase(String phase)           { this.phase = phase; }
        public String getResponseSpec()              { return responseSpec; }
        public void setResponseSpec(String responseSpec) { this.responseSpec = responseSpec; }
        public int getMaxBodySize()                  { return maxBodySize; }
        public void setMaxBodySize(int maxBodySize)  { this.maxBodySize = maxBodySize; }
    }
}

