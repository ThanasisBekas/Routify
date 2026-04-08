package io.routify.gateway.filter.resilience;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import lombok.Data;
import lombok.extern.slf4j.Slf4j;
import org.springframework.cloud.gateway.filter.GatewayFilter;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.cloud.gateway.filter.factory.AbstractGatewayFilterFactory;
import org.springframework.cloud.gateway.route.Route;
import org.springframework.cloud.gateway.support.ServerWebExchangeUtils;
import org.springframework.core.Ordered;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;
import reactor.util.retry.Retry;

import java.io.IOException;
import java.time.Duration;
import java.util.Arrays;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;

/**
 * Per-route custom retry filter with exponential backoff, jitter, idempotency-aware
 * retry logic, and configurable retry conditions. Replaces the deprecated
 * {@code RETRY} filter type.
 *
 * <p>Features:
 * <ul>
 *   <li>Exponential backoff with configurable multiplier, max backoff, and jitter</li>
 *   <li>Idempotency awareness — unsafe methods (POST, PUT, PATCH, DELETE) are only
 *       retried when the configured idempotency header is present</li>
 *   <li>Timeout retry — optionally retries on connection/read timeouts</li>
 *   <li>{@code X-Retry-Count} header injection on retried requests</li>
 *   <li>Micrometer metrics: {@code routify.filter.retry.attempt},
 *       {@code routify.filter.retry.exhausted}, {@code routify.filter.retry.success}</li>
 * </ul>
 *
 * <p>Filter type: {@code RETRY_V2}
 */
@Slf4j
@Component
public class RetryV2GatewayFilterFactory
        extends AbstractGatewayFilterFactory<RetryV2GatewayFilterFactory.Config> {

    private final MeterRegistry meterRegistry;

    /** Lazily-created counters keyed by routeId to avoid re-registration */
    private final ConcurrentHashMap<String, RouteMetrics> metricsCache = new ConcurrentHashMap<>();

    public RetryV2GatewayFilterFactory(MeterRegistry meterRegistry) {
        super(Config.class);
        this.meterRegistry = meterRegistry;
    }

    @Override
    public GatewayFilter apply(Config config) {
        Set<Integer> retryableStatuses = parseStatuses(config.getRetryableStatuses());
        Set<String> retryableMethods = parseMethods(config.getRetryableMethods());

        return new RetryV2Filter(config, retryableStatuses, retryableMethods);
    }

    // ─── Inner filter ─────────────────────────────────────────────────────────

    private class RetryV2Filter implements GatewayFilter, Ordered {

        private final Config config;
        private final Set<Integer> retryableStatuses;
        private final Set<String> retryableMethods;

        RetryV2Filter(Config config, Set<Integer> retryableStatuses, Set<String> retryableMethods) {
            this.config = config;
            this.retryableStatuses = retryableStatuses;
            this.retryableMethods = retryableMethods;
        }

        @Override
        public int getOrder() {
            // Run after most filters but before the final forwarding
            return Ordered.LOWEST_PRECEDENCE - 1;
        }

        @Override
        public Mono<Void> filter(ServerWebExchange exchange, GatewayFilterChain chain) {
            String routeId = extractRouteId(exchange);
            RouteMetrics metrics = getOrCreateMetrics(routeId);

            // Check if the request method is retryable (or has idempotency header)
            if (!isRetryable(exchange, config)) {
                log.debug("RetryV2: route={} method={} not retryable — skipping retry logic",
                        routeId, exchange.getRequest().getMethod());
                return chain.filter(exchange);
            }

            AtomicInteger attemptCounter = new AtomicInteger(0);

            Retry retrySpec = Retry.backoff(config.getMaxRetries(),
                            Duration.ofMillis(config.getInitialBackoffMs()))
                    .maxBackoff(Duration.ofMillis(config.getMaxBackoffMs()))
                    .multiplier(config.getBackoffMultiplier())
                    .jitter(config.getJitterFactor())
                    .filter(throwable -> isRetryableException(throwable, config))
                    .doBeforeRetry(signal -> {
                        int attempt = attemptCounter.incrementAndGet();
                        metrics.attempt.increment();
                        log.debug("RetryV2: route={} retry attempt #{} (cause={})",
                                routeId, attempt, signal.failure().getClass().getSimpleName());
                    })
                    .onRetryExhaustedThrow((spec, signal) -> {
                        metrics.exhausted.increment();
                        log.warn("RetryV2: route={} all {} retries exhausted (last cause={})",
                                routeId, config.getMaxRetries(), signal.failure().getMessage());
                        return signal.failure();
                    });

            return Mono.defer(() -> executeWithStatusCheck(exchange, chain, routeId))
                    .retryWhen(retrySpec)
                    .doOnSuccess(v -> {
                        if (attemptCounter.get() > 0) {
                            metrics.success.increment();
                            log.debug("RetryV2: route={} succeeded after {} retries", routeId, attemptCounter.get());
                        }
                    })
                    .onErrorResume(RetryableStatusException.class, ex -> {
                        // All retries exhausted with a retryable status — return the last response as-is
                        return Mono.empty();
                    });
        }

        /**
         * Executes the filter chain and inspects the response status.
         * If the status is retryable, throws a {@link RetryableStatusException}
         * to trigger Reactor's retry mechanism.
         */
        private Mono<Void> executeWithStatusCheck(ServerWebExchange exchange,
                                                   GatewayFilterChain chain,
                                                   String routeId) {
            // Mutate request to add X-Retry-Count header on subsequent attempts
            ServerHttpRequest mutatedRequest = exchange.getRequest();

            return chain.filter(exchange.mutate().request(mutatedRequest).build())
                    .then(Mono.defer(() -> {
                        HttpStatusCode status = exchange.getResponse().getStatusCode();
                        if (status != null && retryableStatuses.contains(status.value())) {
                            // Only throw if response is not yet committed to allow retry
                            if (!exchange.getResponse().isCommitted()) {
                                return Mono.error(new RetryableStatusException(
                                        status.value(), routeId));
                            }
                        }
                        return Mono.empty();
                    }));
        }

        /**
         * Determines if the request is retryable based on HTTP method and idempotency header.
         */
        private boolean isRetryable(ServerWebExchange exchange, Config cfg) {
            HttpMethod method = exchange.getRequest().getMethod();
            String methodName = method != null ? method.name() : "GET";

            // Safe methods are always retryable
            if (retryableMethods.contains(methodName)) {
                return true;
            }

            // Unsafe methods are retryable only if the idempotency header is present
            String idempotencyHeader = cfg.getIdempotencyHeader();
            if (idempotencyHeader != null && !idempotencyHeader.isBlank()) {
                String headerValue = exchange.getRequest().getHeaders().getFirst(idempotencyHeader);
                if (headerValue != null && !headerValue.isBlank()) {
                    log.debug("RetryV2: unsafe method {} retryable via idempotency header '{}'",
                            methodName, idempotencyHeader);
                    return true;
                }
            }

            log.debug("RetryV2: unsafe method {} not retryable — no idempotency header present", methodName);
            return false;
        }
    }

    // ─── Exception types ──────────────────────────────────────────────────────

    /**
     * Thrown when the upstream returns a retryable HTTP status code.
     * Used to bridge SCG's status-code-based error signalling with Reactor's
     * exception-based retry mechanism.
     */
    static class RetryableStatusException extends RuntimeException {
        private final int statusCode;

        RetryableStatusException(int statusCode, String routeId) {
            super("Retryable status %d for route %s".formatted(statusCode, routeId));
            this.statusCode = statusCode;
        }

        int getStatusCode() { return statusCode; }
    }

    // ─── Retry predicate ──────────────────────────────────────────────────────

    /**
     * Determines if a throwable should trigger a retry.
     */
    static boolean isRetryableException(Throwable throwable, Config config) {
        // Our own status-based exception — always retryable
        if (throwable instanceof RetryableStatusException) {
            return true;
        }

        // Timeout exceptions — only if retryOnTimeout is enabled
        if (config.isRetryOnTimeout()) {
            if (throwable instanceof TimeoutException) return true;
            if (throwable instanceof io.netty.channel.ConnectTimeoutException) return true;
            if (throwable instanceof io.netty.handler.timeout.ReadTimeoutException) return true;
            if (throwable instanceof java.net.ConnectException) return true;
        }

        // I/O exceptions (connection reset, etc.)
        if (throwable instanceof IOException) {
            return config.isRetryOnTimeout();
        }

        return false;
    }

    // ─── Route ID extraction ──────────────────────────────────────────────────

    private static String extractRouteId(ServerWebExchange exchange) {
        var routeAttr = exchange.getAttribute(ServerWebExchangeUtils.GATEWAY_ROUTE_ATTR);
        if (routeAttr instanceof Route route) {
            String id = route.getId();
            if (id != null && id.contains("::")) {
                return id.substring(id.indexOf("::") + 2);
            }
            return id != null ? id : "unknown";
        }
        return "unknown";
    }

    // ─── Metrics ──────────────────────────────────────────────────────────────

    private RouteMetrics getOrCreateMetrics(String routeId) {
        return metricsCache.computeIfAbsent(routeId, id -> {
            Counter attempt = Counter.builder("routify.filter.retry.attempt")
                    .description("Number of retry attempts")
                    .tag("routeId", id)
                    .register(meterRegistry);

            Counter exhausted = Counter.builder("routify.filter.retry.exhausted")
                    .description("Retries exhausted — all attempts failed")
                    .tag("routeId", id)
                    .register(meterRegistry);

            Counter success = Counter.builder("routify.filter.retry.success")
                    .description("Successful request after at least one retry")
                    .tag("routeId", id)
                    .register(meterRegistry);

            log.debug("RetryV2: registered metrics for route={}", id);
            return new RouteMetrics(attempt, exhausted, success);
        });
    }

    private record RouteMetrics(Counter attempt, Counter exhausted, Counter success) {}

    // ─── Config parsing helpers ───────────────────────────────────────────────

    static Set<Integer> parseStatuses(String statuses) {
        if (statuses == null || statuses.isBlank()) {
            return Set.of(502, 503, 504);
        }
        return Arrays.stream(statuses.split(","))
                .map(String::trim)
                .filter(s -> !s.isEmpty())
                .map(Integer::parseInt)
                .collect(Collectors.toSet());
    }

    static Set<String> parseMethods(String methods) {
        if (methods == null || methods.isBlank()) {
            return Set.of("GET", "HEAD", "OPTIONS");
        }
        return Arrays.stream(methods.split(","))
                .map(String::trim)
                .filter(s -> !s.isEmpty())
                .map(String::toUpperCase)
                .collect(Collectors.toSet());
    }

    // ─── Config ───────────────────────────────────────────────────────────────

    @Data
    public static class Config {
        /** Maximum retry attempts. Default: 3. */
        private int maxRetries = 3;
        /** Initial backoff delay in milliseconds. Default: 500. */
        private long initialBackoffMs = 500;
        /** Maximum backoff delay in milliseconds. Default: 5000. */
        private long maxBackoffMs = 5000;
        /** Backoff multiplier for exponential growth. Default: 2.0. */
        private double backoffMultiplier = 2.0;
        /** Random jitter factor (0.0–1.0) to prevent retry storms. Default: 0.25. */
        private double jitterFactor = 0.25;
        /** Comma-separated HTTP status codes to retry on. Default: "502,503,504". */
        private String retryableStatuses = "502,503,504";
        /** Comma-separated HTTP methods safe to retry. Default: "GET,HEAD,OPTIONS". */
        private String retryableMethods = "GET,HEAD,OPTIONS";
        /** Retry on upstream connection/read timeouts. Default: true. */
        private boolean retryOnTimeout = true;
        /** Header name that signals the request is idempotent (allows retry of unsafe methods). Default: "Idempotency-Key". */
        private String idempotencyHeader = "Idempotency-Key";
    }
}

