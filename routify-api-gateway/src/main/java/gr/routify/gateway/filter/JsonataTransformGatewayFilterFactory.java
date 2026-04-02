package gr.routify.gateway.filter;

import com.dashjoin.jsonata.JException;
import com.dashjoin.jsonata.Jsonata;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.Data;
import lombok.extern.slf4j.Slf4j;
import org.springframework.cloud.gateway.filter.GatewayFilter;
import org.springframework.cloud.gateway.filter.factory.AbstractGatewayFilterFactory;
import org.springframework.core.io.buffer.DataBufferUtils;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.http.server.reactive.ServerHttpRequestDecorator;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Flux;

import java.nio.charset.StandardCharsets;

/**
 * Gateway filter factory that applies a
 * <a href="https://jsonata.org/">JSONata</a> expression to transform the
 * JSON request body before forwarding it to the upstream service.
 *
 * <p>Only JSON request bodies are transformed; all others pass through unchanged.
 * On a transformation error the original body is forwarded unchanged (fail-open),
 * and a warning is logged.
 *
 * <h3>Implementation note</h3>
 * Uses the <a href="https://github.com/dashjoin/jsonata-java">dashjoin/jsonata-java</a>
 * library ({@code com.dashjoin:jsonata}). A new {@link Jsonata} instance is created
 * per request because the library is not thread-safe for concurrent evaluation.
 *
 * <h3>Config params:</h3>
 * <ul>
 *   <li>{@code expression} — the JSONata expression to evaluate against the body (required)</li>
 *   <li>{@code phase}      — {@code REQUEST} (default) or {@code RESPONSE}. Currently only
 *       {@code REQUEST} phase is implemented.</li>
 * </ul>
 *
 * <p>Filter type: {@code BODY_JSONATA_TRANSFORM}
 */
@Slf4j
@Component
public class JsonataTransformGatewayFilterFactory
        extends AbstractGatewayFilterFactory<JsonataTransformGatewayFilterFactory.Config> {

    private final ObjectMapper objectMapper;

    public JsonataTransformGatewayFilterFactory(ObjectMapper objectMapper) {
        super(Config.class);
        this.objectMapper = objectMapper;
    }

    @Override
    public GatewayFilter apply(Config config) {
        if (config.getExpression() == null || config.getExpression().isBlank()) {
            log.error("JsonataTransform: 'expression' config is required but was not provided — filter will PASS all requests unchanged");
            return (exchange, chain) -> chain.filter(exchange);
        }

        // Validate the expression parses at startup
        try {
            Jsonata.jsonata(config.getExpression());
        } catch (Exception e) {
            log.error("JsonataTransform: failed to parse expression '{}': {}",
                    config.getExpression(), e.getMessage());
            return (exchange, chain) -> chain.filter(exchange);
        }

        final String expression = config.getExpression();

        return (exchange, chain) -> {
            MediaType contentType = exchange.getRequest().getHeaders().getContentType();
            if (!isJson(contentType)) {
                log.debug("JsonataTransform: non-JSON content-type '{}' — skipping", contentType);
                return chain.filter(exchange);
            }

            return DataBufferUtils.join(exchange.getRequest().getBody())
                    .flatMap(dataBuffer -> {
                        byte[] originalBytes = new byte[dataBuffer.readableByteCount()];
                        dataBuffer.read(originalBytes);
                        DataBufferUtils.release(dataBuffer);

                        String originalJson = new String(originalBytes, StandardCharsets.UTF_8);
                        byte[] resultBytes;

                        try {
                            // Parse body as a plain Java object (Map/List) for JSONata
                            Object input = objectMapper.readValue(originalJson, Object.class);

                            // Jsonata instances are not thread-safe; create per request
                            Jsonata expr = Jsonata.jsonata(expression);
                            Object output = expr.evaluate(input);

                            String transformed = objectMapper.writeValueAsString(output);
                            resultBytes = transformed.getBytes(StandardCharsets.UTF_8);
                            log.debug("JsonataTransform: body transformed ({} → {} bytes)",
                                    originalBytes.length, resultBytes.length);

                        } catch (JException e) {
                            log.warn("JsonataTransform: expression evaluation error — forwarding original body. Error: {}",
                                    e.getMessage());
                            resultBytes = originalBytes;
                        } catch (Exception e) {
                            log.error("JsonataTransform: unexpected error — forwarding original body. Error: {}",
                                    e.getMessage(), e);
                            resultBytes = originalBytes;
                        }

                        final byte[] finalBytes = resultBytes;
                        ServerHttpRequest mutated = new ServerHttpRequestDecorator(exchange.getRequest()) {
                            @Override
                            public Flux<org.springframework.core.io.buffer.DataBuffer> getBody() {
                                return Flux.just(exchange.getResponse().bufferFactory().wrap(finalBytes));
                            }

                            @Override
                            public HttpHeaders getHeaders() {
                                HttpHeaders headers = new HttpHeaders();
                                headers.putAll(super.getHeaders());
                                headers.setContentLength(finalBytes.length);
                                return headers;
                            }
                        };
                        return chain.filter(exchange.mutate().request(mutated).build());
                    })
                    .switchIfEmpty(chain.filter(exchange));
        };
    }

    private boolean isJson(MediaType contentType) {
        if (contentType == null) return false;
        return contentType.isCompatibleWith(MediaType.APPLICATION_JSON)
                || (contentType.getSubtype() != null && contentType.getSubtype().contains("json"));
    }


    @Data
    public static class Config {
        /** JSONata expression string. Required. */
        private String expression;
        /** REQUEST (default) or RESPONSE. Only REQUEST is currently implemented. */
        private String phase = "REQUEST";
    }
}

