package io.routify.gateway.filter.resilience;

import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerConfig;
import io.github.resilience4j.circuitbreaker.CircuitBreakerConfig.SlidingWindowType;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import io.github.resilience4j.circuitbreaker.CallNotPermittedException;
import io.github.resilience4j.reactor.circuitbreaker.operator.CircuitBreakerOperator;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.routify.common.event.KafkaTopics;
import io.routify.gateway.filter.shared.GatewayProblemResponse;
import lombok.Data;
import lombok.extern.slf4j.Slf4j;
import org.springframework.cloud.gateway.filter.GatewayFilter;
import org.springframework.cloud.gateway.filter.factory.AbstractGatewayFilterFactory;
import org.springframework.cloud.gateway.route.Route;
import org.springframework.cloud.gateway.support.ServerWebExchangeUtils;
import org.springframework.http.HttpStatus;
import org.springframework.http.server.reactive.ServerHttpResponse;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;

import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Per-route Resilience4j circuit breaker filter replacing the deprecated
 * {@code CIRCUIT_BREAKER} type.
 *
 * <p>Features:
 * <ul>
 *   <li>Configurable failure-rate and slow-call-rate thresholds</li>
 *   <li>Half-open probing with configurable permitted calls</li>
 *   <li>State broadcast via Kafka → admin-api → WebSocket STOMP ({@code /topic/events})</li>
 *   <li>Manual override via Kafka commands (force-open / force-closed / reset)</li>
 *   <li>Resilience4j Micrometer metrics tagged by {@code routeId}</li>
 *   <li>Custom state gauge {@code routify.filter.circuit_breaker.state}</li>
 * </ul>
 *
 * <p>Filter type: {@code CIRCUIT_BREAKER_V2}
 */
@Slf4j
@Component
public class CircuitBreakerV2GatewayFilterFactory
        extends AbstractGatewayFilterFactory<CircuitBreakerV2GatewayFilterFactory.Config> {

    private final CircuitBreakerRegistry circuitBreakerRegistry;
    private final MeterRegistry meterRegistry;
    private final KafkaTemplate<String, Object> kafkaTemplate;

    /**
     * Per-route circuit breaker instances keyed by route ID.
     * Exposed for manual override by the Kafka consumer.
     */
    private final ConcurrentHashMap<String, CircuitBreaker> circuitBreakers = new ConcurrentHashMap<>();

    /** Numeric state gauge values per route — updated on every state transition */
    private final ConcurrentHashMap<String, AtomicInteger> stateGauges = new ConcurrentHashMap<>();

    public CircuitBreakerV2GatewayFilterFactory(
            CircuitBreakerRegistry circuitBreakerRegistry,
            MeterRegistry meterRegistry,
            KafkaTemplate<String, Object> kafkaTemplate) {
        super(Config.class);
        this.circuitBreakerRegistry = circuitBreakerRegistry;
        this.meterRegistry = meterRegistry;
        this.kafkaTemplate = kafkaTemplate;
    }

    @Override
    public GatewayFilter apply(Config config) {
        return (exchange, chain) -> {
            // Resolve route ID from the exchange
            final String resolvedRouteId = extractRouteId(exchange);

            CircuitBreaker cb = getOrCreateCircuitBreaker(resolvedRouteId, config);

            return Mono.defer(() -> chain.filter(exchange))
                    .transformDeferred(CircuitBreakerOperator.of(cb))
                    .onErrorResume(CallNotPermittedException.class, ignored -> {
                        log.warn("CircuitBreakerV2: circuit OPEN for route={}, returning {}",
                                resolvedRouteId, config.getFallbackStatus());
                        return writeFallbackResponse(exchange, config);
                    });
        };
    }

    /**
     * Get or create a circuit breaker instance for a given route.
     */
    CircuitBreaker getOrCreateCircuitBreaker(String routeId, Config config) {
        return circuitBreakers.computeIfAbsent(routeId, id -> {
            CircuitBreakerConfig cbConfig = CircuitBreakerConfig.custom()
                    .failureRateThreshold(config.getFailureRateThreshold())
                    .slowCallRateThreshold(config.getSlowCallRateThreshold())
                    .slowCallDurationThreshold(Duration.ofMillis(config.getSlowCallDurationMs()))
                    .slidingWindowSize(config.getSlidingWindowSize())
                    .slidingWindowType(parseSlidingWindowType(config.getSlidingWindowType()))
                    .minimumNumberOfCalls(config.getMinimumNumberOfCalls())
                    .waitDurationInOpenState(Duration.ofMillis(config.getWaitDurationInOpenStateMs()))
                    .permittedNumberOfCallsInHalfOpenState(config.getPermittedNumberOfCallsInHalfOpenState())
                    .build();

            CircuitBreaker cb = circuitBreakerRegistry.circuitBreaker("cbv2-" + id, cbConfig);

            // Register state transition event listener
            cb.getEventPublisher().onStateTransition(event -> {
                log.info("CircuitBreakerV2: route={} state {} → {}",
                        id, event.getStateTransition().getFromState(), event.getStateTransition().getToState());
                broadcastStateChange(id, cb, event.getStateTransition().getFromState().name(),
                        event.getStateTransition().getToState().name());
            });

            // Register state gauge
            registerStateGauge(id, cb);

            log.info("CircuitBreakerV2: created circuit breaker for route={} " +
                            "(failureRate={}%, slowCallRate={}%, slowCallDuration={}ms, window={} {})",
                    id, config.getFailureRateThreshold(), config.getSlowCallRateThreshold(),
                    config.getSlowCallDurationMs(), config.getSlidingWindowSize(), config.getSlidingWindowType());

            return cb;
        });
    }

    /**
     * Get a circuit breaker by route ID (for manual override).
     * Returns {@code null} if no CB exists for the route.
     */
    public CircuitBreaker getCircuitBreaker(String routeId) {
        return circuitBreakers.get(routeId);
    }

    /**
     * Returns all tracked circuit breaker instances.
     */
    public Map<String, CircuitBreaker> getAllCircuitBreakers() {
        return Map.copyOf(circuitBreakers);
    }

    // ─── Route ID extraction ──────────────────────────────────────────────────

    private static String extractRouteId(org.springframework.web.server.ServerWebExchange exchange) {
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

    // ─── State broadcast ──────────────────────────────────────────────────────

    private void broadcastStateChange(String routeId, CircuitBreaker cb,
                                      String fromState, String toState) {
        try {
            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("eventType", "CIRCUIT_BREAKER_STATE_CHANGE");
            payload.put("routeId", routeId);
            payload.put("fromState", fromState);
            payload.put("toState", toState);
            payload.put("timestamp", Instant.now().toString());
            payload.put("failureRate", cb.getMetrics().getFailureRate());
            payload.put("slowCallRate", cb.getMetrics().getSlowCallRate());

            kafkaTemplate.send(KafkaTopics.AUDIT_EVENTS, routeId, payload);
            log.debug("CircuitBreakerV2: broadcast state change for route={}", routeId);
        } catch (Exception e) {
            log.warn("CircuitBreakerV2: failed to broadcast state change for route={}: {}",
                    routeId, e.getMessage());
        }

        // Update gauge value
        AtomicInteger gauge = stateGauges.get(routeId);
        if (gauge != null) {
            gauge.set(stateToNumeric(cb.getState().name()));
        }
    }

    // ─── Metrics ──────────────────────────────────────────────────────────────

    private void registerStateGauge(String routeId, CircuitBreaker cb) {
        AtomicInteger stateValue = new AtomicInteger(stateToNumeric(cb.getState().name()));
        stateGauges.put(routeId, stateValue);

        Gauge.builder("routify.filter.circuit_breaker.state", stateValue, AtomicInteger::get)
                .description("Circuit breaker state (0=CLOSED, 1=OPEN, 2=HALF_OPEN, 3=FORCED_OPEN)")
                .tags(List.of(io.micrometer.core.instrument.Tag.of("routeId", routeId)))
                .register(meterRegistry);
    }

    private static int stateToNumeric(String state) {
        return switch (state) {
            case "CLOSED"                 -> 0;
            case "OPEN"                   -> 1;
            case "HALF_OPEN"              -> 2;
            case "FORCED_OPEN"            -> 3;
            case "DISABLED"               -> -1;
            case "METRICS_ONLY"           -> -2;
            default                       -> -99;
        };
    }

    // ─── Helpers ──────────────────────────────────────────────────────────────

    private static SlidingWindowType parseSlidingWindowType(String type) {
        if ("TIME_BASED".equalsIgnoreCase(type)) {
            return SlidingWindowType.TIME_BASED;
        }
        return SlidingWindowType.COUNT_BASED;
    }

    private Mono<Void> writeFallbackResponse(org.springframework.web.server.ServerWebExchange exchange, Config config) {
        if (exchange.getResponse().isCommitted()) {
            return Mono.empty();
        }

        // If the operator provided a custom fallback body, write it raw (preserving backward compatibility)
        String customBody = config.getFallbackBody();
        if (customBody != null && !customBody.isBlank()) {
            ServerHttpResponse resp = exchange.getResponse();
            resp.setStatusCode(HttpStatus.valueOf(config.getFallbackStatus()));
            resp.getHeaders().set("Content-Type", "application/problem+json");
            return resp.writeWith(Mono.just(resp.bufferFactory().wrap(customBody.getBytes())));
        }

        // Default: use the shared builder
        return GatewayProblemResponse.status(HttpStatus.valueOf(config.getFallbackStatus()))
                .errorCode("CIRCUIT_BREAKER_OPEN")
                .detail("Circuit breaker is open — the upstream service is temporarily unavailable.")
                .write(exchange);
    }

    @Data
    public static class Config {
        /** Failure rate percentage to trip the circuit (0–100). Default: 50. */
        private float failureRateThreshold = 50.0f;
        /** Slow call rate percentage to trip the circuit. Default: 80. */
        private float slowCallRateThreshold = 80.0f;
        /** Threshold in ms above which a call is considered slow. Default: 3000. */
        private long slowCallDurationMs = 3000L;
        /** Number of calls in the sliding window. Default: 10. */
        private int slidingWindowSize = 10;
        /** Sliding window type: COUNT_BASED or TIME_BASED. Default: COUNT_BASED. */
        private String slidingWindowType = "COUNT_BASED";
        /** Minimum calls before circuit evaluates failure rate. Default: 5. */
        private int minimumNumberOfCalls = 5;
        /** Time in open state before transitioning to half-open (ms). Default: 60000. */
        private long waitDurationInOpenStateMs = 60000L;
        /** Calls allowed in half-open for probing. Default: 3. */
        private int permittedNumberOfCallsInHalfOpenState = 3;
        /** HTTP status when circuit is open. Default: 503. */
        private int fallbackStatus = 503;
        /** Response body when circuit is open. Default: ProblemDetail JSON. */
        private String fallbackBody;
    }
}

