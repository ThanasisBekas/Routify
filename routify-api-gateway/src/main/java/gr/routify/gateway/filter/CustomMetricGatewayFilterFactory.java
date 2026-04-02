package gr.routify.gateway.filter;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Tag;
import lombok.Data;
import lombok.extern.slf4j.Slf4j;
import org.springframework.cloud.gateway.filter.GatewayFilter;
import org.springframework.cloud.gateway.filter.factory.AbstractGatewayFilterFactory;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Gateway filter factory that increments a custom Micrometer {@link Counter} for
 * every request that passes through the route it is attached to.
 *
 * <p>The counter is registered once (at filter-apply time) with the configured name
 * and tags. Because it is registered once rather than looked up per-request, there
 * is no per-request registry lookup overhead.
 *
 * <p>Dynamic per-request tag values are supported via a special {@code $header.*}
 * notation in the tag values map:
 * <ul>
 *   <li>{@code "$header.X-Tenant-Id"} — substituted with the value of the
 *       {@code X-Tenant-Id} request header, or {@code "unknown"} if missing.</li>
 *   <li>Any other value is used as a literal tag value.</li>
 * </ul>
 *
 * <p>When any dynamic tag is present the counter is looked up (not created) each
 * request because the tag values differ per request. A static counter is recorded
 * once and reused.
 *
 * <h3>Config params:</h3>
 * <ul>
 *   <li>{@code metricName} — Micrometer metric name, e.g. {@code routify.custom.hits}
 *       (default: {@code routify.gateway.custom_metric})</li>
 *   <li>{@code description} — optional metric description shown in /actuator/metrics</li>
 *   <li>{@code tags} — map of tag name → literal value or {@code $header.X-Header-Name}
 *       for dynamic per-request tag values</li>
 * </ul>
 *
 * <p>Filter type: {@code CUSTOM_METRIC}
 */
@Slf4j
@Component
public class CustomMetricGatewayFilterFactory
        extends AbstractGatewayFilterFactory<CustomMetricGatewayFilterFactory.Config> {

    private final MeterRegistry meterRegistry;

    public CustomMetricGatewayFilterFactory(MeterRegistry meterRegistry) {
        super(Config.class);
        this.meterRegistry = meterRegistry;
    }

    @Override
    public GatewayFilter apply(Config config) {
        String metricName   = config.getMetricName() != null && !config.getMetricName().isBlank()
                ? config.getMetricName() : "routify.gateway.custom_metric";
        String description  = config.getDescription() != null ? config.getDescription() : metricName;
        Map<String, String> tagConfig = config.getTags() != null ? config.getTags() : Map.of();

        // Determine whether any tags are dynamic (resolved per-request from headers)
        boolean hasDynamicTags = tagConfig.values().stream()
                .anyMatch(v -> v != null && v.startsWith("$header."));

        if (!hasDynamicTags) {
            // All tags are static — register counter once
            List<Tag> tags = buildStaticTags(tagConfig);
            Counter counter = Counter.builder(metricName)
                    .description(description)
                    .tags(tags)
                    .register(meterRegistry);

            return (exchange, chain) -> {
                counter.increment();
                log.debug("CustomMetric: incremented '{}' (static tags)", metricName);
                return chain.filter(exchange);
            };
        }

        // Has dynamic tags — resolve per-request
        return (exchange, chain) -> {
            List<Tag> tags = new ArrayList<>();
            tagConfig.forEach((name, value) -> {
                if (value != null && value.startsWith("$header.")) {
                    String headerName = value.substring("$header.".length());
                    String headerVal  = exchange.getRequest().getHeaders().getFirst(headerName);
                    tags.add(Tag.of(name, headerVal != null ? headerVal : "unknown"));
                } else {
                    tags.add(Tag.of(name, value != null ? value : ""));
                }
            });
            // Counter.builder registers-or-retrieves from the registry
            Counter.builder(metricName)
                    .description(description)
                    .tags(tags)
                    .register(meterRegistry)
                    .increment();
            log.debug("CustomMetric: incremented '{}' (dynamic tags)", metricName);
            return chain.filter(exchange);
        };
    }

    private List<Tag> buildStaticTags(Map<String, String> tagConfig) {
        List<Tag> tags = new ArrayList<>();
        tagConfig.forEach((name, value) -> tags.add(Tag.of(name, value != null ? value : "")));
        return tags;
    }

    @Data
    public static class Config {
        /** Micrometer metric name. Default: routify.gateway.custom_metric. */
        private String              metricName;
        /** Optional metric description. */
        private String              description;
        /**
         * Map of tag name → value.
         * Use {@code "$header.X-Header-Name"} to resolve the value dynamically
         * from a request header on each request.
         */
        private Map<String, String> tags;
    }
}

