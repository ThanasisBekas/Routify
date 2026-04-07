package io.routify.gateway.filter.devex;

import io.routify.common.web.RoutifyHeaders;
import lombok.Data;
import lombok.extern.slf4j.Slf4j;
import org.springframework.cloud.gateway.filter.GatewayFilter;
import org.springframework.cloud.gateway.filter.factory.AbstractGatewayFilterFactory;
import org.springframework.core.io.buffer.DataBuffer;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.http.server.reactive.ServerHttpResponse;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Returns a configurable static response without forwarding the request to any
 * upstream service. Enables API stubbing, contract-first development, and
 * maintenance mode pages directly at the gateway level.
 *
 * <h3>Template interpolation:</h3>
 * The {@code body} config supports the following placeholders:
 * <ul>
 *   <li>{@code ${method}} — HTTP method (GET, POST, etc.)</li>
 *   <li>{@code ${path}} — request path</li>
 *   <li>{@code ${header:X-Foo}} — value of request header {@code X-Foo}</li>
 *   <li>{@code ${param:id}} — value of query parameter {@code id}</li>
 *   <li>{@code ${timestamp}} — current ISO-8601 timestamp</li>
 *   <li>{@code ${correlationId}} — correlation ID from {@code X-Correlation-Id}</li>
 * </ul>
 * Unknown placeholders resolve to an empty string.
 *
 * <h3>Conditional activation:</h3>
 * When {@code conditionHeader} is configured, the mock is only returned if the
 * request contains that header. If absent, the request passes through to upstream.
 *
 * <h3>Maintenance mode pattern:</h3>
 * <pre>
 * filterType: MOCK_RESPONSE
 * config:
 *   status: 503
 *   contentType: application/json
 *   body: '{"type":"about:blank","title":"Service Unavailable","status":503,"detail":"Under maintenance."}'
 *   headers:
 *     Retry-After: "3600"
 * </pre>
 *
 * <p>Filter type: {@code MOCK_RESPONSE}
 */
@Slf4j
@Component
public class MockResponseGatewayFilterFactory
        extends AbstractGatewayFilterFactory<MockResponseGatewayFilterFactory.Config> {

    /**
     * Pattern matching template placeholders: {@code ${...}}.
     * Captures the content between the braces.
     */
    private static final Pattern PLACEHOLDER = Pattern.compile("\\$\\{([^}]+)}");

    public MockResponseGatewayFilterFactory() {
        super(Config.class);
    }

    @Override
    public GatewayFilter apply(Config config) {
        int statusCode = config.getStatus();
        String contentType = config.getContentType();
        String bodyTemplate = config.getBody();
        long delayMs = config.getDelay();
        String conditionHeader = config.getConditionHeader();

        // Pre-parse headers config
        Map<String, String> extraHeaders = config.getParsedHeaders();

        return (exchange, chain) -> {
            // ─── Conditional activation ─────────────────────────────────────
            if (conditionHeader != null && !conditionHeader.isBlank()) {
                String headerValue = exchange.getRequest().getHeaders().getFirst(conditionHeader);
                if (headerValue == null) {
                    // Condition not met → pass through to upstream
                    log.debug("MOCK_RESPONSE: condition header '{}' absent — passing through", conditionHeader);
                    return chain.filter(exchange);
                }
            }

            // ─── Build mock response ────────────────────────────────────────
            Mono<Void> responseMono = Mono.defer(() -> {
                String resolvedBody = resolveTemplate(bodyTemplate, exchange);
                return writeMockResponse(exchange, statusCode, contentType, resolvedBody, extraHeaders);
            });

            // ─── Delay simulation ───────────────────────────────────────────
            if (delayMs > 0) {
                responseMono = Mono.delay(Duration.ofMillis(delayMs)).then(responseMono);
            }

            return responseMono;
        };
    }

    /**
     * Writes the mock response to the exchange. Does NOT call {@code chain.filter()}
     * — the request is short-circuited here.
     */
    private Mono<Void> writeMockResponse(ServerWebExchange exchange,
                                          int statusCode,
                                          String contentType,
                                          String body,
                                          Map<String, String> extraHeaders) {
        ServerHttpResponse response = exchange.getResponse();

        // Status code
        HttpStatus status = HttpStatus.resolve(statusCode);
        if (status != null) {
            response.setStatusCode(status);
        } else {
            response.setRawStatusCode(statusCode);
        }

        // Content-Type
        response.getHeaders().set(HttpHeaders.CONTENT_TYPE, contentType);

        // Extra headers
        extraHeaders.forEach((name, value) -> response.getHeaders().set(name, value));

        // Body
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        DataBuffer buffer = response.bufferFactory().wrap(bytes);
        return response.writeWith(Mono.just(buffer));
    }

    /**
     * Resolves template placeholders in the body string against the current request.
     *
     * <p>Supported placeholders:
     * <ul>
     *   <li>{@code ${method}} → request HTTP method</li>
     *   <li>{@code ${path}} → request path</li>
     *   <li>{@code ${timestamp}} → ISO-8601 instant</li>
     *   <li>{@code ${correlationId}} → X-Correlation-Id header value</li>
     *   <li>{@code ${header:Name}} → request header value</li>
     *   <li>{@code ${param:name}} → query parameter value</li>
     * </ul>
     * Unknown placeholders resolve to empty string.
     */
    static String resolveTemplate(String template, ServerWebExchange exchange) {
        if (template == null || template.isEmpty() || !template.contains("${")) {
            return template;
        }

        ServerHttpRequest request = exchange.getRequest();
        Matcher matcher = PLACEHOLDER.matcher(template);
        StringBuilder sb = new StringBuilder();

        while (matcher.find()) {
            String placeholder = matcher.group(1);
            String replacement = resolvePlaceholder(placeholder, request);
            // Escape replacement string to avoid issues with $ and \ in Matcher.appendReplacement
            matcher.appendReplacement(sb, Matcher.quoteReplacement(replacement));
        }
        matcher.appendTail(sb);

        return sb.toString();
    }

    /**
     * Resolves a single placeholder to its value from the request.
     */
    private static String resolvePlaceholder(String placeholder, ServerHttpRequest request) {
        return switch (placeholder) {
            case "method" -> request.getMethod() != null ? request.getMethod().name() : "";
            case "path" -> request.getURI().getPath() != null ? request.getURI().getPath() : "";
            case "timestamp" -> Instant.now().toString();
            case "correlationId" -> {
                String corr = request.getHeaders().getFirst(RoutifyHeaders.CORRELATION_ID);
                yield corr != null ? corr : "";
            }
            default -> {
                // header:Name
                if (placeholder.startsWith("header:")) {
                    String headerName = placeholder.substring(7);
                    String val = request.getHeaders().getFirst(headerName);
                    yield val != null ? val : "";
                }
                // param:name
                if (placeholder.startsWith("param:")) {
                    String paramName = placeholder.substring(6);
                    String val = request.getQueryParams().getFirst(paramName);
                    yield val != null ? val : "";
                }
                // Unknown placeholder → empty string
                yield "";
            }
        };
    }

    // ─── Config ───────────────────────────────────────────────────────────────

    @Data
    public static class Config {
        /** HTTP status code to return. Default: 200. */
        private int status = 200;

        /** Response Content-Type header. Default: application/json. */
        private String contentType = "application/json";

        /**
         * Response body. Supports template interpolation with request attributes:
         * {@code ${method}}, {@code ${path}}, {@code ${header:Name}},
         * {@code ${param:name}}, {@code ${timestamp}}, {@code ${correlationId}}.
         */
        private String body = "{}";

        /**
         * Additional response headers as a comma-separated string of key:value pairs,
         * or as a JSON-like map. SCG property binding will convert Map configs.
         */
        private String headers = "";

        /** Simulated latency in milliseconds. 0 = no delay. */
        private long delay = 0;

        /**
         * When set, the mock response is only returned if the request contains this header.
         * If the header is absent, the request passes through to upstream.
         */
        private String conditionHeader = "";

        /**
         * Parses the headers string into a Map. Supports "Key: Value, Key2: Value2" format.
         */
        Map<String, String> getParsedHeaders() {
            if (headers == null || headers.isBlank()) {
                return Map.of();
            }
            Map<String, String> result = new LinkedHashMap<>();
            // Split by comma, then by first colon or equals
            for (String entry : headers.split(",")) {
                String trimmed = entry.trim();
                int sep = trimmed.indexOf(':');
                if (sep < 0) sep = trimmed.indexOf('=');
                if (sep > 0 && sep < trimmed.length() - 1) {
                    String key = trimmed.substring(0, sep).trim();
                    String value = trimmed.substring(sep + 1).trim();
                    if (!key.isEmpty()) {
                        result.put(key, value);
                    }
                }
            }
            return result;
        }
    }
}

