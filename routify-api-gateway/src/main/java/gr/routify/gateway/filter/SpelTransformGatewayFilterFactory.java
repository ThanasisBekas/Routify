package gr.routify.gateway.filter;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.Data;
import lombok.extern.slf4j.Slf4j;
import org.springframework.cloud.gateway.filter.GatewayFilter;
import org.springframework.cloud.gateway.filter.factory.AbstractGatewayFilterFactory;
import org.springframework.core.io.buffer.DataBufferUtils;
import org.springframework.expression.EvaluationException;
import org.springframework.expression.Expression;
import org.springframework.expression.ExpressionParser;
import org.springframework.expression.ParseException;
import org.springframework.expression.spel.standard.SpelExpressionParser;
import org.springframework.expression.spel.support.StandardEvaluationContext;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.http.server.reactive.ServerHttpRequestDecorator;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Flux;

import java.nio.charset.StandardCharsets;
import java.util.Map;

/**
 * Gateway filter factory that applies a Spring Expression Language (SpEL) expression
 * to transform the JSON request body before forwarding it to the upstream service.
 *
 * <h3>How it works</h3>
 * <ol>
 *   <li>The JSON body is deserialized into a {@code Map<String, Object>} which becomes
 *       the SpEL root object.</li>
 *   <li>The configured {@code expression} is evaluated against the map. The result
 *       replaces the entire body (if the expression returns a Map) or is assigned to
 *       the field named by {@code targetField} inside the body map.</li>
 *   <li>The modified map is re-serialized to JSON and the body is replaced.</li>
 * </ol>
 *
 * <p>On evaluation error the original body is forwarded unchanged (fail-open).
 *
 * <h3>Config params:</h3>
 * <ul>
 *   <li>{@code expression}  — SpEL expression evaluated against the parsed body map (required)</li>
 *   <li>{@code targetField} — if provided, the expression result is stored under this key in the
 *       body map instead of replacing the whole body</li>
 *   <li>{@code phase}       — {@code REQUEST} (default); RESPONSE phase not yet implemented</li>
 * </ul>
 *
 * <h3>Example — uppercase the "name" field:</h3>
 * <pre>{@code
 * { "expression": "#root['name'].toUpperCase()", "targetField": "name" }
 * }</pre>
 *
 * <p>Filter type: {@code BODY_SPEL_TRANSFORM}
 */
@Slf4j
@Component
public class SpelTransformGatewayFilterFactory
        extends AbstractGatewayFilterFactory<SpelTransformGatewayFilterFactory.Config> {

    private static final ExpressionParser PARSER = new SpelExpressionParser();
    private static final TypeReference<Map<String, Object>> MAP_TYPE = new TypeReference<>() {};

    private final ObjectMapper objectMapper;

    public SpelTransformGatewayFilterFactory(ObjectMapper objectMapper) {
        super(Config.class);
        this.objectMapper = objectMapper;
    }

    @Override
    public GatewayFilter apply(Config config) {
        if (config.getExpression() == null || config.getExpression().isBlank()) {
            log.error("SpelTransform: 'expression' config is required but was not provided — filter disabled");
            return (exchange, chain) -> chain.filter(exchange);
        }

        Expression compiledExpr;
        try {
            compiledExpr = PARSER.parseExpression(config.getExpression());
        } catch (ParseException e) {
            log.error("SpelTransform: failed to parse SpEL expression '{}': {}", config.getExpression(), e.getMessage());
            return (exchange, chain) -> chain.filter(exchange);
        }

        return (exchange, chain) -> {
            MediaType contentType = exchange.getRequest().getHeaders().getContentType();
            if (!isJson(contentType)) {
                log.debug("SpelTransform: non-JSON content-type '{}' — skipping", contentType);
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
                            Map<String, Object> bodyMap = objectMapper.readValue(originalJson, MAP_TYPE);

                            StandardEvaluationContext ctx = new StandardEvaluationContext(bodyMap);
                            Object exprResult = compiledExpr.getValue(ctx);

                            if (config.getTargetField() != null && !config.getTargetField().isBlank()) {
                                // Assign result to a specific field
                                bodyMap.put(config.getTargetField(), exprResult);
                            } else if (exprResult instanceof Map) {
                                // Replace whole body with expression result
                                @SuppressWarnings("unchecked")
                                Map<String, Object> resultMap = (Map<String, Object>) exprResult;
                                bodyMap = resultMap;
                            } else {
                                log.warn("SpelTransform: expression returned {} (not a Map) and no targetField set — wrapping in 'result' key",
                                        exprResult != null ? exprResult.getClass().getSimpleName() : "null");
                                bodyMap.put("result", exprResult);
                            }

                            String transformed = objectMapper.writeValueAsString(bodyMap);
                            resultBytes = transformed.getBytes(StandardCharsets.UTF_8);
                            log.debug("SpelTransform: transformed body ({} → {} bytes)",
                                    originalBytes.length, resultBytes.length);

                        } catch (EvaluationException e) {
                            log.warn("SpelTransform: expression evaluation failed — forwarding original body. Error: {}",
                                    e.getMessage());
                            resultBytes = originalBytes;
                        } catch (Exception e) {
                            log.error("SpelTransform: unexpected error — forwarding original body. Error: {}",
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
                || contentType.getSubtype().contains("json");
    }

    @Data
    public static class Config {
        /** SpEL expression evaluated against the parsed body map. Required. */
        private String expression;
        /** If set, the expression result is stored under this key in the body map. */
        private String targetField;
        /** REQUEST (default) or RESPONSE. Only REQUEST is currently implemented. */
        private String phase = "REQUEST";
    }
}

