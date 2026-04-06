package io.routify.ai.metrics;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

/**
 * Micrometer metrics for the AI filter evaluation pipeline.
 *
 * <h3>Metrics exposed (all prefixed with {@code routify.ai.filter.})</h3>
 * <ul>
 *   <li>{@code routify.ai.filter.evaluations} — counter, tags: action, mode, cached</li>
 *   <li>{@code routify.ai.filter.latency} — timer (ms), tags: action, cached</li>
 *   <li>{@code routify.ai.filter.fallbacks} — counter, tags: routeId</li>
 *   <li>{@code routify.ai.filter.parse.errors} — counter</li>
 * </ul>
 *
 * <p>These metrics are scraped by Prometheus and visualised in Grafana.
 * Grafana panels to create:
 * <ul>
 *   <li>ALLOW / BLOCK / FLAG rate (per-minute, per-route)</li>
 *   <li>LLM latency p50 / p95 / p99</li>
 *   <li>Cache hit rate (cached=true / total)</li>
 *   <li>Circuit breaker open duration</li>
 * </ul>
 */
@Slf4j
@Component
public class AiFilterMetrics {

    // Metric names
    static final String EVALUATIONS  = "routify.ai.filter.evaluations";
    static final String LATENCY      = "routify.ai.filter.latency";
    static final String FALLBACKS    = "routify.ai.filter.fallbacks";
    static final String PARSE_ERRORS = "routify.ai.filter.parse.errors";

    private final MeterRegistry registry;
    // Pre-build common counters for hot-path efficiency
    private final Counter parseErrorCounter;
    // Cache tag-based counters to avoid allocation per request
    private final ConcurrentHashMap<String, Counter> evaluationCounters = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, Timer>   latencyTimers      = new ConcurrentHashMap<>();

    public AiFilterMetrics(MeterRegistry registry) {
        this.registry = registry;
        this.parseErrorCounter = Counter.builder(PARSE_ERRORS)
                .description("Number of LLM responses that failed JSON parsing")
                .register(registry);
    }

    /**
     * Records a completed AI filter evaluation.
     *
     * @param action    ALLOW | BLOCK | FLAG
     * @param mode      SYNC | ASYNC
     * @param cached    whether the verdict came from Redis cache
     * @param latencyMs total evaluation latency
     */
    public void recordEvaluation(String action, String mode, boolean cached, long latencyMs) {
        String cachedStr = String.valueOf(cached);

        // Counter
        String counterKey = action + ":" + mode + ":" + cachedStr;
        evaluationCounters.computeIfAbsent(counterKey, k ->
                Counter.builder(EVALUATIONS)
                        .description("Number of AI filter evaluations")
                        .tag("action", action)
                        .tag("mode",   mode)
                        .tag("cached", cachedStr)
                        .register(registry)
        ).increment();

        // Timer
        String timerKey = action + ":" + cachedStr;
        latencyTimers.computeIfAbsent(timerKey, k ->
                Timer.builder(LATENCY)
                        .description("AI filter evaluation latency")
                        .tag("action", action)
                        .tag("cached", cachedStr)
                        .publishPercentiles(0.5, 0.95, 0.99)
                        .publishPercentileHistogram()
                        .register(registry)
        ).record(latencyMs, TimeUnit.MILLISECONDS);
    }

    /** Records a circuit-breaker fallback activation for a specific route. */
    public void recordFallback(String routeId) {
        Counter.builder(FALLBACKS)
                .description("Number of AI filter circuit-breaker fallback activations")
                .tag("routeId", routeId)
                .register(registry)
                .increment();
    }

    /** Records an LLM response JSON parse failure. */
    public void recordParseError() {
        parseErrorCounter.increment();
    }
}

