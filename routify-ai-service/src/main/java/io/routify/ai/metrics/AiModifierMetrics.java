package io.routify.ai.metrics;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

/**
 * Micrometer metrics for the AI Modification Filter evaluation pipeline.
 *
 * <h3>Metrics exposed (all prefixed with {@code routify.ai.modification.})</h3>
 * <ul>
 *   <li>{@code routify.ai.modification.applied}       — counter, tags: routeId, mutationType, cached</li>
 *   <li>{@code routify.ai.modification.latency}       — timer (ms), tags: mutationType, cached</li>
 *   <li>{@code routify.ai.modification.parse.failures}— counter (no tags)</li>
 *   <li>{@code routify.ai.modification.cache.hits}    — counter, tags: routeId</li>
 *   <li>{@code routify.ai.modification.fallbacks}     — counter, tags: routeId</li>
 * </ul>
 *
 * <h3>Prometheus alert rules</h3>
 * <ul>
 *   <li>Parse failure rate &gt; 5% → {@code AiModificationParseFailureHigh} (warning)</li>
 *   <li>p99 latency &gt; 3s       → {@code AiModificationLatencyHigh} (critical)</li>
 *   <li>Circuit breaker open       → {@code AiModifierCircuitOpen} (critical)</li>
 * </ul>
 */
@Slf4j
@Component
public class AiModifierMetrics {

    // Metric names
    static final String APPLIED        = "routify.ai.modification.applied";
    static final String LATENCY        = "routify.ai.modification.latency";
    static final String PARSE_FAILURES = "routify.ai.modification.parse.failures";
    static final String CACHE_HITS     = "routify.ai.modification.cache.hits";
    static final String FALLBACKS      = "routify.ai.modification.fallbacks";

    private final MeterRegistry registry;
    private final Counter        parseFailureCounter;
    private final ConcurrentHashMap<String, Counter> appliedCounters  = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, Counter> cacheHitCounters = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, Counter> fallbackCounters = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, Timer>   latencyTimers    = new ConcurrentHashMap<>();

    public AiModifierMetrics(MeterRegistry registry) {
        this.registry = registry;
        this.parseFailureCounter = Counter.builder(PARSE_FAILURES)
                .description("Number of AI modifier LLM responses that failed JSON parsing or schema validation")
                .register(registry);
    }

    /**
     * Records a completed AI modification evaluation.
     *
     * @param mutationType PII_SCRUB | TRANSLATE | HEADER_REWRITE | CUSTOM | PASSTHROUGH
     * @param routeId      Route that triggered the mutation.
     * @param cached       Whether the result came from Redis cache.
     * @param applied      Whether a mutation was actually applied.
     * @param latencyMs    Total evaluation latency in milliseconds.
     */
    public void recordEvaluation(String mutationType, String routeId,
                                 boolean cached, boolean applied, long latencyMs) {
        String cachedStr  = String.valueOf(cached);
        String appliedStr = String.valueOf(applied);

        // Applied counter (tagged by mutationType, cached, applied)
        String counterKey = mutationType + ":" + cachedStr + ":" + appliedStr;
        appliedCounters.computeIfAbsent(counterKey, k ->
                Counter.builder(APPLIED)
                        .description("Number of AI modification evaluations")
                        .tag("mutationType", mutationType)
                        .tag("cached",       cachedStr)
                        .tag("applied",      appliedStr)
                        .register(registry)
        ).increment();

        // Latency timer (tagged by mutationType, cached)
        String timerKey = mutationType + ":" + cachedStr;
        latencyTimers.computeIfAbsent(timerKey, k ->
                Timer.builder(LATENCY)
                        .description("AI modification evaluation latency")
                        .tag("mutationType", mutationType)
                        .tag("cached",       cachedStr)
                        .publishPercentiles(0.5, 0.95, 0.99)
                        .publishPercentileHistogram()
                        .register(registry)
        ).record(latencyMs, TimeUnit.MILLISECONDS);
    }

    /** Records a Redis cache hit for a specific route. */
    public void recordCacheHit(String routeId) {
        cacheHitCounters.computeIfAbsent(routeId, k ->
                Counter.builder(CACHE_HITS)
                        .description("Number of AI modification Redis cache hits")
                        .tag("routeId", routeId)
                        .register(registry)
        ).increment();
    }

    /** Records a circuit-breaker or timeout fallback for a specific route. */
    public void recordFallback(String routeId) {
        fallbackCounters.computeIfAbsent(routeId, k ->
                Counter.builder(FALLBACKS)
                        .description("Number of AI modification fallback activations (circuit open or timeout)")
                        .tag("routeId", routeId)
                        .register(registry)
        ).increment();
    }

    /** Records an LLM response JSON parse or schema validation failure. */
    public void recordParseFailure() {
        parseFailureCounter.increment();
    }
}

