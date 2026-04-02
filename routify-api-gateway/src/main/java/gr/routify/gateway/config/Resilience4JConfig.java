package gr.routify.gateway.config;

import io.github.resilience4j.circuitbreaker.CircuitBreakerConfig;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import io.github.resilience4j.timelimiter.TimeLimiterConfig;
import io.github.resilience4j.timelimiter.TimeLimiterRegistry;
import org.springframework.cloud.circuitbreaker.resilience4j.ReactiveResilience4JCircuitBreakerFactory;
import org.springframework.cloud.circuitbreaker.resilience4j.Resilience4JConfigBuilder;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Wires the {@link ReactiveResilience4JCircuitBreakerFactory} to use per-route
 * circuit-breaker and time-limiter configs when available, falling back to registry defaults.
 */
@Configuration
public class Resilience4JConfig {

    @Bean
    @SuppressWarnings("deprecation")
    public ReactiveResilience4JCircuitBreakerFactory reactiveResilience4JCircuitBreakerFactory(
            final CircuitBreakerRegistry circuitBreakerRegistry,
            final TimeLimiterRegistry timeLimiterRegistry) {

        ReactiveResilience4JCircuitBreakerFactory factory =
                new ReactiveResilience4JCircuitBreakerFactory(circuitBreakerRegistry, timeLimiterRegistry);

        factory.configureDefault(id -> {
            CircuitBreakerConfig cbConfig = circuitBreakerRegistry.getConfiguration(id)
                    .orElseGet(circuitBreakerRegistry::getDefaultConfig);
            TimeLimiterConfig tlConfig = timeLimiterRegistry.getConfiguration(id)
                    .orElseGet(timeLimiterRegistry::getDefaultConfig);
            return new Resilience4JConfigBuilder(id)
                    .circuitBreakerConfig(cbConfig)
                    .timeLimiterConfig(tlConfig)
                    .build();
        });

        return factory;
    }
}
