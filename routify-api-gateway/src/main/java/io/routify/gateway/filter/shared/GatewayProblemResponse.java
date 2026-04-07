package io.routify.gateway.filter.shared;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.core.io.buffer.DataBuffer;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.server.reactive.ServerHttpResponse;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Shared utility for writing RFC 9457 ProblemDetail JSON error responses from
 * gateway filter factories.
 *
 * <p>Uses Jackson {@link ObjectMapper} for body serialization, guaranteeing safe
 * JSON encoding of all fields (no manual escaping). Always sets
 * {@code Content-Type: application/problem+json}.
 *
 * <h3>Usage</h3>
 * <pre>{@code
 * return GatewayProblemResponse.status(HttpStatus.TOO_MANY_REQUESTS)
 *     .errorCode("RATE_LIMIT_EXCEEDED")
 *     .detail("Rate limit exceeded for key %s", clientKey)
 *     .header("Retry-After", retryAfterSeconds)
 *     .write(exchange);
 * }</pre>
 *
 * @see <a href="https://www.rfc-editor.org/rfc/rfc9457">RFC 9457 — Problem Details for HTTP APIs</a>
 */
public final class GatewayProblemResponse {

    /** Shared Jackson ObjectMapper — thread-safe, reused across all filter factories. */
    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    /** RFC 9457 standard content type. */
    private static final String PROBLEM_JSON = "application/problem+json";

    private GatewayProblemResponse() {
        // utility class
    }

    /**
     * Entry point — starts building a ProblemDetail response with the given HTTP status.
     *
     * @param status the HTTP status code for the response
     * @return a new {@link Builder} instance
     */
    public static Builder status(HttpStatus status) {
        return new Builder(status);
    }

    /**
     * Reactive builder for RFC 9457 ProblemDetail responses.
     *
     * <p>Supports standard fields ({@code type}, {@code title}, {@code status},
     * {@code detail}, {@code instance}) plus Routify's {@code errorCode} extension
     * and arbitrary extension properties via {@link #extension(String, Object)}.
     */
    public static final class Builder {

        private final HttpStatus status;
        private String type = "about:blank";
        private String errorCode;
        private String detail;
        private String instance;
        private final Map<String, String> headers = new LinkedHashMap<>();
        private final Map<String, Object> extensions = new LinkedHashMap<>();

        private Builder(HttpStatus status) {
            this.status = status;
        }

        /**
         * Sets the {@code errorCode} field (Routify extension).
         */
        public Builder errorCode(String code) {
            this.errorCode = code;
            return this;
        }

        /**
         * Sets the {@code detail} field — a human-readable explanation specific to this occurrence.
         */
        public Builder detail(String detail) {
            this.detail = detail;
            return this;
        }

        /**
         * Sets the {@code detail} field using {@link String#formatted(Object...)}.
         */
        public Builder detail(String format, Object... args) {
            this.detail = format.formatted(args);
            return this;
        }

        /**
         * Sets the RFC 9457 {@code type} URI (default: {@code about:blank}).
         */
        public Builder type(String typeUri) {
            this.type = typeUri;
            return this;
        }

        /**
         * Sets the RFC 9457 {@code instance} URI.
         */
        public Builder instance(String instanceUri) {
            this.instance = instanceUri;
            return this;
        }

        /**
         * Adds an arbitrary response header.
         */
        public Builder header(String name, String value) {
            this.headers.put(name, value);
            return this;
        }

        /**
         * Adds an arbitrary response header (long value).
         */
        public Builder header(String name, long value) {
            this.headers.put(name, String.valueOf(value));
            return this;
        }

        /**
         * Adds an RFC 9457 extension member to the response body.
         *
         * <p>Extension members appear as top-level JSON properties alongside the standard
         * ProblemDetail fields. Common Routify extensions: {@code evaluationId},
         * {@code violations}.
         */
        public Builder extension(String key, Object value) {
            this.extensions.put(key, value);
            return this;
        }

        /**
         * Serializes the ProblemDetail body, sets the response status and headers,
         * and writes the body to the exchange. Returns {@code Mono<Void>} that
         * completes when the write finishes.
         *
         * @param exchange the current server web exchange
         * @return a {@code Mono<Void>} completing the response
         */
        public Mono<Void> write(ServerWebExchange exchange) {
            ServerHttpResponse response = exchange.getResponse();
            response.setStatusCode(status);
            response.getHeaders().set(HttpHeaders.CONTENT_TYPE, PROBLEM_JSON);

            // Apply custom response headers
            headers.forEach((name, value) -> response.getHeaders().set(name, value));

            // Build the ProblemDetail body as an ordered map
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("type", type);
            body.put("title", status.getReasonPhrase());
            body.put("status", status.value());
            if (errorCode != null) {
                body.put("errorCode", errorCode);
            }
            if (detail != null) {
                body.put("detail", detail);
            }
            if (instance != null) {
                body.put("instance", instance);
            }
            // Add extension members
            body.putAll(extensions);

            try {
                byte[] bytes = OBJECT_MAPPER.writeValueAsBytes(body);
                DataBuffer buffer = response.bufferFactory().wrap(bytes);
                return response.writeWith(Mono.just(buffer));
            } catch (Exception e) {
                // Fallback: write a minimal error response if Jackson serialization fails
                byte[] fallback = ("{\"type\":\"about:blank\",\"title\":\"%s\",\"status\":%d}"
                        .formatted(status.getReasonPhrase(), status.value()))
                        .getBytes(StandardCharsets.UTF_8);
                DataBuffer buffer = response.bufferFactory().wrap(fallback);
                return response.writeWith(Mono.just(buffer));
            }
        }
    }
}

