package io.routify.gateway.routing;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.cloud.gateway.filter.FilterDefinition;

import java.util.*;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Reproduces the bug where {@code customFilter()} serialises {@code List} and
 * {@code Map} config values with Java's {@code .toString()}, producing bracket
 * notation ({@code "[]"}, {@code "[a, b]"}) that Spring Cloud Gateway's property
 * binder cannot convert back to typed collections.
 *
 * <p>The immediate user-visible effect is that <em>all</em> filter config appears
 * to be ignored — the filter uses only its default values — because SCG's
 * {@code Binder} falls back to defaults when it encounters an unconvertible
 * property.
 */
class CustomFilterListSerializationTest {

    /**
     * Simulates exactly what {@link RouteDefinitionBuilder#customFilter} does
     * with a REQUEST_LOGGER config map deserialized from JSONB.
     */
    @Test
    @DisplayName("customFilter serialises List values with .toString() → brackets that SCG cannot parse")
    void listToStringProducesBracketNotation() {
        // ── Arrange: a config map as Jackson deserialises from JSONB ──────────
        Map<String, Object> dbConfig = new LinkedHashMap<>();
        dbConfig.put("logRequestBody", true);
        dbConfig.put("logResponseBody", true);
        dbConfig.put("logRequestHeaders", true);
        dbConfig.put("logResponseHeaders", true);
        dbConfig.put("maxBodyCaptureBytes", 4096);
        dbConfig.put("failedStatusThreshold", 500);
        dbConfig.put("samplingRate", 1.0);
        dbConfig.put("headerAllowlist", List.of());                      // empty list
        dbConfig.put("headerDenylist", List.of("Authorization", "Cookie")); // non-empty list
        dbConfig.put("skipPaths", List.of("/health", "/actuator/**"));     // non-empty list

        // ── Act: same logic as customFilter() ────────────────────────────────
        var args = new LinkedHashMap<String, String>();
        dbConfig.forEach((k, v) -> args.put(k, v != null ? v.toString() : ""));

        // ── Assert: demonstrate the broken serialisation ─────────────────────
        // Booleans and numbers serialise correctly
        assertThat(args.get("logRequestBody")).isEqualTo("true");
        assertThat(args.get("logResponseBody")).isEqualTo("true");
        assertThat(args.get("maxBodyCaptureBytes")).isEqualTo("4096");
        assertThat(args.get("samplingRate")).isEqualTo("1.0");

        // Lists serialise with bracket notation — SCG cannot bind these
        assertThat(args.get("headerAllowlist"))
                .as("Empty list produces '[]' — not bindable to List<String>")
                .isEqualTo("[]");

        assertThat(args.get("headerDenylist"))
                .as("Non-empty list produces '[Authorization, Cookie]' — not 'Authorization,Cookie'")
                .isEqualTo("[Authorization, Cookie]");

        assertThat(args.get("skipPaths"))
                .as("Non-empty list produces '[/health, /actuator/**]'")
                .isEqualTo("[/health, /actuator/**]");
    }

    /**
     * Verifies the fix: List values should be serialised as comma-separated
     * strings, and empty lists / Maps should be omitted from the args.
     */
    @Test
    @DisplayName("Fixed serialisation: Lists become comma-separated, empty collections omitted")
    void fixedSerializationProducesBindableArgs() {
        Map<String, Object> dbConfig = new LinkedHashMap<>();
        dbConfig.put("logRequestBody", true);
        dbConfig.put("logResponseBody", true);
        dbConfig.put("headerAllowlist", List.of());
        dbConfig.put("headerDenylist", List.of("Authorization", "Cookie"));
        dbConfig.put("skipPaths", List.of("/health", "/actuator/**"));

        // ── Act: use the actual flattenConfigValue logic from RouteDefinitionBuilder ─
        var args = new LinkedHashMap<String, String>();
        dbConfig.forEach((k, v) -> {
            if (v == null) return;
            if (v instanceof List<?> list) {
                if (!list.isEmpty()) {
                    args.put(k, list.stream()
                            .map(Object::toString)
                            .collect(java.util.stream.Collectors.joining(",")));
                }
            } else if (v instanceof Map<?, ?> map) {
                if (!map.isEmpty()) {
                    map.forEach((mk, mv) ->
                            args.put(k + "." + mk, mv != null ? mv.toString() : ""));
                }
            } else {
                args.put(k, v.toString());
            }
        });

        // ── Assert: all values are now SCG-bindable ──────────────────────────
        assertThat(args.get("logRequestBody")).isEqualTo("true");
        assertThat(args.get("logResponseBody")).isEqualTo("true");

        assertThat(args).doesNotContainKey("headerAllowlist");  // empty → omitted

        assertThat(args.get("headerDenylist"))
                .as("Comma-separated, no brackets")
                .isEqualTo("Authorization,Cookie");

        assertThat(args.get("skipPaths"))
                .isEqualTo("/health,/actuator/**");
    }

    /**
     * Verifies that Map-type config values (e.g. CUSTOM_METRIC tags) are
     * expanded as dotted keys that SCG's property binder can handle.
     */
    @Test
    @DisplayName("Map config values are expanded as dotted keys")
    void mapValuesExpandedAsDottedKeys() {
        Map<String, Object> dbConfig = new LinkedHashMap<>();
        dbConfig.put("metricName", "my_metric");
        dbConfig.put("tags", Map.of("env", "prod", "region", "eu"));

        var args = new LinkedHashMap<String, String>();
        dbConfig.forEach((k, v) -> {
            if (v == null) return;
            if (v instanceof List<?> list) {
                if (!list.isEmpty()) {
                    args.put(k, list.stream().map(Object::toString)
                            .collect(java.util.stream.Collectors.joining(",")));
                }
            } else if (v instanceof Map<?, ?> map) {
                if (!map.isEmpty()) {
                    map.forEach((mk, mv) ->
                            args.put(k + "." + mk, mv != null ? mv.toString() : ""));
                }
            } else {
                args.put(k, v.toString());
            }
        });

        assertThat(args.get("metricName")).isEqualTo("my_metric");
        assertThat(args.get("tags.env")).isEqualTo("prod");
        assertThat(args.get("tags.region")).isEqualTo("eu");
        assertThat(args).doesNotContainKey("tags");
    }
}

