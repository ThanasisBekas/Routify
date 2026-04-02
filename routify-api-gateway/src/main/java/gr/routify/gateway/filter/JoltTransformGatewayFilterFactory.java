package gr.routify.gateway.filter;

import com.bazaarvoice.jolt.Chainr;
import com.bazaarvoice.jolt.JsonUtils;
import lombok.extern.slf4j.Slf4j;
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
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.nio.charset.StandardCharsets;
import java.util.List;

/**
 * Gateway filter factory that performs a Jolt JSON-to-JSON transformation on the
 * request body before forwarding it to the upstream service.
 *
 * <p>The filter reads the full request body, applies the Jolt spec, and replaces the
 * body with the transformed JSON. Only requests with a JSON content-type are
 * transformed; all others pass through unchanged.
 *
 * <h3>Config params:</h3>
 * <ul>
 *   <li>{@code spec} — the Jolt spec as a JSON string (required). Must be a valid
 *       Chainr spec array, e.g. {@code [{"operation":"shift","spec":{...}}]}</li>
 *   <li>{@code phase} — {@code REQUEST} (default) or {@code RESPONSE}. Currently
 *       only {@code REQUEST} transformation is supported.</li>
 * </ul>
 *
 * <h3>Example filter config (stored in the route's filter config JSON):</h3>
 * <pre>{@code
 * {
 *   "spec": "[{\"operation\":\"shift\",\"spec\":{\"id\":\"userId\",\"name\":\"fullName\"}}]"
 * }
 * }</pre>
 */
@Slf4j
@Component
public class JoltTransformGatewayFilterFactory
        extends AbstractGatewayFilterFactory<JoltTransformGatewayFilterFactory.Config> {

    public JoltTransformGatewayFilterFactory() {
        super(Config.class);
    }

    @Override
    public GatewayFilter apply(Config config) {
        // Build the Chainr once at route-load time (not per-request) for performance
        Chainr chainr = buildChainr(config);

        return (exchange, chain) -> {
            if (chainr == null) {
                log.warn("JoltTransform: no valid spec configured — passing through unchanged");
                return chain.filter(exchange);
            }

            // Only transform JSON bodies
            MediaType contentType = exchange.getRequest().getHeaders().getContentType();
            if (!isJson(contentType)) {
                log.debug("JoltTransform: content-type '{}' is not JSON — skipping transformation", contentType);
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
                            log.debug("JoltTransform: body transformed successfully ({} -> {} bytes)",
                                    originalBytes.length, transformedJson.length());
                        } catch (Exception e) {
                            log.error("JoltTransform: transformation failed — passing original body. Error: {}",
                                    e.getMessage(), e);
                            transformedJson = originalJson; // fail-open: forward original body
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
                                // Update Content-Length to reflect the new body size
                                headers.setContentLength(transformedBytes.length);
                                return headers;
                            }
                        };

                        return chain.filter(exchange.mutate().request(mutatedRequest).build());
                    })
                    .switchIfEmpty(chain.filter(exchange)); // empty body — pass through
        };
    }

    private Chainr buildChainr(Config config) {
        if (config.getSpec() == null || config.getSpec().isBlank()) {
            log.error("JoltTransform: 'spec' config is required but was not provided");
            return null;
        }
        try {
            List<Object> specList = JsonUtils.jsonToList(config.getSpec());
            return Chainr.fromSpec(specList);
        } catch (Exception e) {
            log.error("JoltTransform: failed to parse Jolt spec '{}': {}", config.getSpec(), e.getMessage(), e);
            return null;
        }
    }

    private boolean isJson(MediaType contentType) {
        if (contentType == null) return false;
        return contentType.isCompatibleWith(MediaType.APPLICATION_JSON)
                || (contentType.getSubtype() != null && contentType.getSubtype().contains("json"));
    }

    // ─── Error response helper ────────────────────────────────────────────────

    private Mono<Void> badRequest(ServerWebExchange exchange, String detail) {
        ServerHttpResponse response = exchange.getResponse();
        response.setStatusCode(HttpStatus.BAD_REQUEST);
        response.getHeaders().set(HttpHeaders.CONTENT_TYPE, "application/problem+json");
        String body = """
                {"type":"about:blank","title":"Bad Request","status":400,"detail":"%s"}
                """.formatted(detail).strip();
        return response.writeWith(Mono.just(response.bufferFactory().wrap(body.getBytes(StandardCharsets.UTF_8))));
    }

    // ─── Config ───────────────────────────────────────────────────────────────

    /**
     * Configuration for the JoltTransform gateway filter.
     */
    public static class Config {

        /**
         * Jolt Chainr spec as a JSON array string.
         * Example: {@code [{"operation":"shift","spec":{"id":"userId"}}]}
         */
        private String spec;

        /**
         * Which phase to apply the transformation: REQUEST (default) or RESPONSE.
         * Currently only REQUEST transformation is implemented.
         */
        private String phase = "REQUEST";

        public String getSpec()             { return spec; }
        public void setSpec(String spec)    { this.spec = spec; }
        public String getPhase()            { return phase; }
        public void setPhase(String phase)  { this.phase = phase; }
    }
}

