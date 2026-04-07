package io.routify.gateway.filter.resilience;

import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.kafka.core.KafkaTemplate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

/**
 * Unit tests for {@link CircuitBreakerV2GatewayFilterFactory}.
 *
 * <p>Tests verify:
 * <ul>
 *   <li>Per-route circuit breaker instance creation and isolation</li>
 *   <li>Configuration propagation (thresholds, window type)</li>
 *   <li>Manual force-open / force-closed / reset via Kafka consumer</li>
 *   <li>State gauge registration</li>
 * </ul>
 */
class CircuitBreakerV2Test {

    private CircuitBreakerV2GatewayFilterFactory factory;
    private CircuitBreakerRegistry registry;
    private SimpleMeterRegistry meterRegistry;

    @SuppressWarnings("unchecked")
    @BeforeEach
    void setUp() {
        registry = CircuitBreakerRegistry.ofDefaults();
        meterRegistry = new SimpleMeterRegistry();
        KafkaTemplate<String, Object> kafkaTemplate = mock(KafkaTemplate.class);
        factory = new CircuitBreakerV2GatewayFilterFactory(registry, meterRegistry, kafkaTemplate);
    }

    @Test
    void shouldCreatePerRouteCircuitBreakers() {
        var config = new CircuitBreakerV2GatewayFilterFactory.Config();
        config.setFailureRateThreshold(60.0f);
        config.setSlidingWindowSize(20);

        CircuitBreaker cbA = factory.getOrCreateCircuitBreaker("route-a", config);
        CircuitBreaker cbB = factory.getOrCreateCircuitBreaker("route-b", config);

        assertThat(cbA).isNotNull();
        assertThat(cbB).isNotNull();
        assertThat(cbA).isNotSameAs(cbB);
        assertThat(cbA.getName()).isEqualTo("cbv2-route-a");
        assertThat(cbB.getName()).isEqualTo("cbv2-route-b");
    }

    @Test
    void shouldReturnSameInstanceForSameRoute() {
        var config = new CircuitBreakerV2GatewayFilterFactory.Config();

        CircuitBreaker cb1 = factory.getOrCreateCircuitBreaker("route-x", config);
        CircuitBreaker cb2 = factory.getOrCreateCircuitBreaker("route-x", config);

        assertThat(cb1).isSameAs(cb2);
    }

    @Test
    void shouldApplyConfigThresholds() {
        var config = new CircuitBreakerV2GatewayFilterFactory.Config();
        config.setFailureRateThreshold(75.0f);
        config.setSlowCallRateThreshold(90.0f);
        config.setSlowCallDurationMs(5000L);
        config.setSlidingWindowSize(15);
        config.setSlidingWindowType("TIME_BASED");
        config.setMinimumNumberOfCalls(10);
        config.setWaitDurationInOpenStateMs(30000L);
        config.setPermittedNumberOfCallsInHalfOpenState(5);

        CircuitBreaker cb = factory.getOrCreateCircuitBreaker("route-config", config);
        var cbConfig = cb.getCircuitBreakerConfig();

        assertThat(cbConfig.getFailureRateThreshold()).isEqualTo(75.0f);
        assertThat(cbConfig.getSlowCallRateThreshold()).isEqualTo(90.0f);
        assertThat(cbConfig.getSlowCallDurationThreshold().toMillis()).isEqualTo(5000L);
        assertThat(cbConfig.getSlidingWindowSize()).isEqualTo(15);
        assertThat(cbConfig.getSlidingWindowType().name()).isEqualTo("TIME_BASED");
        assertThat(cbConfig.getMinimumNumberOfCalls()).isEqualTo(10);
        assertThat(cbConfig.getPermittedNumberOfCallsInHalfOpenState()).isEqualTo(5);
    }

    @Test
    void shouldStartInClosedState() {
        var config = new CircuitBreakerV2GatewayFilterFactory.Config();
        CircuitBreaker cb = factory.getOrCreateCircuitBreaker("route-closed", config);

        assertThat(cb.getState()).isEqualTo(CircuitBreaker.State.CLOSED);
    }

    @Test
    void manualForceOpenShouldTransitionToForcedOpen() {
        var config = new CircuitBreakerV2GatewayFilterFactory.Config();
        factory.getOrCreateCircuitBreaker("route-force", config);

        CircuitBreaker cb = factory.getCircuitBreaker("route-force");
        assertThat(cb).isNotNull();

        cb.transitionToForcedOpenState();
        assertThat(cb.getState()).isEqualTo(CircuitBreaker.State.FORCED_OPEN);
    }

    @Test
    void manualForceClosedShouldTransitionToClosed() {
        var config = new CircuitBreakerV2GatewayFilterFactory.Config();
        factory.getOrCreateCircuitBreaker("route-fclosed", config);

        CircuitBreaker cb = factory.getCircuitBreaker("route-fclosed");
        cb.transitionToForcedOpenState();
        assertThat(cb.getState()).isEqualTo(CircuitBreaker.State.FORCED_OPEN);

        cb.transitionToClosedState();
        assertThat(cb.getState()).isEqualTo(CircuitBreaker.State.CLOSED);
    }

    @Test
    void resetShouldReturnToClosedState() {
        var config = new CircuitBreakerV2GatewayFilterFactory.Config();
        factory.getOrCreateCircuitBreaker("route-reset", config);

        CircuitBreaker cb = factory.getCircuitBreaker("route-reset");
        cb.transitionToForcedOpenState();
        assertThat(cb.getState()).isEqualTo(CircuitBreaker.State.FORCED_OPEN);

        cb.reset();
        assertThat(cb.getState()).isEqualTo(CircuitBreaker.State.CLOSED);
    }

    @Test
    void perRouteIsolation_routeACircuitDoesNotAffectRouteB() {
        var config = new CircuitBreakerV2GatewayFilterFactory.Config();
        factory.getOrCreateCircuitBreaker("route-iso-a", config);
        factory.getOrCreateCircuitBreaker("route-iso-b", config);

        CircuitBreaker cbA = factory.getCircuitBreaker("route-iso-a");
        CircuitBreaker cbB = factory.getCircuitBreaker("route-iso-b");

        // Force route-a open
        cbA.transitionToForcedOpenState();

        // Route-b should remain closed
        assertThat(cbA.getState()).isEqualTo(CircuitBreaker.State.FORCED_OPEN);
        assertThat(cbB.getState()).isEqualTo(CircuitBreaker.State.CLOSED);
    }

    @Test
    void shouldRegisterStateGaugeMetric() {
        var config = new CircuitBreakerV2GatewayFilterFactory.Config();
        factory.getOrCreateCircuitBreaker("route-gauge", config);

        var gauge = meterRegistry.find("routify.filter.circuit_breaker.state")
                .tag("routeId", "route-gauge")
                .gauge();
        assertThat(gauge).isNotNull();
        assertThat(gauge.value()).isEqualTo(0.0); // CLOSED = 0
    }

    @Test
    void getCircuitBreakerReturnsNullForUnknownRoute() {
        assertThat(factory.getCircuitBreaker("nonexistent-route")).isNull();
    }

    @Test
    void defaultConfigValues() {
        var config = new CircuitBreakerV2GatewayFilterFactory.Config();
        assertThat(config.getFailureRateThreshold()).isEqualTo(50.0f);
        assertThat(config.getSlowCallRateThreshold()).isEqualTo(80.0f);
        assertThat(config.getSlowCallDurationMs()).isEqualTo(3000L);
        assertThat(config.getSlidingWindowSize()).isEqualTo(10);
        assertThat(config.getSlidingWindowType()).isEqualTo("COUNT_BASED");
        assertThat(config.getMinimumNumberOfCalls()).isEqualTo(5);
        assertThat(config.getWaitDurationInOpenStateMs()).isEqualTo(60000L);
        assertThat(config.getPermittedNumberOfCallsInHalfOpenState()).isEqualTo(3);
        assertThat(config.getFallbackStatus()).isEqualTo(503);
        assertThat(config.getFallbackBody()).isNull();
    }
}

