package io.routify.gateway.routing;

import io.routify.common.web.RoutifyHeaders;
import io.routify.gateway.config.GatewayConfigLoader;
import io.routify.gateway.filter.TenantContextGatewayFilterFactory;
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
 * by the {@code tenantIsolation.enabled} flag in the persisted gateway config
 * (loaded by {@link GatewayConfigLoader}). When {@code enabled=true} callers must
 * supply the tenant header and the predicate is added. When {@code enabled=false}
 * the predicate is omitted and the
 * {@link TenantContextGatewayFilterFactory TENANT_CONTEXT}
 * filter auto-injects the tenant from route metadata. The resolved header is always
 * forwarded to the upstream destination.
 *
 * <h3>Strategy Pattern for Filter Building:</h3>
 * Each filter type maps to one or more Spring Cloud Gateway built-in filters
 * or our custom {@link io.routify.gateway.filter} implementations.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class RouteDefinitionBuilder {

    private static final String DEFAULT_TENANT_HEADER = RoutifyHeaders.TENANT_ID;

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

        // Tenant header predicate — added when enabled is true (caller provides
        // X-Tenant-Id). When enabled=false the caller does not supply the header and
        // the TenantContext filter auto-injects it from route metadata instead.
        // The UUID is wrapped in ^…$ anchors and Pattern.quote() so it is treated
        // as a literal (not a raw regex) and cannot be spoofed by a partial match.
        if (isolation.enabled()) {
            predicates.add(new PredicateDefinition(
                    "Header=%s,^%s$".formatted(
                            isolation.tenantIdHeader(),
                            Pattern.quote(snapshot.tenantId().toString()))));
            log.debug("Tenant header predicate added: {}={} for route {}",
                    isolation.tenantIdHeader(), snapshot.tenantId(), snapshot.routeId());
        } else {
            log.debug("Tenant header predicate SKIPPED for route {} (enabled={})",
                    snapshot.routeId(), isolation.enabled());
        }

        // Staging environment predicate — only match staging routes when
        // the request carries the X-Route-Environment: STAGING header.
        // Production routes get NO extra predicate (they match by default).
        StagingSettings staging = resolveStagingSettings();
        if ("STAGING".equalsIgnoreCase(snapshot.environment()) && staging.enabled()) {
            predicates.add(new PredicateDefinition(
                    "Header=%s, STAGING".formatted(staging.headerName())));
            log.debug("Staging predicate added for route {}: header={}",
                    snapshot.routeId(), staging.headerName());
        }

        // ─── Canary / Weighted routing predicate ──────────────────────────────
        // When a route participates in a canary deployment (trafficWeight < 100),
        // add a Weight predicate. Both primary (e.g. 90) and canary (e.g. 10)
        // routes get a Weight predicate in the same group. Spring Cloud Gateway's
        // WeightRoutePredicateFactory handles probabilistic selection.
        if (snapshot.trafficWeight() < 100) {
            // The weight group is derived from the primary route's ID.
            // For the primary route: canaryRouteId is set, so use own routeId.
            // For the canary route: canaryRouteId is null, check extraConfig for primaryRouteId.
            UUID groupRouteId = snapshot.canaryRouteId() != null
                    ? snapshot.routeId()   // This is the primary — group by own ID
                    : (snapshot.extraConfig() != null && snapshot.extraConfig().get("canaryPrimaryRouteId") != null
                        ? UUID.fromString(snapshot.extraConfig().get("canaryPrimaryRouteId").toString())
                        : snapshot.routeId());
            String weightGroup = "canary-" + groupRouteId;
            predicates.add(new PredicateDefinition(
                    "Weight=%s, %d".formatted(weightGroup, snapshot.trafficWeight())));
            log.debug("Weight predicate added for route {}: group={} weight={}",
                    snapshot.routeId(), weightGroup, snapshot.trafficWeight());
        }

        definition.setPredicates(predicates);

        // ─── Filters ─────────────────────────────────────────────────────────
        List<FilterDefinition> filters = new ArrayList<>();

        // Global filter entries — operator-selected filters applied to all routes.
        // They execute before per-route filters, in the order defined by the operator.
        resolveGlobalFilterEntries().stream()
                .map(entry -> buildFilterDefinitionFromGlobalEntry(snapshot, entry))
                .filter(Objects::nonNull)
                .forEach(filters::add);

        // Strip prefix from route-level flag — derive the number of path segments to strip
        // from the stored prefix string (e.g. "/api/v1" → 2 parts, "/api" → 1 part).
        // Only add when the filter chain does NOT already contain a PATH_STRIP_PREFIX filter
        // to avoid double-stripping.
        boolean chainHasStripPrefix = snapshot.filters() != null && snapshot.filters().stream()
                .anyMatch(f -> "PATH_STRIP_PREFIX".equals(f.filterType()));
        if (!chainHasStripPrefix && snapshot.stripPrefix() != null && !snapshot.stripPrefix().isBlank()) {
            int parts = countPathSegments(snapshot.stripPrefix());
            if (parts > 0) {
                var stripFilter = new FilterDefinition();
                stripFilter.setName("StripPrefix");
                stripFilter.setArgs(Map.of("parts", String.valueOf(parts)));
                filters.add(stripFilter);
                log.debug("StripPrefix filter added: prefix='{}' parts={} for route {}",
                        snapshot.stripPrefix(), parts, snapshot.routeId());
            } else {
                log.warn("stripPrefix='{}' for route {} resolved to 0 segments — StripPrefix filter skipped",
                        snapshot.stripPrefix(), snapshot.routeId());
            }
        }

        // Dynamic filters from the route's filter chain
        if (snapshot.filters() != null) {
            snapshot.filters().stream()
                    .sorted(Comparator.comparingInt(RouteSnapshotDto.FilterSnapshotDto::order))
                    .map(f -> buildFilterDefinition(snapshot, f))
                    .filter(Objects::nonNull)
                    .forEach(filters::add);
        }


        definition.setFilters(filters);

        // ─── Metadata ────────────────────────────────────────────────────────
        Map<String, Object> metadata = new HashMap<>();
        metadata.put("tenantId", snapshot.tenantId().toString());
        metadata.put("routeName", snapshot.name());
        metadata.put("routeVersion", snapshot.version());
        metadata.put("environment", snapshot.environment() != null ? snapshot.environment() : "PRODUCTION");
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
    private FilterDefinition buildFilterDefinition(RouteSnapshotDto snapshot,
                                                    RouteSnapshotDto.FilterSnapshotDto filter) {
        // Resolve gatewayConfigRef and merge into the effective config
        Map<String, Object> cfg = configRefResolver.resolve(
                filter.config(), filter.gatewayConfigRef());

        return switch (filter.filterType()) {
            // ─── Authentication ───────────────────────────────────────────────
            case "AUTH_JWT" -> customFilter("JwtAuth", cfg);
            case "AUTH_API_KEY" -> customFilter("ApiKeyAuth", cfg);
            case "AUTH_BASIC" -> customFilter("BasicAuth", cfg);
            case "AUTH_OAUTH2" -> customFilter("OAuth2TokenIntrospect", cfg);
            case "AUTH_MTLS" -> indexedValuesFilter("MtlsAuth", cfg);
            case "AUTH_CLIENT_ID" -> indexedValuesFilter("ClientIdAuth", cfg);
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
            case "QUERY_PARAM_MODIFY" -> {
                log.warn("Deprecated filter type QUERY_PARAM_MODIFY — ignored (no factory implementation)");
                yield null;
            }

            // ─── Body Transformation ──────────────────────────────────────────
            case "BODY_JOLT_TRANSFORM" -> customFilter("JoltTransform", cfg);
            case "BODY_JSONATA_TRANSFORM" -> {
                log.warn("Deprecated filter type BODY_JSONATA_TRANSFORM — ignored (no factory implementation)");
                yield null;
            }
            case "BODY_SPEL_TRANSFORM" -> {
                log.warn("Deprecated filter type BODY_SPEL_TRANSFORM — ignored (no factory implementation)");
                yield null;
            }

            // ─── Validation ───────────────────────────────────────────────────
            case "VALIDATE_JSON_SCHEMA" -> customFilter("JsonSchemaValidate", cfg);
            case "REQUEST_SIZE_LIMIT"   -> customFilter("RequestSizeLimit", cfg);
            case "VALIDATE_REGEX" -> {
                log.warn("Deprecated filter type VALIDATE_REGEX — ignored (no factory implementation)");
                yield null;
            }
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

            // ─── Performance ──────────────────────────────────────────────────
            case "RESPONSE_CACHE"           -> customFilter("ResponseCache", cfg);

            // ─── Routing ─────────────────────────────────────────────────────────
            case "CONDITIONAL_ROUTE"        -> customFilter("ConditionalRoute", cfg);
            case "USER_ID_PAYLOAD_ROUTING"  -> customFilter("UserIdPayloadRouting", cfg);
            case "GEO_ROUTE"                -> customFilter("GeoRoute", cfg);

            // ─── Security ──────────────────────────────────────────────────────
            case "IP_ACCESS_CONTROL"         -> customFilter("IpAccessControl", cfg);

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

            // ─── Custom ───────────────────────────────────────────────────────────
            case "CUSTOM_SPEL" -> customFilter("SpelCustom", cfg);

            // ─── AI ───────────────────────────────────────────────────────────────
            case "AI_FILTER"    -> buildAiFilter(snapshot, cfg);
            case "AI_MODIFIER"  -> buildAiModifierFilter(snapshot, cfg);

            default -> {
                log.warn("Unknown filter type '{}' — skipping", filter.filterType());
                yield null;
            }
        };
    }

    /**
     * Builds a {@link FilterDefinition} for filter factories whose Config class has a
     * {@code List<SomeComplexType> values} field (e.g. {@code ClientIdAuth}, {@code MtlsAuth}).
     *
     * <p>Spring Cloud Gateway's property binder cannot convert a flat {@link String} to a
     * {@code List<NameValueConfig>} or similar complex list type. Instead we expand the
     * {@code values} entry — which must be a {@link java.util.List} of {@link java.util.Map}s —
     * into indexed args: {@code values[0].name}, {@code values[0].value}, etc.
     *
     * <p>All other config entries are serialised as flat strings via the usual
     * {@link #customFilter} path.
     *
     * @param name the SCG filter factory name
     * @param cfg  the resolved config map (may contain a {@code values} key with a List of Maps)
     */
    @SuppressWarnings("unchecked")
    private static FilterDefinition indexedValuesFilter(String name, Map<String, Object> cfg) {
        var f = new FilterDefinition();
        f.setName(name);
        var args = new LinkedHashMap<String, String>();

        cfg.forEach((k, v) -> {
            if (!"values".equals(k)) {
                args.put(k, v != null ? v.toString() : "");
                return;
            }
            // Expand values list into indexed args: values[i].fieldName = fieldValue
            if (v instanceof List<?> list) {
                for (int i = 0; i < list.size(); i++) {
                    Object entry = list.get(i);
                    if (entry instanceof Map<?, ?> entryMap) {
                        for (Map.Entry<?, ?> e : entryMap.entrySet()) {
                            args.put("values[" + i + "]." + e.getKey(),
                                    e.getValue() != null ? e.getValue().toString() : "");
                        }
                    }
                }
            } else if (v != null) {
                // Fallback: store as-is (should not happen in normal operation)
                log.warn("indexedValuesFilter({}): 'values' is not a List — storing as flat string. " +
                        "This will likely cause a BindException.", name);
                args.put(k, v.toString());
            }
        });

        f.setArgs(args);
        return f;
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

    /**
     * Counts the number of non-empty path segments in a prefix string.
     *
     * <p>Examples:
     * <ul>
     *   <li>{@code "/api/v1"} → 2</li>
     *   <li>{@code "/api"}    → 1</li>
     *   <li>{@code "/"}       → 0</li>
     *   <li>{@code "api/v1"}  → 2  (no leading slash is also handled)</li>
     * </ul>
     *
     * @param prefix the strip-prefix path stored on the route (e.g. {@code "/api/v1"})
     * @return number of non-empty segments; never negative
     */
    private static int countPathSegments(String prefix) {
        if (prefix == null || prefix.isBlank()) return 0;
        String[] segments = prefix.split("/");
        int count = 0;
        for (String s : segments) {
            if (!s.isBlank()) count++;
        }
        return count;
    }

    private static String resolveRateLimitKeyResolver(Map<String, Object> cfg) {
        String keyResolver = String.valueOf(cfg.getOrDefault("keyResolver", "IP"));
        // For new dynamic strategies (ROUTE, HEADER:<name>, COMPOSITE:<a>:<b>),
        // delegate to the ipKeyResolver as a fallback since the SCG token bucket
        // can only reference named beans. The actual key resolution for these strategies
        // is handled by our custom filter factories via RateLimitKeyResolver.
        return switch (keyResolver) {
            case "USER"        -> "#{@userKeyResolver}";
            case "TENANT"      -> "#{@tenantKeyResolver}";
            case "API_KEY"     -> "#{@apiKeyResolver}";
            case "TENANT_USER" -> "#{@tenantUserKeyResolver}";
            case "ROUTE"       -> "#{@routeKeyResolver}";
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

        boolean enabled        = toBool(ti.get("enabled"), true);
        String  tenantIdHeader = ti.get("tenantIdHeader") instanceof String s && !s.isBlank()
                ? s : DEFAULT_TENANT_HEADER;

        return new TenantIsolationSettings(enabled, tenantIdHeader);
    }

    private static boolean toBool(Object value, boolean defaultValue) {
        if (value == null)           return defaultValue;
        if (value instanceof Boolean b) return b;
        return Boolean.parseBoolean(value.toString().trim());
    }

    /** Immutable snapshot of the tenant isolation settings for a single route-build call. */
    private record TenantIsolationSettings(boolean enabled,
                                           String tenantIdHeader) {
        static final TenantIsolationSettings DEFAULTS =
                new TenantIsolationSettings(true, DEFAULT_TENANT_HEADER);
    }

    // ─── Staging Environment Settings ─────────────────────────────────────────

    /** Immutable snapshot of the staging environment settings for a single route-build call. */
    private record StagingSettings(boolean enabled, String headerName) {
        static final StagingSettings DEFAULTS = new StagingSettings(true, "X-Route-Environment");
    }

    /**
     * Reads the {@code staging} section from the live gateway config.
     * Falls back to safe defaults (staging ON, default header) if the section is missing.
     */
    @SuppressWarnings("unchecked")
    private StagingSettings resolveStagingSettings() {
        Map<String, Object> gwConfig = configLoader.getConfig();
        if (gwConfig == null || gwConfig.isEmpty()) {
            return StagingSettings.DEFAULTS;
        }

        Object raw = gwConfig.get("staging");
        if (!(raw instanceof Map<?, ?> rawMap)) {
            return StagingSettings.DEFAULTS;
        }

        Map<String, Object> s = (Map<String, Object>) rawMap;
        boolean enabled = toBool(s.get("enabled"), true);
        String headerName = s.get("headerName") instanceof String h && !h.isBlank()
                ? h : "X-Route-Environment";
        return new StagingSettings(enabled, headerName);
    }

    // ─── Global Filter Entries ───────────────────────────────────────────────

    /**
     * Immutable snapshot of a global filter entry for a single route-build call.
     */
    private record GlobalFilterEntrySnapshot(String filterId,
                                             String filterName,
                                             String filterType,
                                             int order,
                                             boolean enabled) {}

    /**
     * Reads the {@code globalFilterEntries} section from the live gateway config.
     * Returns an empty list if the section is missing or empty.
     * Only enabled entries are returned, sorted by order.
     */
    @SuppressWarnings("unchecked")
    private List<GlobalFilterEntrySnapshot> resolveGlobalFilterEntries() {
        Map<String, Object> gwConfig = configLoader.getConfig();
        if (gwConfig == null || gwConfig.isEmpty()) return List.of();

        Object raw = gwConfig.get("globalFilterEntries");
        if (!(raw instanceof List<?> rawList) || rawList.isEmpty()) return List.of();

        List<GlobalFilterEntrySnapshot> entries = new ArrayList<>();
        for (Object item : rawList) {
            if (!(item instanceof Map<?, ?> entryMap)) continue;
            Map<String, Object> m = (Map<String, Object>) entryMap;

            boolean enabled = toBool(m.get("enabled"), true);
            if (!enabled) {
                log.debug("Global filter entry '{}' is disabled — skipping", m.get("filterName"));
                continue;
            }

            String filterId   = m.get("filterId")   instanceof String s ? s : null;
            String filterName = m.get("filterName") instanceof String s ? s : "";
            String filterType = m.get("filterType") instanceof String s ? s : null;
            int order = m.get("order") instanceof Number n ? n.intValue() : 0;

            if (filterType == null || filterType.isBlank()) {
                log.warn("Global filter entry with filterId='{}' has no filterType — skipping", filterId);
                continue;
            }

            entries.add(new GlobalFilterEntrySnapshot(filterId, filterName, filterType, order, true));
        }

        entries.sort(Comparator.comparingInt(GlobalFilterEntrySnapshot::order));
        log.debug("Resolved {} enabled global filter entries", entries.size());
        return entries;
    }

    /**
     * Builds a {@link FilterDefinition} from a global filter entry.
     *
     * <p>Global filter entries reference existing filter definitions by type. Since they
     * carry no per-filter config (the config lives on the filter definition in the DB and
     * is resolved at per-route level), global entries are built as named filters with
     * empty args. For filter types that require config (e.g. rate limiters, AI filters),
     * they must be configured on individual routes instead.
     *
     * <p>Zero-config filter types (CORRELATION_ID, SECURITY_HEADERS, TENANT_CONTEXT,
     * REQUEST_LOGGER, etc.) work seamlessly as global entries because they read their
     * config from the persisted gateway config at runtime.
     */
    private FilterDefinition buildFilterDefinitionFromGlobalEntry(
            RouteSnapshotDto snapshot, GlobalFilterEntrySnapshot entry) {
        // Delegate to the same switch expression used for per-route filters.
        // Create a synthetic FilterSnapshotDto with empty config.
        var syntheticFilter = new RouteSnapshotDto.FilterSnapshotDto(
                entry.filterId() != null ? java.util.UUID.fromString(entry.filterId()) : null,
                entry.filterType(),
                entry.order(),
                "PRE",
                Map.of(),
                null);
        FilterDefinition fd = buildFilterDefinition(snapshot, syntheticFilter);
        if (fd != null) {
            log.debug("Global filter entry applied: type={} name='{}' order={} for route {}",
                    entry.filterType(), entry.filterName(), entry.order(), snapshot.routeId());
        }
        return fd;
    }

    /**
     * Builds a {@link FilterDefinition} for the {@code AI_FILTER} type.
     *
     * <p>The AI filter config is a flat key-value map (same as all other custom filters),
     * but additionally injects three route-scoped fields ({@code routeId}, {@code routeName},
     * {@code tenantId}) from the route snapshot. These are NOT stored in the JSONB config —
     * they are available from the route metadata and injected here so the gateway filter
     * can include them in the RabbitMQ RPC request without needing to look them up at
     * request time.
     */
    private FilterDefinition buildAiFilter(RouteSnapshotDto snapshot, Map<String, Object> cfg) {
        var f = new FilterDefinition();
        f.setName("AiFilter");
        var args = new LinkedHashMap<String, String>();
        // Copy all config fields from the JSONB blob
        cfg.forEach((k, v) -> args.put(k, v != null ? v.toString() : ""));
        // Inject route metadata — these override any stale values that might be in the config
        args.put("routeId",   snapshot.routeId().toString());
        args.put("routeName", snapshot.name() != null ? snapshot.name() : "");
        args.put("tenantId",  snapshot.tenantId().toString());
        f.setArgs(args);
        return f;
    }

    /**
     * Builds a {@link FilterDefinition} for the AI Modification Filter.
     *
     * <p>Mirrors {@link #buildAiFilter} — copies all JSONB config fields and
     * injects route metadata (routeId, routeName, tenantId) from the snapshot,
     * ensuring they cannot be overridden by stale config values.
     */
    private FilterDefinition buildAiModifierFilter(RouteSnapshotDto snapshot, Map<String, Object> cfg) {
        var f = new FilterDefinition();
        f.setName("AiModifier");
        var args = new LinkedHashMap<String, String>();
        cfg.forEach((k, v) -> args.put(k, v != null ? v.toString() : ""));
        args.put("routeId",   snapshot.routeId().toString());
        args.put("routeName", snapshot.name() != null ? snapshot.name() : "");
        args.put("tenantId",  snapshot.tenantId().toString());
        f.setArgs(args);
        return f;
    }
}
