package io.routify.gateway.filter;

import io.routify.common.security.RedisKeys;
import io.routify.common.web.RoutifyHeaders;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import lombok.extern.slf4j.Slf4j;
import org.springframework.cloud.gateway.filter.GatewayFilter;
import org.springframework.cloud.gateway.filter.factory.AbstractGatewayFilterFactory;
import org.springframework.data.redis.core.ReactiveStringRedisTemplate;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.http.server.reactive.ServerHttpResponse;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

import java.time.Instant;
import java.util.Map;

/**
 * Gateway filter for API Key authentication via reactive Redis hash lookup.
 *
 * <p>Validates the API key from a configurable header (default {@value RoutifyHeaders#API_KEY})
 * or an optional query parameter against a Redis Hash at
 * {@code routify:apikeys:<key>}.
 *
 * <h2>Redis hash structure</h2>
 * Each valid API key is stored as a Redis Hash with the following fields:
 * <ul>
 *   <li>{@code tenantId}  — UUID of the owning tenant</li>
 *   <li>{@code userId}    — UUID of the user the key acts as</li>
 *   <li>{@code role}      — role granted to requests (e.g. {@code OPERATOR})</li>
 *   <li>{@code email}     — email for audit attribution</li>
 *   <li>{@code expiresAt} — (optional) epoch-second expiry timestamp; if absent
 *       the key relies on Redis TTL for expiration</li>
 * </ul>
 *
 * <h2>On success</h2>
 * Injects resolved identity headers for downstream services:
 * {@code X-Auth-User-Id}, {@code X-Auth-Tenant-Id}, {@code X-Auth-Role},
 * {@code X-Auth-Email}, {@code X-Auth-Type=API_KEY}.
 *
 * <h2>Error responses</h2>
 * Returns RFC 9457 ProblemDetail JSON with appropriate error codes:
 * {@code MISSING_API_KEY} (401), {@code INVALID_API_KEY} (401),
 * {@code API_KEY_EXPIRED} (401), {@code API_KEY_VALIDATION_FAILED} (502).
 *
 * <h2>Config params</h2>
 * <ul>
 *   <li>{@code headerName}     — header to read the API key from (default: {@code X-Api-Key})</li>
 *   <li>{@code queryParam}     — query param fallback (optional)</li>
 *   <li>{@code validationMode} — {@code REDIS} (default); future: {@code REMOTE}</li>
 * </ul>
 */
@Slf4j
@Component
public class ApiKeyAuthGatewayFilterFactory
        extends AbstractGatewayFilterFactory<ApiKeyAuthGatewayFilterFactory.Config> {

    private final ReactiveStringRedisTemplate redisTemplate;
    private final Counter authFailures;

    public ApiKeyAuthGatewayFilterFactory(ReactiveStringRedisTemplate redisTemplate,
                                          MeterRegistry meterRegistry) {
        super(Config.class);
        this.redisTemplate = redisTemplate;
        this.authFailures = Counter.builder("routify.auth.failures")
                .tag("method", "API_KEY")
                .description("API key authentication failures")
                .register(meterRegistry);
    }

    @Override
    public GatewayFilter apply(Config config) {
        return (exchange, chain) -> {
            String apiKey = extractApiKey(exchange, config);

            if (apiKey == null || apiKey.isBlank()) {
                authFailures.increment();
                return unauthorized(exchange, "MISSING_API_KEY", "API Key is required");
            }

            String redisKey = RedisKeys.APIKEY_PREFIX + apiKey;

            return redisTemplate.<String, String>opsForHash()
                    .entries(redisKey)
                    .collectMap(Map.Entry::getKey, Map.Entry::getValue)
                    .flatMap(fields -> {
                        if (fields.isEmpty()) {
                            log.debug("API key rejected — not found in Redis (key prefix: {}…)",
                                    apiKey.substring(0, Math.min(8, apiKey.length())));
                            authFailures.increment();
                            return unauthorized(exchange, "INVALID_API_KEY",
                                    "API Key is invalid or has been revoked");
                        }

                        // ── Optional expiry check ────────────────────────────
                        String expiresAt = fields.get("expiresAt");
                        if (expiresAt != null && !expiresAt.isBlank()) {
                            try {
                                long expiryEpoch = Long.parseLong(expiresAt);
                                if (Instant.ofEpochSecond(expiryEpoch).isBefore(Instant.now())) {
                                    log.debug("API key rejected — expired (expiresAt={})",
                                            expiresAt);
                                    authFailures.increment();
                                    return unauthorized(exchange, "API_KEY_EXPIRED",
                                            "API Key has expired");
                                }
                            } catch (NumberFormatException e) {
                                log.warn("API key has malformed expiresAt field: {}", expiresAt);
                            }
                        }

                        // ── Inject identity headers ──────────────────────────
                        String tenantId = fields.getOrDefault("tenantId", "");
                        String userId   = fields.getOrDefault("userId", "");
                        String role     = fields.getOrDefault("role", "");
                        String email    = fields.getOrDefault("email", "");

                        ServerHttpRequest mutatedRequest = exchange.getRequest().mutate()
                                .header(RoutifyHeaders.AUTH_USER_ID,   userId)
                                .header(RoutifyHeaders.AUTH_TENANT_ID, tenantId)
                                .header(RoutifyHeaders.AUTH_ROLE,      role)
                                .header(RoutifyHeaders.AUTH_EMAIL,     email)
                                .header(RoutifyHeaders.AUTH_TYPE,      "API_KEY")
                                .build();

                        log.debug("API key validated: tenant={} user={} role={}",
                                tenantId, userId, role);
                        return chain.filter(
                                exchange.mutate().request(mutatedRequest).build());
                    })
                    .onErrorResume(ex -> {
                        log.error("Redis lookup failed for API key (prefix: {}…): {}",
                                apiKey.substring(0, Math.min(8, apiKey.length())),
                                ex.getMessage());
                        return gatewayError(exchange, "API_KEY_VALIDATION_FAILED",
                                "API Key validation is temporarily unavailable");
                    });
        };
    }

    private String extractApiKey(ServerWebExchange exchange, Config config) {
        String headerName = config.getHeaderName() != null
                ? config.getHeaderName()
                : RoutifyHeaders.API_KEY;
        String apiKey = exchange.getRequest().getHeaders().getFirst(headerName);

        if (apiKey == null && config.getQueryParam() != null) {
            apiKey = exchange.getRequest().getQueryParams().getFirst(config.getQueryParam());
        }
        return apiKey;
    }

    // ─── Error responses (RFC 9457 ProblemDetail JSON) ───────────────────────

    private Mono<Void> unauthorized(ServerWebExchange exchange, String errorCode, String detail) {
        return writeErrorResponse(exchange, HttpStatus.UNAUTHORIZED, errorCode, detail);
    }

    private Mono<Void> gatewayError(ServerWebExchange exchange, String errorCode, String detail) {
        return writeErrorResponse(exchange, HttpStatus.BAD_GATEWAY, errorCode, detail);
    }

    private Mono<Void> writeErrorResponse(ServerWebExchange exchange, HttpStatus status,
                                           String errorCode, String detail) {
        ServerHttpResponse response = exchange.getResponse();
        response.setStatusCode(status);
        response.getHeaders().set(HttpHeaders.CONTENT_TYPE, "application/problem+json");
        String body = """
                {"type":"about:blank","title":"%s","status":%d,\
                "errorCode":"%s","detail":"%s"}\
                """.formatted(status.getReasonPhrase(), status.value(), errorCode, detail);
        return response.writeWith(
                Mono.just(response.bufferFactory().wrap(body.getBytes())));
    }

    /**
     * Configuration for the ApiKeyAuth filter.
     * Populated from the filter definition's config JSON.
     */
    public static class Config {
        private String headerName = RoutifyHeaders.API_KEY;
        private String queryParam;
        private String validationMode = "REDIS";

        public String getHeaderName()             { return headerName; }
        public void setHeaderName(String h)       { this.headerName = h; }
        public String getQueryParam()             { return queryParam; }
        public void setQueryParam(String q)       { this.queryParam = q; }
        public String getValidationMode()         { return validationMode; }
        public void setValidationMode(String m)   { this.validationMode = m; }
    }
}

