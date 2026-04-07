package io.routify.common.observability;

import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;

/**
 * Auto-configuration that registers {@link RoutifyMetrics} in any service
 * that depends on {@code routify-common} and has Micrometer on the classpath.
 *
 * <p>This removes the need for each service to manually declare a
 * {@code @Bean RoutifyMetrics} in its own configuration — the shared library
 * provides it automatically via Spring Boot's auto-configuration mechanism.
 */
@AutoConfiguration
@ConditionalOnClass(MeterRegistry.class)
public class RoutifyMetricsAutoConfiguration {

    @Bean
    @ConditionalOnMissingBean
    public RoutifyMetrics routifyMetrics(MeterRegistry meterRegistry) {
        return new RoutifyMetrics(meterRegistry);
    }
}

