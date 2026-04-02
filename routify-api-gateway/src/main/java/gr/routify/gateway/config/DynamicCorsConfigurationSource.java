package gr.routify.gateway.config;

import lombok.extern.slf4j.Slf4j;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.lang.NonNull;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.reactive.CorsConfigurationSource;
import org.springframework.web.server.ServerWebExchange;

import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * A reactive {@link CorsConfigurationSource} that resolves CORS settings
 * dynamically on every request from the live {@link GatewayConfigLoader} snapshot.
 *
 * <h3>Why dynamic?</h3>
 * <p>The standard {@code UrlBasedCorsConfigurationSource} is wired once at startup
 * and ignores any subsequent changes.  When an admin updates CORS settings via the
 * dashboard, {@link GatewayConfigLoader} receives a {@code GatewayConfigChanged}
 * Kafka event and atomically refreshes its in-memory snapshot — this source picks
 * up the new config on the very next preflight or cross-origin request without
 * requiring a gateway restart.
 *
 * <h3>Fallback chain</h3>
 * <ol>
 *   <li>DB config (loaded from route-service via {@code GatewayConfigLoader})</li>
 *   <li>Env-var {@code CORS_ALLOWED_ORIGINS} — used when DB config is absent/disabled</li>
 *   <li>Hard-coded default {@code http://localhost:5173}</li>
 * </ol>
 *
 * <h3>Thread safety</h3>
 * <p>{@link GatewayConfigLoader#getConfig()} returns the current value of an
 * {@code AtomicReference} — this read is always safe under concurrent access.
 */
@Slf4j
public class DynamicCorsConfigurationSource implements CorsConfigurationSource {

    /** Key in the serialized GatewayConfigDto map that holds the CORS section. */
    private static final String CORS_SECTION_KEY = "cors";

    private final GatewayConfigLoader configLoader;
    /** Comma-separated allowed origins from the CORS_ALLOWED_ORIGINS env var. */
    private final String              fallbackOrigins;

    public DynamicCorsConfigurationSource(GatewayConfigLoader configLoader,
                                          String fallbackOrigins) {
        this.configLoader    = configLoader;
        this.fallbackOrigins = fallbackOrigins != null ? fallbackOrigins : "http://localhost:5173";
    }

    @Override
    public CorsConfiguration getCorsConfiguration(@NonNull ServerWebExchange exchange) {
        CorsConfig dbCors = loadFromDb();

        if (dbCors != null && dbCors.isEnabled()) {
            return buildFromDbConfig(dbCors, exchange.getRequest());
        }

        // Fallback: env-var / hard-coded defaults
        log.debug("CORS: DB config absent or disabled — using fallback origins: {}", fallbackOrigins);
        return buildFallback();
    }

    // ─── Private helpers ──────────────────────────────────────────────────────

    /**
     * Reads the {@code cors} section from the in-memory config snapshot.
     * Returns {@code null} if the loader has not yet received a config or the
     * section is missing.
     */
    @SuppressWarnings("unchecked")
    private CorsConfig loadFromDb() {
        try {
            Map<String, Object> config = configLoader.getConfig();
            if (config == null || config.isEmpty()) return null;

            Object corsRaw = config.get(CORS_SECTION_KEY);
            if (!(corsRaw instanceof Map<?, ?> corsMap)) return null;

            return CorsConfig.fromMap((Map<String, Object>) corsMap);
        } catch (Exception e) {
            log.warn("CORS: failed to read cors section from GatewayConfigLoader: {}", e.getMessage());
            return null;
        }
    }

    private CorsConfiguration buildFromDbConfig(CorsConfig cors, ServerHttpRequest request) {
        CorsConfiguration cfg = new CorsConfiguration();

        // Origins
        List<String> origins = cors.getAllowedOriginPatterns();
        if (origins != null && !origins.isEmpty()) {
            origins.forEach(o -> cfg.addAllowedOriginPattern(o.trim()));
        } else {
            applyFallbackOrigins(cfg);
        }

        // Methods — default to standard set when not configured
        List<String> methods = cors.getAllowedMethods();
        cfg.setAllowedMethods(
                (methods != null && !methods.isEmpty())
                        ? methods
                        : List.of("GET", "POST", "PUT", "DELETE", "PATCH", "OPTIONS"));

        // Headers
        List<String> headers = cors.getAllowedHeaders();
        if (headers != null && !headers.isEmpty()) {
            headers.forEach(cfg::addAllowedHeader);
        } else {
            cfg.addAllowedHeader("*");
        }

        // Exposed headers
        List<String> exposed = cors.getExposedHeaders();
        if (exposed != null && !exposed.isEmpty()) {
            cfg.setExposedHeaders(exposed);
        }

        cfg.setAllowCredentials(cors.isAllowCredentials());
        cfg.setMaxAge(cors.getMaxAge() > 0 ? cors.getMaxAge() : 3600L);

        log.debug("CORS: resolved dynamic config for path={} origins={}",
                request.getPath(), cfg.getAllowedOriginPatterns());
        return cfg;
    }

    private CorsConfiguration buildFallback() {
        CorsConfiguration cfg = new CorsConfiguration();
        applyFallbackOrigins(cfg);
        cfg.setAllowedMethods(List.of("GET", "POST", "PUT", "DELETE", "PATCH", "OPTIONS"));
        cfg.addAllowedHeader("*");
        cfg.setExposedHeaders(List.of("X-Correlation-Id", "X-Route-Version"));
        cfg.setAllowCredentials(true);
        cfg.setMaxAge(3600L);
        return cfg;
    }

    private void applyFallbackOrigins(CorsConfiguration cfg) {
        for (String origin : fallbackOrigins.split(",")) {
            cfg.addAllowedOriginPattern(origin.trim());
        }
    }

    // ─── Inner DTO — mirrors GatewayConfigDto.CorsConfig without a cross-module dep ──

    /**
     * Lightweight mirror of {@code GatewayConfigDto.CorsConfig}.
     * Parsed manually from the raw {@code Map<String, Object>} stored in
     * {@link GatewayConfigLoader} so the gateway module stays independent of
     * the admin-api DTO classes.
     */
    static final class CorsConfig {

        private final boolean      enabled;
        private final List<String> allowedOriginPatterns;
        private final List<String> allowedMethods;
        private final List<String> allowedHeaders;
        private final List<String> exposedHeaders;
        private final boolean      allowCredentials;
        private final long         maxAge;
        private final List<String> paths;

        private CorsConfig(boolean enabled,
                           List<String> allowedOriginPatterns,
                           List<String> allowedMethods,
                           List<String> allowedHeaders,
                           List<String> exposedHeaders,
                           boolean allowCredentials,
                           long maxAge,
                           List<String> paths) {
            this.enabled               = enabled;
            this.allowedOriginPatterns = allowedOriginPatterns;
            this.allowedMethods        = allowedMethods;
            this.allowedHeaders        = allowedHeaders;
            this.exposedHeaders        = exposedHeaders;
            this.allowCredentials      = allowCredentials;
            this.maxAge                = maxAge;
            this.paths                 = paths;
        }

        static CorsConfig fromMap(Map<String, Object> m) {
            if (m == null) return null;
            return new CorsConfig(
                    bool(m.get("enabled")),
                    stringList(m.get("allowedOriginPatterns")),
                    stringList(m.get("allowedMethods")),
                    stringList(m.get("allowedHeaders")),
                    stringList(m.get("exposedHeaders")),
                    bool(m.get("allowCredentials")),
                    longVal(m.get("maxAge")),
                    stringList(m.get("paths"))
            );
        }

        boolean      isEnabled()                  { return enabled; }
        List<String> getAllowedOriginPatterns()    { return allowedOriginPatterns; }
        List<String> getAllowedMethods()           { return allowedMethods; }
        List<String> getAllowedHeaders()           { return allowedHeaders; }
        List<String> getExposedHeaders()          { return exposedHeaders; }
        boolean      isAllowCredentials()         { return allowCredentials; }
        long         getMaxAge()                  { return maxAge; }

        // paths stored for future per-path registration support
        @SuppressWarnings("unused")
        List<String> getPaths()                   { return paths; }

        // ─── Parsing helpers ──────────────────────────────────────────────────

        private static boolean bool(Object v) {
            if (v instanceof Boolean b) return b;
            if (v instanceof String  s) return Boolean.parseBoolean(s);
            return false;
        }

        private static long longVal(Object v) {
            if (v instanceof Number n) return n.longValue();
            if (v instanceof String s) {
                try { return Long.parseLong(s); } catch (NumberFormatException ignored) {}
            }
            return 0L;
        }

        private static List<String> stringList(Object v) {
            if (v instanceof List<?> list) {
                return list.stream()
                           .filter(Objects::nonNull)
                           .map(Object::toString)
                           .toList();
            }
            return null;
        }
    }
}

