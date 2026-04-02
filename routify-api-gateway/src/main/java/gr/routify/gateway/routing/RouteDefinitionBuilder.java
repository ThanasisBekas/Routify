package gr.routify.gateway.routing;

import gr.routify.gateway.config.GatewayConfigLoader;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.cloud.gateway.filter.FilterDefinition;
import org.springframework.cloud.gateway.handler.predicate.PredicateDefinition;
import org.springframework.cloud.gateway.route.RouteDefinition;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.util.*;
import java.util.regex.Pattern;

/**
 * Builds Spring Cloud Gateway {@link RouteDefinition}s from route snapshots.
 *
 * <p>This is where FilterType values from the database are mapped to
 * Spring Cloud Gateway filter definitions. Each {@link RouteSnapshotDto.FilterSnapshotDto}
 * becomes a chain of SCG filters applied in order.
 *
 * <p>When a filter has a {@code gatewayConfigRef}, the {@link GatewayConfigRefResolver}
 * is used to look up the referenced gateway config entry (e.g. an Auth Provider) and merge
 * its values into the filter config before the filter factory receives it. This allows
 * filters to be linked to centrally-managed gateway config rather than duplicating
 * credentials or policy settings per-filter.
 *
 * <h3>Tenant Isolation:</h3>
 * Whether the {@code Header=X-Tenant-Id} predicate is added to each route is controlled
 * by the {@code tenantIsolation} section of the persisted gateway config
 * (loaded by {@link GatewayConfigLoader}). When {@code enabled=false} or
 * {@code enforceHeaderPredicate=false} the predicate is omitted and requests reach
 * the route regardless of whether they carry the header.
 *
 * <h3>Strategy Pattern for Filter Building:</h3>
 * Each filter type maps to one or more Spring Cloud Gateway built-in filters
 * or our custom {@link gr.routify.gateway.filter} implementations.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class RouteDefinitionBuilder {

    private static final String DEFAULT_TENANT_HEADER = "X-Tenant-Id";

    private final GatewayConfigRefResolver configRefResolver;
    private final GatewayConfigLoader      configLoader;

    /**
     * Builds a complete Spring Cloud Gateway {@link RouteDefinition} from a snapshot.
     *
     * @param snapshot the route snapshot from routify-route-service
     * @return a fully configured RouteDefinition ready for SCG routing
     */
    public RouteDefinition build(RouteSnapshotDto snapshot) {
        RouteDefinition definition = new RouteDefinition();

        // Route ID — prefix with tenant for namespace isolation
        definition.setId("%s::%s".formatted(snapshot.tenantId(), snapshot.routeId()));
        definition.setUri(URI.create(snapshot.upstreamUri()));
        definition.setOrder(0);

        // ─── Tenant isolation config ──────────────────────────────────────────
        TenantIsolationSettings isolation = resolveTenantIsolationSettings();

        // ─── Predicates ──────────────────────────────────────────────────────
        List<PredicateDefinition> predicates = new ArrayList<>();

        // Path predicate
        predicates.add(new PredicateDefinition(
                "Path=%s".formatted(snapshot.pathPattern())));

        // Method predicate (skip if *)
        if (snapshot.methods() != null && !"*".equals(snapshot.methods())) {
            predicates.add(new PredicateDefinition(
                    "Method=%s".formatted(snapshot.methods())));
        }

        // Tenant header predicate — only added when isolation is enabled AND
        // enforceHeaderPredicate is true (both controlled via Gateway Config UI).
        // The UUID is wrapped in ^…$ anchors and Pattern.quote() so it is treated
        // as a literal (not a raw regex) and cannot be spoofed by a partial match.
        if (isolation.enabled() && isolation.enforceHeaderPredicate()) {
            predicates.add(new PredicateDefinition(
                    "Header=%s,^%s$".formatted(
                            isolation.tenantIdHeader(),
                            Pattern.quote(snapshot.tenantId().toString()))));
            log.debug("Tenant header predicate added: {}={} for route {}",
                    isolation.tenantIdHeader(), snapshot.tenantId(), snapshot.routeId());
        } else {
            log.debug("Tenant header predicate SKIPPED for route {} (enabled={} enforce={})",
                    snapshot.routeId(), isolation.enabled(), isolation.enforceHeaderPredicate());
        }

        definition.setPredicates(predicates);

        // ─── Filters ─────────────────────────────────────────────────────────
        List<FilterDefinition> filters = new ArrayList<>();

        // Strip prefix from route-level flag — only when the filter chain does NOT already
        // contain a PATH_STRIP_PREFIX filter to avoid double-stripping.
        boolean chainHasStripPrefix = snapshot.filters() != null && snapshot.filters().stream()
                .anyMatch(f -> "PATH_STRIP_PREFIX".equals(f.filterType()));
        if (!chainHasStripPrefix && snapshot.stripPrefix() != null && !snapshot.stripPrefix().isBlank()) {
            var stripFilter = new FilterDefinition();
            stripFilter.setName("StripPrefix");
            stripFilter.setArgs(Map.of("parts", "1"));
            filters.add(stripFilter);
        }

        // Dynamic filters from the route's filter chain
        if (snapshot.filters() != null) {
            snapshot.filters().stream()
                    .sorted(Comparator.comparingInt(RouteSnapshotDto.FilterSnapshotDto::order))
                    .map(this::buildFilterDefinition)
                    .filter(Objects::nonNull)
                    .forEach(filters::add);
        }


        definition.setFilters(filters);

        // ─── Metadata ────────────────────────────────────────────────────────
        Map<String, Object> metadata = new HashMap<>();
        metadata.put("tenantId", snapshot.tenantId().toString());
        metadata.put("routeName", snapshot.name());
        metadata.put("routeVersion", snapshot.version());
        if (snapshot.extraConfig() != null) {
            metadata.putAll(snapshot.extraConfig());
        }
        definition.setMetadata(metadata);

        log.debug("Built RouteDefinition: id={} path={} upstream={}",
                definition.getId(), snapshot.pathPattern(), snapshot.upstreamUri());

        return definition;
    }

    /**
     * Maps a filter snapshot to a Spring Cloud Gateway FilterDefinition.
     *
     * <p>If the filter has a {@code gatewayConfigRef}, the config is first enriched
     * by merging the referenced gateway config entry's values before the filter
     * factory receives it. Local config values override ref values for per-filter
     * customisation.
     *
     * Uses Java 21 switch expression for exhaustive pattern matching on filter types.
     */
    private FilterDefinition buildFilterDefinition(RouteSnapshotDto.FilterSnapshotDto filter) {
        // Resolve gatewayConfigRef and merge into the effective config
        Map<String, Object> cfg = configRefResolver.resolve(
                filter.config(), filter.gatewayConfigRef());

        return switch (filter.filterType()) {
            // ─── Authentication ───────────────────────────────────────────────
            case "AUTH_JWT" -> customFilter("JwtAuth", cfg);
            case "AUTH_API_KEY" -> customFilter("ApiKeyAuth", cfg);
            case "AUTH_BASIC" -> customFilter("BasicAuth", cfg);
            case "AUTH_OAUTH2" -> customFilter("OAuth2TokenIntrospect", cfg);
            case "AUTH_MTLS" -> customFilter("MtlsAuth", cfg);
            case "AUTH_CLIENT_ID" -> customFilter("ClientIdAuth", cfg);
            case "AUTH_NONE" -> null; // No filter needed

            // ─── Downstream Auth Injection ────────────────────────────────────
            case "DOWNSTREAM_BASIC_AUTH" -> customFilter("DownstreamBasicAuth", cfg);
            case "DOWNSTREAM_BEARER_CC"  -> customFilter("DownstreamOAuth2Bearer", cfg);

            // ─── Rate Limiting ────────────────────────────────────────────────
            case "RATE_LIMIT_TOKEN_BUCKET" -> {
                var f = new FilterDefinition();
                f.setName("RequestRateLimiter");

                int replenishRate    = toInt(cfg.getOrDefault("replenishRate",    10), 10);
                int burstCapacity    = toInt(cfg.getOrDefault("burstCapacity",    20), 20);
                int requestedTokens  = toInt(cfg.getOrDefault("requestedTokens",   1),  1);

                // burstCapacity must be >= replenishRate (SCG constraint in RedisRateLimiter.Config)
                if (burstCapacity < replenishRate) {
                    log.warn("RATE_LIMIT_TOKEN_BUCKET: burstCapacity({}) < replenishRate({}) — clamping to replenishRate",
                            burstCapacity, replenishRate);
                    burstCapacity = replenishRate;
                }

                var args = new LinkedHashMap<String, String>();
                args.put("redis-rate-limiter.replenishRate",   String.valueOf(replenishRate));
                args.put("redis-rate-limiter.burstCapacity",   String.valueOf(burstCapacity));
                args.put("redis-rate-limiter.requestedTokens", String.valueOf(requestedTokens));
                args.put("key-resolver",                       resolveRateLimitKeyResolver(cfg));

                log.debug("RATE_LIMIT_TOKEN_BUCKET filter: replenishRate={} burstCapacity={} requestedTokens={} keyResolver={}",
                        replenishRate, burstCapacity, requestedTokens, args.get("key-resolver"));

                f.setArgs(args);
                yield f;
            }
            case "RATE_LIMIT_SLIDING_WINDOW" -> customFilter("SlidingWindowRateLimit", cfg);
            case "RATE_LIMIT_FIXED_WINDOW" -> customFilter("FixedWindowRateLimit", cfg);

            // ─── Request/Response Modification ───────────────────────────────
            case "REQUEST_HEADER_MODIFY" -> customFilter("RequestHeaderModify", cfg);
            case "RESPONSE_HEADER_MODIFY" -> customFilter("ResponseHeaderModify", cfg);
            case "PATH_REWRITE" -> {
                var f = new FilterDefinition();
                f.setName("RewritePath");
                f.setArgs(Map.of(
                        "regexp",      String.valueOf(cfg.getOrDefault("regexp", "(?<path>.*)")),
                        "replacement", String.valueOf(cfg.getOrDefault("replacement", "/${path}"))
                ));
                yield f;
            }
            case "PATH_STRIP_PREFIX" -> {
                var f = new FilterDefinition();
                f.setName("StripPrefix");
                f.setArgs(Map.of("parts", String.valueOf(cfg.getOrDefault("parts", "1"))));
                yield f;
            }
            case "PATH_ADD_PREFIX" -> {
                var f = new FilterDefinition();
                f.setName("PrefixPath");
                f.setArgs(Map.of("prefix", String.valueOf(cfg.getOrDefault("prefix", ""))));
                yield f;
            }
            case "QUERY_PARAM_MODIFY" -> customFilter("QueryParamModify", cfg);

            // ─── Body Transformation ──────────────────────────────────────────
            case "BODY_JOLT_TRANSFORM" -> customFilter("JoltTransform", cfg);
            case "BODY_JSONATA_TRANSFORM" -> customFilter("JsonataTransform", cfg);
            case "BODY_SPEL_TRANSFORM" -> customFilter("SpelTransform", cfg);

            // ─── Validation ───────────────────────────────────────────────────
            case "VALIDATE_JSON_SCHEMA" -> customFilter("JsonSchemaValidate", cfg);
            case "VALIDATE_REGEX" -> customFilter("RegexValidate", cfg);
            case "VALIDATE_SIZE" -> {
                var f = new FilterDefinition();
                f.setName("RequestSize");
                f.setArgs(Map.of("maxSize",
                        String.valueOf(cfg.getOrDefault("maxSize", "5MB"))));
                yield f;
            }

            // ─── Resilience ───────────────────────────────────────────────────
            case "CIRCUIT_BREAKER" -> {
                var f = new FilterDefinition();
                f.setName("CircuitBreaker");
                f.setArgs(Map.of(
                        "name",        String.valueOf(cfg.getOrDefault("name", "default")),
                        "fallbackUri", String.valueOf(cfg.getOrDefault("fallbackUri", "forward:/fallback/503"))
                ));
                yield f;
            }
            case "RETRY" -> {
                var f = new FilterDefinition();
                f.setName("Retry");
                // SCG Retry filter expects: retries, series (HttpStatus.Series names), methods (HTTP method names).
                // "statuses" is NOT a valid SCG Retry arg — use "series" and "methods" instead.
                var args = new LinkedHashMap<String, String>();
                args.put("retries", String.valueOf(cfg.getOrDefault("retries", "3")));
                args.put("series",  String.valueOf(cfg.getOrDefault("series",  "SERVER_ERROR")));
                args.put("methods", String.valueOf(cfg.getOrDefault("methods", "GET,HEAD,OPTIONS")));
                // Optional: specific status codes to retry on (comma-separated, e.g. "500,502,503")
                Object statuses = cfg.get("statuses");
                if (statuses != null && !statuses.toString().isBlank()) {
                    args.put("statuses", statuses.toString());
                }
                f.setArgs(args);
                yield f;
            }
            case "TIMEOUT" -> customFilter("RequestTimeout", cfg);

            // ─── Routing ─────────────────────────────────────────────────────────
            case "CONDITIONAL_ROUTE"        -> customFilter("ConditionalRoute", cfg);
            case "USER_ID_PAYLOAD_ROUTING"  -> customFilter("UserIdPayloadRouting", cfg);

            // ─── Certificates / TLS ───────────────────────────────────────────
            case "AUTH_CERT_VAULT"           -> customFilter("CertVaultAuth", cfg);
            case "CERT_ROTATION"             -> customFilter("CertRotation", cfg);
            case "CERT_VAULT_EXPIRY_CHECK"   -> customFilter("CertVaultExpiryCheck", cfg);

            // ─── Versioning ───────────────────────────────────────────────────
            case "API_VERSIONING" -> customFilter("ApiVersioning", cfg);

            // ─── Observability ────────────────────────────────────────────────
            case "CORRELATION_ID"   -> namedFilter("CorrelationId");
            case "REQUEST_LOGGER"   -> customFilter("RequestLogger", cfg);
            case "TENANT_CONTEXT"   -> namedFilter("TenantContext");
            case "SECURITY_HEADERS" -> namedFilter("SecurityHeaders");
            case "CUSTOM_METRIC"    -> customFilter("CustomMetric", cfg);

            // ─── Custom ───────────────────────────────────────────────────────
            case "CUSTOM_SPEL" -> customFilter("SpelCustom", cfg);

            default -> {
                log.warn("Unknown filter type '{}' — skipping", filter.filterType());
                yield null;
            }
        };
    }

    private static FilterDefinition namedFilter(String name) {
        var f = new FilterDefinition();
        f.setName(name);
        f.setArgs(Map.of());
        return f;
    }

    private static FilterDefinition customFilter(String name, Map<String, Object> config) {
        var f = new FilterDefinition();
        f.setName(name);
        // Convert all config values to String for SCG compatibility
        var args = new LinkedHashMap<String, String>();
        config.forEach((k, v) -> args.put(k, v != null ? v.toString() : ""));
        f.setArgs(args);
        return f;
    }

    private static String resolveRateLimitKeyResolver(Map<String, Object> cfg) {
        String keyResolver = String.valueOf(cfg.getOrDefault("keyResolver", "IP"));
        return switch (keyResolver) {
            case "USER"        -> "#{@userKeyResolver}";
            case "TENANT"      -> "#{@tenantKeyResolver}";
            case "API_KEY"     -> "#{@apiKeyResolver}";
            case "TENANT_USER" -> "#{@tenantUserKeyResolver}";
            default            -> "#{@ipKeyResolver}";
        };
    }

    /**
     * Safely converts an Object config value to int, falling back to {@code defaultValue}
     * on null or unparseable values.
     */
    private static int toInt(Object value, int defaultValue) {
        if (value == null) return defaultValue;
        if (value instanceof Number n) return n.intValue();
        try {
            return Integer.parseInt(value.toString().trim());
        } catch (NumberFormatException e) {
            log.warn("Rate limiter config: could not parse '{}' as int — using default {}", value, defaultValue);
            return defaultValue;
        }
    }

    // ─── Tenant Isolation ─────────────────────────────────────────────────────

    /**
     * Reads the {@code tenantIsolation} section from the live gateway config.
     * Falls back to safe defaults (isolation ON, default header) if the config
     * is not yet loaded or the section is missing.
     */
    @SuppressWarnings("unchecked")
    private TenantIsolationSettings resolveTenantIsolationSettings() {
        Map<String, Object> gwConfig = configLoader.getConfig();
        if (gwConfig == null || gwConfig.isEmpty()) {
            log.debug("Gateway config not loaded — using default tenant isolation (enabled=true)");
            return TenantIsolationSettings.DEFAULTS;
        }

        Object raw = gwConfig.get("tenantIsolation");
        if (!(raw instanceof Map<?, ?> rawMap)) {
            log.debug("tenantIsolation section not found in gateway config — using defaults");
            return TenantIsolationSettings.DEFAULTS;
        }

        Map<String, Object> ti = (Map<String, Object>) rawMap;

        boolean enabled                = toBool(ti.get("enabled"),                true);
        boolean enforceHeaderPredicate = toBool(ti.get("enforceHeaderPredicate"), true);
        String  tenantIdHeader         = ti.get("tenantIdHeader") instanceof String s && !s.isBlank()
                ? s : DEFAULT_TENANT_HEADER;

        return new TenantIsolationSettings(enabled, enforceHeaderPredicate, tenantIdHeader);
    }

    private static boolean toBool(Object value, boolean defaultValue) {
        if (value == null)           return defaultValue;
        if (value instanceof Boolean b) return b;
        return Boolean.parseBoolean(value.toString().trim());
    }

    /** Immutable snapshot of the tenant isolation settings for a single route-build call. */
    private record TenantIsolationSettings(boolean enabled,
                                           boolean enforceHeaderPredicate,
                                           String tenantIdHeader) {
        static final TenantIsolationSettings DEFAULTS =
                new TenantIsolationSettings(true, true, DEFAULT_TENANT_HEADER);
    }
}
