package io.routify.gateway.routing;

import io.routify.common.domain.FilterType;
import io.routify.gateway.config.GatewayConfigLoader;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.MethodSource;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.cloud.gateway.filter.FilterDefinition;
import org.springframework.cloud.gateway.route.RouteDefinition;

import java.util.*;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link RouteDefinitionBuilder}.
 *
 * <p>Verifies:
 * <ul>
 *   <li>Every non-deprecated {@link FilterType} produces a non-null {@link FilterDefinition}</li>
 *   <li>The filter name in each produced {@link FilterDefinition} matches the expected SCG filter factory name</li>
 *   <li>Config values are correctly flattened (lists → comma-separated, maps → dotted keys)</li>
 *   <li>{@code indexedValuesFilter} correctly expands {@code values} lists for AUTH_MTLS and AUTH_CLIENT_ID</li>
 *   <li>Deprecated filter types behave correctly (5 return null, 7 return legacy SCG filters)</li>
 *   <li>Unknown filter type strings return null</li>
 *   <li>{@code build()} produces correct route definitions with predicates, filters, and metadata</li>
 * </ul>
 */
@ExtendWith(MockitoExtension.class)
class RouteDefinitionBuilderTest {

    @Mock
    private GatewayConfigRefResolver configRefResolver;

    @Mock
    private GatewayConfigLoader configLoader;

    @InjectMocks
    private RouteDefinitionBuilder builder;

    // ─── Expected FilterType → SCG filter name mapping ─────────────────────────

    /**
     * Maps every active (non-deprecated) FilterType to its expected SCG filter factory name.
     * This is the single source of truth for the mapping tested below.
     */
    @SuppressWarnings("deprecation")
    private static final Map<FilterType, String> ACTIVE_TYPE_TO_FILTER_NAME;

    static {
        var m = new LinkedHashMap<FilterType, String>();
        // Authentication
        m.put(FilterType.AUTH_JWT, "JwtAuth");
        m.put(FilterType.AUTH_API_KEY, "ApiKeyAuth");
        m.put(FilterType.AUTH_BASIC, "BasicAuth");
        m.put(FilterType.AUTH_OAUTH2, "OAuth2TokenIntrospect");
        m.put(FilterType.AUTH_MTLS, "MtlsAuth");
        m.put(FilterType.AUTH_CLIENT_ID, "ClientIdAuth");
        m.put(FilterType.AUTH_CERT_VAULT, "CertVaultAuth");
        // Downstream Auth
        m.put(FilterType.DOWNSTREAM_BASIC_AUTH, "DownstreamBasicAuth");
        m.put(FilterType.DOWNSTREAM_BEARER_CC, "DownstreamOAuth2Bearer");
        m.put(FilterType.OAUTH2_TOKEN_RELAY, "OAuth2TokenRelay");
        // Rate Limiting
        m.put(FilterType.RATE_LIMIT_FIXED_WINDOW, "FixedWindowRateLimit");
        m.put(FilterType.RATE_LIMIT_SLIDING_WINDOW, "SlidingWindowRateLimit");
        // Request/Response Modification
        m.put(FilterType.REQUEST_HEADER_MODIFY, "RequestHeaderModify");
        m.put(FilterType.RESPONSE_HEADER_MODIFY, "ResponseHeaderModify");
        m.put(FilterType.RESPONSE_HEADER_REWRITE, "ResponseHeaderRewrite");
        // Body Transformation
        m.put(FilterType.BODY_JOLT_TRANSFORM, "JoltTransform");
        // Validation
        m.put(FilterType.VALIDATE_JSON_SCHEMA, "JsonSchemaValidate");
        m.put(FilterType.REQUEST_SIZE_LIMIT, "RequestSizeLimit");
        m.put(FilterType.GRAPHQL_DEPTH_LIMIT, "GraphQLDepthLimit");
        // Performance
        m.put(FilterType.RESPONSE_CACHE, "ResponseCache");
        m.put(FilterType.REQUEST_DECOMPRESS, "RequestDecompress");
        // Reliability
        m.put(FilterType.IDEMPOTENCY_KEY, "IdempotencyKey");
        // Resilience
        m.put(FilterType.TIMEOUT, "RequestTimeout");
        m.put(FilterType.CIRCUIT_BREAKER_V2, "CircuitBreakerV2");
        m.put(FilterType.RETRY_V2, "RetryV2");
        // Routing
        m.put(FilterType.CONDITIONAL_ROUTE, "ConditionalRoute");
        m.put(FilterType.USER_ID_PAYLOAD_ROUTING, "UserIdPayloadRouting");
        m.put(FilterType.GEO_ROUTE, "GeoRoute");
        // Security
        m.put(FilterType.IP_ACCESS_CONTROL, "IpAccessControl");
        // Certificates
        m.put(FilterType.CERT_ROTATION, "CertRotation");
        m.put(FilterType.CERT_VAULT_EXPIRY_CHECK, "CertVaultExpiryCheck");
        // Versioning
        m.put(FilterType.API_VERSIONING, "ApiVersioning");
        // Observability
        m.put(FilterType.CORRELATION_ID, "CorrelationId");
        m.put(FilterType.REQUEST_LOGGER, "RequestLogger");
        m.put(FilterType.TENANT_CONTEXT, "TenantContext");
        m.put(FilterType.SECURITY_HEADERS, "SecurityHeaders");
        m.put(FilterType.CUSTOM_METRIC, "CustomMetric");
        m.put(FilterType.BODY_SIZE_METRIC, "BodySizeMetric");
        // Integration
        m.put(FilterType.WEBHOOK_NOTIFY, "WebhookNotify");
        // Developer Experience
        m.put(FilterType.MOCK_RESPONSE, "MockResponse");
        // Custom
        m.put(FilterType.CUSTOM_SPEL, "SpelCustom");
        // AI
        m.put(FilterType.AI_FILTER, "AiFilter");
        m.put(FilterType.AI_MODIFIER, "AiModifier");
        ACTIVE_TYPE_TO_FILTER_NAME = Collections.unmodifiableMap(m);
    }

    /**
     * Maps deprecated FilterTypes that produce a legacy SCG filter (not null) to their expected name.
     */
    @SuppressWarnings("deprecation")
    private static final Map<FilterType, String> DEPRECATED_FUNCTIONAL_TO_FILTER_NAME = Map.of(
            FilterType.RATE_LIMIT_TOKEN_BUCKET, "RequestRateLimiter",
            FilterType.PATH_REWRITE, "RewritePath",
            FilterType.PATH_STRIP_PREFIX, "StripPrefix",
            FilterType.PATH_ADD_PREFIX, "PrefixPath",
            FilterType.VALIDATE_SIZE, "RequestSize",
            FilterType.CIRCUIT_BREAKER, "CircuitBreaker",
            FilterType.RETRY, "Retry"
    );

    /**
     * Deprecated FilterTypes that yield null (no factory implementation).
     */
    @SuppressWarnings("deprecation")
    private static final Set<FilterType> DEPRECATED_NULL_TYPES = Set.of(
            FilterType.AUTH_NONE,
            FilterType.QUERY_PARAM_MODIFY,
            FilterType.BODY_JSONATA_TRANSFORM,
            FilterType.BODY_SPEL_TRANSFORM,
            FilterType.VALIDATE_REGEX
    );

    // ─── Helpers ──────────────────────────────────────────────────────────────

    private RouteSnapshotDto minimalSnapshot() {
        return new RouteSnapshotDto(
                UUID.randomUUID(),
                UUID.randomUUID(),
                "test-route",
                "/api/test/**",
                "GET,POST",
                "http://upstream:8080",
                null,  // stripPrefix
                1,     // version
                "PRODUCTION",
                List.of(),
                null,  // extraConfig
                100,   // trafficWeight
                null   // canaryRouteId
        );
    }

    private RouteSnapshotDto.FilterSnapshotDto filterSnapshot(String filterType, Map<String, Object> config) {
        return new RouteSnapshotDto.FilterSnapshotDto(
                UUID.randomUUID(), filterType, 0, "PRE", config, null);
    }

    private RouteSnapshotDto snapshotWithFilter(String filterType, Map<String, Object> config) {
        RouteSnapshotDto base = minimalSnapshot();
        return new RouteSnapshotDto(
                base.routeId(), base.tenantId(), base.name(), base.pathPattern(),
                base.methods(), base.upstreamUri(), base.stripPrefix(), base.version(),
                base.environment(),
                List.of(filterSnapshot(filterType, config)),
                base.extraConfig(), base.trafficWeight(), base.canaryRouteId()
        );
    }

    /**
     * Configures mock to pass-through local config (no gateway config ref resolution).
     */
    private void stubConfigRefPassThrough() {
        when(configRefResolver.resolve(any(), any()))
                .thenAnswer(inv -> {
                    Map<String, Object> localConfig = inv.getArgument(0);
                    return localConfig != null ? localConfig : Map.of();
                });
    }

    /**
     * Configures GatewayConfigLoader to return an empty config map.
     */
    private void stubEmptyGatewayConfig() {
        when(configLoader.getConfig()).thenReturn(Map.of());
    }

    // ═══════════════════════════════════════════════════════════════════════════
    //  Issue 3.1: Every non-deprecated FilterType → non-null FilterDefinition
    // ═══════════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("Active filter types")
    class ActiveFilterTypes {

        @BeforeEach
        void setup() {
            stubConfigRefPassThrough();
            stubEmptyGatewayConfig();
        }

        static Stream<Arguments> activeFilterTypeAndName() {
            return ACTIVE_TYPE_TO_FILTER_NAME.entrySet().stream()
                    .map(e -> Arguments.of(e.getKey(), e.getValue()));
        }

        @ParameterizedTest(name = "{0} → {1}")
        @MethodSource("activeFilterTypeAndName")
        @DisplayName("Active FilterType produces non-null FilterDefinition with correct SCG filter name")
        void activeType_producesCorrectFilterDefinition(FilterType filterType, String expectedFilterName) {
            RouteSnapshotDto snapshot = snapshotWithFilter(filterType.name(), Map.of());
            RouteDefinition rd = builder.build(snapshot);

            List<FilterDefinition> filters = rd.getFilters();
            assertThat(filters)
                    .as("Route for %s should have at least one filter", filterType)
                    .isNotEmpty();

            // Find the filter we added (skip any strip-prefix or global filters)
            FilterDefinition fd = filters.getLast();
            assertThat(fd).as("FilterDefinition for %s must not be null", filterType).isNotNull();
            assertThat(fd.getName())
                    .as("SCG filter name for %s", filterType)
                    .isEqualTo(expectedFilterName);
        }
    }

    // ═══════════════════════════════════════════════════════════════════════════
    //  Coverage verification — test data maps cover all FilterType values
    // ═══════════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("Coverage verification")
    class CoverageVerification {

        @Test
        @DisplayName("ACTIVE_TYPE_TO_FILTER_NAME covers all non-deprecated FilterType values")
        void allActiveFilterTypesCovered() {
            Set<FilterType> allActive = EnumSet.allOf(FilterType.class);
            allActive.removeAll(FilterType.DEPRECATED);

            assertThat(ACTIVE_TYPE_TO_FILTER_NAME.keySet())
                    .as("Every non-deprecated FilterType must have an expected filter name mapping")
                    .containsExactlyInAnyOrderElementsOf(allActive);
        }

        @Test
        @DisplayName("All 12 deprecated types accounted for (7 functional + 5 null)")
        void allDeprecatedTypesCovered() {
            Set<FilterType> allDeprecated = new HashSet<>();
            allDeprecated.addAll(DEPRECATED_FUNCTIONAL_TO_FILTER_NAME.keySet());
            allDeprecated.addAll(DEPRECATED_NULL_TYPES);

            assertThat(allDeprecated)
                    .as("7 functional + 5 null must equal FilterType.DEPRECATED")
                    .containsExactlyInAnyOrderElementsOf(FilterType.DEPRECATED);
        }
    }

    // ═══════════════════════════════════════════════════════════════════════════
    //  Issue 3.1: Deprecated FilterTypes — functional and null
    // ═══════════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("Deprecated filter types")
    class DeprecatedFilterTypes {

        @BeforeEach
        void setup() {
            stubConfigRefPassThrough();
            stubEmptyGatewayConfig();
        }

        @SuppressWarnings("deprecation")
        static Stream<Arguments> deprecatedFunctionalTypes() {
            return DEPRECATED_FUNCTIONAL_TO_FILTER_NAME.entrySet().stream()
                    .map(e -> Arguments.of(e.getKey(), e.getValue()));
        }

        @ParameterizedTest(name = "{0} → {1} (legacy)")
        @MethodSource("deprecatedFunctionalTypes")
        @DisplayName("Deprecated functional type produces legacy SCG filter")
        void deprecatedFunctional_producesLegacyFilter(FilterType filterType, String expectedName) {
            RouteSnapshotDto snapshot = snapshotWithFilter(filterType.name(), Map.of());
            RouteDefinition rd = builder.build(snapshot);

            FilterDefinition fd = rd.getFilters().getLast();
            assertThat(fd).as("Deprecated functional filter %s should produce a FilterDefinition", filterType).isNotNull();
            assertThat(fd.getName()).isEqualTo(expectedName);
        }

        @SuppressWarnings("deprecation")
        static Stream<FilterType> deprecatedNullTypes() {
            return DEPRECATED_NULL_TYPES.stream();
        }

        @ParameterizedTest(name = "{0} → null")
        @MethodSource("deprecatedNullTypes")
        @DisplayName("Deprecated no-op type yields null (filter skipped)")
        void deprecatedNull_yieldsNull(FilterType filterType) {
            RouteSnapshotDto snapshot = snapshotWithFilter(filterType.name(), Map.of());
            RouteDefinition rd = builder.build(snapshot);

            // null FilterDefinitions are filtered out by Objects::nonNull in build()
            assertThat(rd.getFilters())
                    .as("Deprecated null type %s should be filtered out (no FilterDefinition)", filterType)
                    .noneMatch(fd -> fd.getName().equals(filterType.name()));
        }
    }

    // ═══════════════════════════════════════════════════════════════════════════
    //  Issue 3.1: Unknown filter type string → null
    // ═══════════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("Unknown filter types")
    class UnknownFilterTypes {

        @BeforeEach
        void setup() {
            stubConfigRefPassThrough();
            stubEmptyGatewayConfig();
        }

        @Test
        @DisplayName("Unknown filter type string is safely skipped")
        void unknownFilterType_returnsNull() {
            RouteSnapshotDto snapshot = snapshotWithFilter("TOTALLY_BOGUS", Map.of());
            RouteDefinition rd = builder.build(snapshot);

            // The bogus filter should be filtered out
            assertThat(rd.getFilters())
                    .noneMatch(fd -> "TOTALLY_BOGUS".equals(fd.getName()));
        }

        @Test
        @DisplayName("Null filter type string is safely skipped")
        void nullFilterType_returnsNull() {
            RouteSnapshotDto snapshot = snapshotWithFilter(null, Map.of());
            RouteDefinition rd = builder.build(snapshot);

            // No crash — null filterType is handled gracefully
            assertThat(rd).isNotNull();
        }

        @Test
        @DisplayName("Empty filter type string is safely skipped")
        void emptyFilterType_returnsNull() {
            RouteSnapshotDto snapshot = snapshotWithFilter("", Map.of());
            RouteDefinition rd = builder.build(snapshot);

            assertThat(rd).isNotNull();
        }
    }

    // ═══════════════════════════════════════════════════════════════════════════
    //  Issue 3.2: Config flattening
    // ═══════════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("Config flattening and args mapping")
    class ConfigFlattening {

        @BeforeEach
        void setup() {
            stubConfigRefPassThrough();
            stubEmptyGatewayConfig();
        }

        @Test
        @DisplayName("Scalar config values are passed through as strings")
        void scalarConfigValues() {
            Map<String, Object> config = Map.of(
                    "logRequestBody", true,
                    "maxBodyCaptureBytes", 4096,
                    "samplingRate", 1.0
            );
            RouteSnapshotDto snapshot = snapshotWithFilter("REQUEST_LOGGER", config);
            RouteDefinition rd = builder.build(snapshot);

            FilterDefinition fd = rd.getFilters().getLast();
            assertThat(fd.getArgs())
                    .containsEntry("logRequestBody", "true")
                    .containsEntry("maxBodyCaptureBytes", "4096")
                    .containsEntry("samplingRate", "1.0");
        }

        @Test
        @DisplayName("List config values become comma-separated strings")
        void listConfigValues() {
            Map<String, Object> config = Map.of(
                    "headerDenylist", List.of("Authorization", "Cookie"),
                    "skipPaths", List.of("/health", "/actuator/**")
            );
            RouteSnapshotDto snapshot = snapshotWithFilter("REQUEST_LOGGER", config);
            RouteDefinition rd = builder.build(snapshot);

            FilterDefinition fd = rd.getFilters().getLast();
            assertThat(fd.getArgs())
                    .containsEntry("headerDenylist", "Authorization,Cookie")
                    .containsEntry("skipPaths", "/health,/actuator/**");
        }

        @Test
        @DisplayName("Empty list config values are omitted from args")
        void emptyListConfigValues() {
            Map<String, Object> config = new LinkedHashMap<>();
            config.put("headerAllowlist", List.of());
            config.put("logRequestBody", true);
            RouteSnapshotDto snapshot = snapshotWithFilter("REQUEST_LOGGER", config);
            RouteDefinition rd = builder.build(snapshot);

            FilterDefinition fd = rd.getFilters().getLast();
            assertThat(fd.getArgs())
                    .doesNotContainKey("headerAllowlist")
                    .containsEntry("logRequestBody", "true");
        }

        @Test
        @DisplayName("Map config values are expanded as dotted keys")
        void mapConfigValues() {
            Map<String, Object> config = Map.of(
                    "metricName", "my_metric",
                    "tags", Map.of("env", "prod", "region", "eu")
            );
            RouteSnapshotDto snapshot = snapshotWithFilter("CUSTOM_METRIC", config);
            RouteDefinition rd = builder.build(snapshot);

            FilterDefinition fd = rd.getFilters().getLast();
            assertThat(fd.getArgs())
                    .containsEntry("metricName", "my_metric")
                    .containsEntry("tags.env", "prod")
                    .containsEntry("tags.region", "eu")
                    .doesNotContainKey("tags");
        }

        @Test
        @DisplayName("Null config values are omitted from args")
        void nullConfigValues() {
            Map<String, Object> config = new LinkedHashMap<>();
            config.put("metricName", "my_metric");
            config.put("description", null);
            RouteSnapshotDto snapshot = snapshotWithFilter("CUSTOM_METRIC", config);
            RouteDefinition rd = builder.build(snapshot);

            FilterDefinition fd = rd.getFilters().getLast();
            assertThat(fd.getArgs())
                    .containsEntry("metricName", "my_metric")
                    .doesNotContainKey("description");
        }
    }

    // ═══════════════════════════════════════════════════════════════════════════
    //  Issue 3.2: indexedValuesFilter (AUTH_MTLS, AUTH_CLIENT_ID)
    // ═══════════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("Indexed values filter expansion")
    class IndexedValuesFilter {

        @BeforeEach
        void setup() {
            stubConfigRefPassThrough();
            stubEmptyGatewayConfig();
        }

        @Test
        @DisplayName("AUTH_MTLS values list is expanded into indexed args")
        void mtlsValuesExpanded() {
            List<Map<String, Object>> values = List.of(
                    Map.of("clientIdRequestHeader", "X-Client-Id",
                           "clientIdValue", "acme",
                           "clientCertificateRequestHeader", "X-Client-Cert",
                           "clientCertificateValue", "cert-logical-1"),
                    Map.of("clientIdRequestHeader", "X-Client-Id",
                           "clientIdValue", "globex",
                           "clientCertificateRequestHeader", "X-Client-Cert",
                           "clientCertificateValue", "cert-logical-2")
            );
            Map<String, Object> config = Map.of("values", values);
            RouteSnapshotDto snapshot = snapshotWithFilter("AUTH_MTLS", config);
            RouteDefinition rd = builder.build(snapshot);

            FilterDefinition fd = rd.getFilters().getLast();
            assertThat(fd.getName()).isEqualTo("MtlsAuth");
            assertThat(fd.getArgs())
                    .containsEntry("values[0].clientIdRequestHeader", "X-Client-Id")
                    .containsEntry("values[0].clientIdValue", "acme")
                    .containsEntry("values[0].clientCertificateRequestHeader", "X-Client-Cert")
                    .containsEntry("values[0].clientCertificateValue", "cert-logical-1")
                    .containsEntry("values[1].clientIdValue", "globex")
                    .containsEntry("values[1].clientCertificateValue", "cert-logical-2");
        }

        @Test
        @DisplayName("AUTH_CLIENT_ID clientIdMapping is expanded into indexed args")
        void clientIdMappingExpanded() {
            Map<String, Object> config = Map.of(
                    "values", List.of(
                            Map.of("name", "X-Client-Id", "value", "my-client")
                    ),
                    "clientIdMapping", Map.of("org-1", "client-a", "org-2", "client-b")
            );
            RouteSnapshotDto snapshot = snapshotWithFilter("AUTH_CLIENT_ID", config);
            RouteDefinition rd = builder.build(snapshot);

            FilterDefinition fd = rd.getFilters().getLast();
            assertThat(fd.getName()).isEqualTo("ClientIdAuth");
            assertThat(fd.getArgs())
                    .containsEntry("values[0].name", "X-Client-Id")
                    .containsEntry("values[0].value", "my-client")
                    .containsEntry("clientIdMapping[org-1]", "client-a")
                    .containsEntry("clientIdMapping[org-2]", "client-b");
        }
    }

    // ═══════════════════════════════════════════════════════════════════════════
    //  Issue 3.2: AI filter metadata injection
    // ═══════════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("AI filter metadata injection")
    class AiFilterMetadata {

        @BeforeEach
        void setup() {
            stubConfigRefPassThrough();
            stubEmptyGatewayConfig();
        }

        @Test
        @DisplayName("AI_FILTER injects routeId, routeName, tenantId from snapshot")
        void aiFilterInjectsRouteMetadata() {
            UUID routeId = UUID.randomUUID();
            UUID tenantId = UUID.randomUUID();
            RouteSnapshotDto snapshot = new RouteSnapshotDto(
                    routeId, tenantId, "my-ai-route", "/api/ai/**", "POST",
                    "http://upstream:8080", null, 1, "PRODUCTION",
                    List.of(filterSnapshot("AI_FILTER", Map.of("policy", "block-pii"))),
                    null, 100, null
            );

            RouteDefinition rd = builder.build(snapshot);
            FilterDefinition fd = rd.getFilters().getLast();

            assertThat(fd.getName()).isEqualTo("AiFilter");
            assertThat(fd.getArgs())
                    .containsEntry("routeId", routeId.toString())
                    .containsEntry("routeName", "my-ai-route")
                    .containsEntry("tenantId", tenantId.toString())
                    .containsEntry("policy", "block-pii");
        }

        @Test
        @DisplayName("AI_MODIFIER injects routeId, routeName, tenantId from snapshot")
        void aiModifierInjectsRouteMetadata() {
            UUID routeId = UUID.randomUUID();
            UUID tenantId = UUID.randomUUID();
            RouteSnapshotDto snapshot = new RouteSnapshotDto(
                    routeId, tenantId, "my-ai-modifier-route", "/api/modify/**", "POST",
                    "http://upstream:8080", null, 1, "PRODUCTION",
                    List.of(filterSnapshot("AI_MODIFIER", Map.of("prompt", "scrub-pii"))),
                    null, 100, null
            );

            RouteDefinition rd = builder.build(snapshot);
            FilterDefinition fd = rd.getFilters().getLast();

            assertThat(fd.getName()).isEqualTo("AiModifier");
            assertThat(fd.getArgs())
                    .containsEntry("routeId", routeId.toString())
                    .containsEntry("routeName", "my-ai-modifier-route")
                    .containsEntry("tenantId", tenantId.toString())
                    .containsEntry("prompt", "scrub-pii");
        }
    }

    // ═══════════════════════════════════════════════════════════════════════════
    //  Issue 3.2: Named filters (zero-config)
    // ═══════════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("Named filters (zero-config)")
    class NamedFilters {

        @BeforeEach
        void setup() {
            stubConfigRefPassThrough();
            stubEmptyGatewayConfig();
        }

        @ParameterizedTest(name = "{0} → {1} (no args)")
        @MethodSource("namedFilterTypes")
        @DisplayName("Named filter produces FilterDefinition with empty args")
        void namedFilter_hasEmptyArgs(FilterType filterType, String expectedName) {
            RouteSnapshotDto snapshot = snapshotWithFilter(filterType.name(), Map.of());
            RouteDefinition rd = builder.build(snapshot);

            FilterDefinition fd = rd.getFilters().getLast();
            assertThat(fd.getName()).isEqualTo(expectedName);
            assertThat(fd.getArgs()).isEmpty();
        }

        static Stream<Arguments> namedFilterTypes() {
            return Stream.of(
                    Arguments.of(FilterType.CORRELATION_ID, "CorrelationId"),
                    Arguments.of(FilterType.TENANT_CONTEXT, "TenantContext"),
                    Arguments.of(FilterType.SECURITY_HEADERS, "SecurityHeaders")
            );
        }
    }

    // ═══════════════════════════════════════════════════════════════════════════
    //  Issue 3.2: Deprecated legacy filters produce correct SCG args
    // ═══════════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("Deprecated legacy filter config mapping")
    class DeprecatedLegacyFilterConfig {

        @BeforeEach
        void setup() {
            stubConfigRefPassThrough();
            stubEmptyGatewayConfig();
        }

        @SuppressWarnings("deprecation")
        @Test
        @DisplayName("RATE_LIMIT_TOKEN_BUCKET uses default SCG rate limiter args")
        void tokenBucketDefaultArgs() {
            RouteSnapshotDto snapshot = snapshotWithFilter("RATE_LIMIT_TOKEN_BUCKET", Map.of());
            RouteDefinition rd = builder.build(snapshot);

            FilterDefinition fd = rd.getFilters().getLast();
            assertThat(fd.getName()).isEqualTo("RequestRateLimiter");
            assertThat(fd.getArgs())
                    .containsEntry("redis-rate-limiter.replenishRate", "10")
                    .containsEntry("redis-rate-limiter.burstCapacity", "20")
                    .containsEntry("redis-rate-limiter.requestedTokens", "1")
                    .containsKey("key-resolver");
        }

        @SuppressWarnings("deprecation")
        @Test
        @DisplayName("RATE_LIMIT_TOKEN_BUCKET clamps burstCapacity when < replenishRate")
        void tokenBucketClampsBurst() {
            Map<String, Object> config = Map.of(
                    "replenishRate", 50,
                    "burstCapacity", 10  // less than replenishRate → should be clamped
            );
            RouteSnapshotDto snapshot = snapshotWithFilter("RATE_LIMIT_TOKEN_BUCKET", config);
            RouteDefinition rd = builder.build(snapshot);

            FilterDefinition fd = rd.getFilters().getLast();
            assertThat(fd.getArgs())
                    .containsEntry("redis-rate-limiter.replenishRate", "50")
                    .containsEntry("redis-rate-limiter.burstCapacity", "50");  // clamped
        }

        @SuppressWarnings("deprecation")
        @Test
        @DisplayName("PATH_REWRITE maps regexp and replacement")
        void pathRewriteArgs() {
            Map<String, Object> config = Map.of(
                    "regexp", "/old/(?<rest>.*)",
                    "replacement", "/new/${rest}"
            );
            RouteSnapshotDto snapshot = snapshotWithFilter("PATH_REWRITE", config);
            RouteDefinition rd = builder.build(snapshot);

            FilterDefinition fd = rd.getFilters().getLast();
            assertThat(fd.getName()).isEqualTo("RewritePath");
            assertThat(fd.getArgs())
                    .containsEntry("regexp", "/old/(?<rest>.*)")
                    .containsEntry("replacement", "/new/${rest}");
        }

        @SuppressWarnings("deprecation")
        @Test
        @DisplayName("PATH_STRIP_PREFIX maps parts config")
        void pathStripPrefixArgs() {
            Map<String, Object> config = Map.of("parts", "2");
            RouteSnapshotDto snapshot = snapshotWithFilter("PATH_STRIP_PREFIX", config);
            RouteDefinition rd = builder.build(snapshot);

            FilterDefinition fd = rd.getFilters().getLast();
            assertThat(fd.getName()).isEqualTo("StripPrefix");
            assertThat(fd.getArgs()).containsEntry("parts", "2");
        }

        @SuppressWarnings("deprecation")
        @Test
        @DisplayName("PATH_ADD_PREFIX maps prefix config")
        void pathAddPrefixArgs() {
            Map<String, Object> config = Map.of("prefix", "/api/v2");
            RouteSnapshotDto snapshot = snapshotWithFilter("PATH_ADD_PREFIX", config);
            RouteDefinition rd = builder.build(snapshot);

            FilterDefinition fd = rd.getFilters().getLast();
            assertThat(fd.getName()).isEqualTo("PrefixPath");
            assertThat(fd.getArgs()).containsEntry("prefix", "/api/v2");
        }

        @SuppressWarnings("deprecation")
        @Test
        @DisplayName("CIRCUIT_BREAKER maps name and fallbackUri")
        void circuitBreakerArgs() {
            Map<String, Object> config = Map.of(
                    "name", "my-breaker",
                    "fallbackUri", "forward:/fallback/custom"
            );
            RouteSnapshotDto snapshot = snapshotWithFilter("CIRCUIT_BREAKER", config);
            RouteDefinition rd = builder.build(snapshot);

            FilterDefinition fd = rd.getFilters().getLast();
            assertThat(fd.getName()).isEqualTo("CircuitBreaker");
            assertThat(fd.getArgs())
                    .containsEntry("name", "my-breaker")
                    .containsEntry("fallbackUri", "forward:/fallback/custom");
        }

        @SuppressWarnings("deprecation")
        @Test
        @DisplayName("RETRY maps retries, series, methods, and optional statuses")
        void retryArgs() {
            Map<String, Object> config = Map.of(
                    "retries", "5",
                    "series", "CLIENT_ERROR,SERVER_ERROR",
                    "methods", "GET,POST",
                    "statuses", "502,503"
            );
            RouteSnapshotDto snapshot = snapshotWithFilter("RETRY", config);
            RouteDefinition rd = builder.build(snapshot);

            FilterDefinition fd = rd.getFilters().getLast();
            assertThat(fd.getName()).isEqualTo("Retry");
            assertThat(fd.getArgs())
                    .containsEntry("retries", "5")
                    .containsEntry("series", "CLIENT_ERROR,SERVER_ERROR")
                    .containsEntry("methods", "GET,POST")
                    .containsEntry("statuses", "502,503");
        }

        @SuppressWarnings("deprecation")
        @Test
        @DisplayName("VALIDATE_SIZE maps maxSize config")
        void validateSizeArgs() {
            Map<String, Object> config = Map.of("maxSize", "10MB");
            RouteSnapshotDto snapshot = snapshotWithFilter("VALIDATE_SIZE", config);
            RouteDefinition rd = builder.build(snapshot);

            FilterDefinition fd = rd.getFilters().getLast();
            assertThat(fd.getName()).isEqualTo("RequestSize");
            assertThat(fd.getArgs()).containsEntry("maxSize", "10MB");
        }
    }

    // ═══════════════════════════════════════════════════════════════════════════
    //  Issue 3.2: Full route build() — predicates, metadata, filters
    // ═══════════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("Full route build()")
    class FullRouteBuild {

        @Test
        @DisplayName("Minimal route snapshot produces correct RouteDefinition")
        void minimalBuild() {
            when(configLoader.getConfig()).thenReturn(Map.of());

            RouteSnapshotDto snapshot = minimalSnapshot();
            RouteDefinition rd = builder.build(snapshot);

            // Route ID is tenantId::routeId
            assertThat(rd.getId()).isEqualTo(
                    "%s::%s".formatted(snapshot.tenantId(), snapshot.routeId()));

            // URI
            assertThat(rd.getUri()).hasToString("http://upstream:8080");

            // Predicates: Path + Method
            List<String> predicateStrings = rd.getPredicates().stream()
                    .map(Object::toString).toList();
            assertThat(predicateStrings)
                    .anyMatch(text -> text.contains("Path") && text.contains("/api/test/**"))
                    .anyMatch(text -> text.contains("Method") && text.contains("GET"));

            // Metadata
            assertThat(rd.getMetadata())
                    .containsEntry("tenantId", snapshot.tenantId().toString())
                    .containsEntry("routeName", "test-route")
                    .containsEntry("routeVersion", 1)
                    .containsEntry("environment", "PRODUCTION");
        }

        @Test
        @DisplayName("Route with wildcard method (*) omits Method predicate")
        void wildcardMethodOmitted() {
            when(configLoader.getConfig()).thenReturn(Map.of());

            RouteSnapshotDto snapshot = new RouteSnapshotDto(
                    UUID.randomUUID(), UUID.randomUUID(), "wildcard-route",
                    "/api/**", "*", "http://upstream:8080",
                    null, 1, "PRODUCTION", List.of(), null, 100, null
            );
            RouteDefinition rd = builder.build(snapshot);

            List<String> predicateStrings = rd.getPredicates().stream()
                    .map(Object::toString).toList();
            assertThat(predicateStrings)
                    .noneMatch(text -> text.contains("Method="));
        }

        @Test
        @DisplayName("Route with stripPrefix adds StripPrefix filter")
        void stripPrefixAdded() {
            when(configLoader.getConfig()).thenReturn(Map.of());

            RouteSnapshotDto snapshot = new RouteSnapshotDto(
                    UUID.randomUUID(), UUID.randomUUID(), "strip-route",
                    "/api/v1/**", "GET", "http://upstream:8080",
                    "/api/v1", 1, "PRODUCTION", List.of(), null, 100, null
            );
            RouteDefinition rd = builder.build(snapshot);

            assertThat(rd.getFilters())
                    .anyMatch(fd -> "StripPrefix".equals(fd.getName())
                            && "2".equals(fd.getArgs().get("parts")));
        }

        @Test
        @DisplayName("Route with trafficWeight < 100 adds Weight predicate")
        void canaryWeightPredicate() {
            when(configLoader.getConfig()).thenReturn(Map.of());

            UUID canaryRouteId = UUID.randomUUID();
            RouteSnapshotDto snapshot = new RouteSnapshotDto(
                    UUID.randomUUID(), UUID.randomUUID(), "canary-route",
                    "/api/**", "GET", "http://upstream:8080",
                    null, 1, "PRODUCTION", List.of(), null,
                    90, canaryRouteId  // 90% traffic, has canary
            );
            RouteDefinition rd = builder.build(snapshot);

            List<String> predicateStrings = rd.getPredicates().stream()
                    .map(Object::toString).toList();
            assertThat(predicateStrings)
                    .anyMatch(text -> text.contains("Weight") && text.contains("canary-"));
        }

        @Test
        @DisplayName("STAGING environment route with staging enabled adds staging header predicate")
        void stagingPredicate() {
            when(configLoader.getConfig()).thenReturn(Map.of(
                    "staging", Map.of("enabled", true, "headerName", "X-Route-Environment")
            ));

            RouteSnapshotDto snapshot = new RouteSnapshotDto(
                    UUID.randomUUID(), UUID.randomUUID(), "staging-route",
                    "/api/**", "GET", "http://upstream:8080",
                    null, 1, "STAGING", List.of(), null, 100, null
            );
            RouteDefinition rd = builder.build(snapshot);

            List<String> predicateStrings = rd.getPredicates().stream()
                    .map(Object::toString).toList();
            assertThat(predicateStrings)
                    .anyMatch(text -> text.contains("X-Route-Environment"));
        }

        @Test
        @DisplayName("Filters are sorted by order")
        void filtersSortedByOrder() {
            stubConfigRefPassThrough();
            when(configLoader.getConfig()).thenReturn(Map.of());

            var filter1 = new RouteSnapshotDto.FilterSnapshotDto(
                    UUID.randomUUID(), "CORRELATION_ID", 2, "PRE", Map.of(), null);
            var filter2 = new RouteSnapshotDto.FilterSnapshotDto(
                    UUID.randomUUID(), "REQUEST_LOGGER", 1, "PRE",
                    Map.of("logRequestBody", true), null);

            RouteSnapshotDto snapshot = new RouteSnapshotDto(
                    UUID.randomUUID(), UUID.randomUUID(), "ordered-route",
                    "/api/**", "GET", "http://upstream:8080",
                    null, 1, "PRODUCTION",
                    List.of(filter1, filter2),
                    null, 100, null
            );
            RouteDefinition rd = builder.build(snapshot);

            // filter2 (order=1) should come before filter1 (order=2)
            List<String> filterNames = rd.getFilters().stream()
                    .map(FilterDefinition::getName)
                    .toList();
            assertThat(filterNames.indexOf("RequestLogger"))
                    .as("RequestLogger (order=1) should appear before CorrelationId (order=2)")
                    .isLessThan(filterNames.indexOf("CorrelationId"));
        }

        @Test
        @DisplayName("Route with tenant isolation enabled adds tenant header predicate")
        void tenantIsolationEnabled() {
            when(configLoader.getConfig()).thenReturn(Map.of(
                    "tenantIsolation", Map.of("enabled", true, "tenantIdHeader", "X-Tenant-Id")
            ));

            UUID tenantId = UUID.randomUUID();
            RouteSnapshotDto snapshot = new RouteSnapshotDto(
                    UUID.randomUUID(), tenantId, "tenant-route",
                    "/api/**", "GET", "http://upstream:8080",
                    null, 1, "PRODUCTION", List.of(), null, 100, null
            );
            RouteDefinition rd = builder.build(snapshot);

            List<String> predicateStrings = rd.getPredicates().stream()
                    .map(Object::toString).toList();
            assertThat(predicateStrings)
                    .anyMatch(text -> text.contains("X-Tenant-Id"));
        }

        @Test
        @DisplayName("Route with tenant isolation disabled omits tenant header predicate")
        void tenantIsolationDisabled() {
            when(configLoader.getConfig()).thenReturn(Map.of(
                    "tenantIsolation", Map.of("enabled", false)
            ));

            RouteSnapshotDto snapshot = minimalSnapshot();
            RouteDefinition rd = builder.build(snapshot);

            List<String> predicateStrings = rd.getPredicates().stream()
                    .map(Object::toString).toList();
            assertThat(predicateStrings)
                    .noneMatch(text -> text.contains("X-Tenant-Id"));
        }

        @Test
        @DisplayName("Extra config is merged into route metadata")
        void extraConfigInMetadata() {
            when(configLoader.getConfig()).thenReturn(Map.of());

            RouteSnapshotDto snapshot = new RouteSnapshotDto(
                    UUID.randomUUID(), UUID.randomUUID(), "extra-config-route",
                    "/api/**", "GET", "http://upstream:8080",
                    null, 1, "PRODUCTION", List.of(),
                    Map.of("customKey", "customValue"), 100, null
            );
            RouteDefinition rd = builder.build(snapshot);

            assertThat(rd.getMetadata())
                    .containsEntry("customKey", "customValue");
        }
    }

    // ═══════════════════════════════════════════════════════════════════════════
    //  Issue 3.2: Exhaustiveness — every FilterType has a switch case
    // ═══════════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("Exhaustiveness verification")
    class Exhaustiveness {

        @BeforeEach
        void setup() {
            stubConfigRefPassThrough();
            stubEmptyGatewayConfig();
        }

        @ParameterizedTest(name = "{0}")
        @EnumSource(FilterType.class)
        @DisplayName("Every FilterType enum value is handled by the switch (no exception)")
        void everyFilterType_handledWithoutException(FilterType filterType) {
            RouteSnapshotDto snapshot = snapshotWithFilter(filterType.name(), Map.of());

            // Should not throw — every enum value must have a case in the switch
            RouteDefinition rd = builder.build(snapshot);
            assertThat(rd).isNotNull();
        }
    }
}

