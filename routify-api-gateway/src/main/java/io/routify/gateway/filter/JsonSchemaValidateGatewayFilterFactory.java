package io.routify.gateway.filter;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.networknt.schema.JsonSchema;
import com.networknt.schema.JsonSchemaFactory;
import com.networknt.schema.SpecVersion;
import com.networknt.schema.ValidationMessage;
import lombok.Data;
import lombok.extern.slf4j.Slf4j;
import org.springframework.cloud.gateway.filter.GatewayFilter;
import org.springframework.cloud.gateway.filter.factory.AbstractGatewayFilterFactory;
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
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Gateway filter factory that validates the JSON request body against a
 * <a href="https://json-schema.org/">JSON Schema</a> (Draft-07 by default)
 * before forwarding to the upstream service.
 *
 * <p>Only requests with a JSON {@code Content-Type} are validated; all others
 * pass through unchanged. Empty bodies also pass through.
 *
 * <p>On validation failure a {@code 400 Bad Request} RFC 9457 Problem Detail response
 * is returned with a {@code violations} array describing each constraint violation.
 *
 * <h3>Config params:</h3>
 * <ul>
 *   <li>{@code schema}      — JSON Schema as a JSON string (required)</li>
 *   <li>{@code specVersion} — Draft version: {@code V4}, {@code V6}, {@code V7} (default),
 *       {@code V201909}, {@code V202012}</li>
 * </ul>
 *
 * <p>Filter type: {@code VALIDATE_JSON_SCHEMA}
 */
@Slf4j
@Component
public class JsonSchemaValidateGatewayFilterFactory
        extends AbstractGatewayFilterFactory<JsonSchemaValidateGatewayFilterFactory.Config> {

    private final ObjectMapper objectMapper;

    public JsonSchemaValidateGatewayFilterFactory(ObjectMapper objectMapper) {
        super(Config.class);
        this.objectMapper = objectMapper;
    }

    @Override
    public GatewayFilter apply(Config config) {
        JsonSchema schema = buildSchema(config);
        if (schema == null) {
            log.error("JsonSchemaValidate: invalid or missing schema — filter will PASS all requests");
        }

        return (exchange, chain) -> {
            if (schema == null) {
                return chain.filter(exchange);
            }

            MediaType contentType = exchange.getRequest().getHeaders().getContentType();
            if (!isJson(contentType)) {
                log.debug("JsonSchemaValidate: non-JSON content-type '{}' — skipping validation", contentType);
                return chain.filter(exchange);
            }

            return DataBufferUtils.join(exchange.getRequest().getBody())
                    .flatMap(dataBuffer -> {
                        byte[] bytes = new byte[dataBuffer.readableByteCount()];
                        dataBuffer.read(bytes);
                        DataBufferUtils.release(dataBuffer);

                        String body = new String(bytes, StandardCharsets.UTF_8);

                        Set<ValidationMessage> violations;
                        try {
                            JsonNode node = objectMapper.readTree(body);
                            violations = schema.validate(node);
                        } catch (Exception e) {
                            log.debug("JsonSchemaValidate: failed to parse JSON body — rejecting: {}", e.getMessage());
                            return badRequest(exchange, "Body is not valid JSON: " + e.getMessage(), Set.of());
                        }

                        if (!violations.isEmpty()) {
                            String violationList = violations.stream()
                                    .map(ValidationMessage::getMessage)
                                    .collect(Collectors.joining("\", \"", "[\"", "\"]"));
                            log.debug("JsonSchemaValidate: {} violation(s) found", violations.size());
                            return badRequest(exchange, "Request body failed JSON Schema validation", violations);
                        }

                        // Re-wrap the consumed body so downstream filters/upstream can read it
                        ServerHttpRequest mutated = new ServerHttpRequestDecorator(exchange.getRequest()) {
                            @Override
                            public Flux<org.springframework.core.io.buffer.DataBuffer> getBody() {
                                return Flux.just(exchange.getResponse().bufferFactory().wrap(bytes));
                            }

                            @Override
                            public HttpHeaders getHeaders() {
                                HttpHeaders headers = new HttpHeaders();
                                headers.putAll(super.getHeaders());
                                headers.setContentLength(bytes.length);
                                return headers;
                            }
                        };
                        return chain.filter(exchange.mutate().request(mutated).build());
                    })
                    .switchIfEmpty(chain.filter(exchange)); // empty body — pass through
        };
    }

    private JsonSchema buildSchema(Config config) {
        if (config.getSchema() == null || config.getSchema().isBlank()) {
            log.error("JsonSchemaValidate: 'schema' config is required but was not provided");
            return null;
        }
        try {
            SpecVersion.VersionFlag version = parseSpecVersion(config.getSpecVersion());
            JsonSchemaFactory factory = JsonSchemaFactory.getInstance(version);
            return factory.getSchema(config.getSchema());
        } catch (Exception e) {
            log.error("JsonSchemaValidate: failed to parse schema: {}", e.getMessage(), e);
            return null;
        }
    }

    private SpecVersion.VersionFlag parseSpecVersion(String v) {
        if (v == null) return SpecVersion.VersionFlag.V7;
        return switch (v.toUpperCase()) {
            case "V4"      -> SpecVersion.VersionFlag.V4;
            case "V6"      -> SpecVersion.VersionFlag.V6;
            case "V201909" -> SpecVersion.VersionFlag.V201909;
            case "V202012" -> SpecVersion.VersionFlag.V202012;
            default        -> SpecVersion.VersionFlag.V7;
        };
    }

    private boolean isJson(MediaType contentType) {
        if (contentType == null) return false;
        return contentType.isCompatibleWith(MediaType.APPLICATION_JSON)
                || (contentType.getSubtype() != null && contentType.getSubtype().contains("json"));
    }

    private Mono<Void> badRequest(ServerWebExchange exchange, String detail, Set<ValidationMessage> violations) {
        ServerHttpResponse resp = exchange.getResponse();
        resp.setStatusCode(HttpStatus.BAD_REQUEST);
        resp.getHeaders().set(HttpHeaders.CONTENT_TYPE, "application/problem+json");
        String violationsJson = violations.isEmpty() ? "[]"
                : violations.stream()
                        .map(v -> "\"" + v.getMessage().replace("\"", "\\\"") + "\"")
                        .collect(Collectors.joining(",", "[", "]"));
        String body = """
                {"type":"about:blank","title":"Bad Request","status":400,\
                "detail":"%s","violations":%s}""".formatted(
                detail.replace("\"", "\\\""), violationsJson);
        return resp.writeWith(Mono.just(resp.bufferFactory().wrap(body.getBytes(StandardCharsets.UTF_8))));
    }

    @Data
    public static class Config {
        /** JSON Schema as a JSON string. Required. */
        private String schema;
        /** JSON Schema spec version: V4, V6, V7 (default), V201909, V202012. */
        private String specVersion = "V7";
    }
}

