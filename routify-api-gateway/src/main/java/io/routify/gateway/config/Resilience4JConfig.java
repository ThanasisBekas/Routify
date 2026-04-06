package io.routify.gateway.config;

import io.github.resilience4j.circuitbreaker.CircuitBreakerConfig;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import io.github.resilience4j.timelimiter.TimeLimiterConfig;
import io.github.resilience4j.timelimiter.TimeLimiterRegistry;
import org.springframework.cloud.circuitbreaker.resilience4j.ReactiveResilience4JCircuitBreakerFactory;
import org.springframework.cloud.circuitbreaker.resilience4j.Resilience4JConfigBuilder;
import org.springframework.cloud.client.circuitbreaker.Customizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Configures the auto-configured {@link ReactiveResilience4JCircuitBreakerFactory}
 * to use per-route circuit-breaker and time-limiter configs when available,
 * falling back to registry defaults.
 */
@Configuration
public class Resilience4JConfig {

    @Bean
    public Customizer<ReactiveResilience4JCircuitBreakerFactory> defaultResilience4JCustomizer(
            final CircuitBreakerRegistry circuitBreakerRegistry,
            final TimeLimiterRegistry timeLimiterRegistry) {

        return factory -> factory.configureDefault(id -> {
            CircuitBreakerConfig cbConfig = circuitBreakerRegistry.getConfiguration(id)
                    .orElseGet(circuitBreakerRegistry::getDefaultConfig);
            TimeLimiterConfig tlConfig = timeLimiterRegistry.getConfiguration(id)
                    .orElseGet(timeLimiterRegistry::getDefaultConfig);
            return new Resilience4JConfigBuilder(id)
                    .circuitBreakerConfig(cbConfig)
                    .timeLimiterConfig(tlConfig)
                    .build();
        });
    }
}
