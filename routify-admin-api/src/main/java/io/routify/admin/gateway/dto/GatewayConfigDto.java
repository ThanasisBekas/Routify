package io.routify.admin.gateway.dto;

import com.fasterxml.jackson.annotation.JsonAutoDetect;
import com.fasterxml.jackson.annotation.JsonInclude;
import io.routify.common.web.SensitiveField;
import lombok.*;

import java.time.Instant;
import java.util.List;
import java.util.Map;

/**
 * Complete gateway configuration snapshot returned by and accepted by the Admin API.
 * Every section maps directly to a capability of routify-api-gateway.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonInclude(JsonInclude.Include.NON_NULL)
public class GatewayConfigDto {

    private Instant updatedAt;
    private String  updatedBy;

    // ─── CORS ──────────────────────────────────────────────────────────────
    private CorsConfig cors;

    // ─── Security Headers ──────────────────────────────────────────────────
    private SecurityHeadersConfig securityHeaders;

    // ─── Global Rate Limit Policies ────────────────────────────────────────
    private List<RateLimitPolicyDto> rateLimitPolicies;

    // ─── Circuit Breaker Defaults ──────────────────────────────────────────
    private CircuitBreakerDefaultsDto circuitBreakerDefaults;

    // ─── Resilience: Retry & Timeout Defaults ──────────────────────────────
    private ResilienceDefaultsDto resilienceDefaults;

    // ─── Auth Providers ────────────────────────────────────────────────────
    private List<AuthProviderDto> authProviders;


    // ─── TLS / Certificate Sources ────────────────────────────────────────
    private TlsConfigDto tlsConfig;

    // ─── Upstream Proxy ────────────────────────────────────────────────────
    private ProxyConfigDto proxyConfig;

    // ─── HTTP Client (connection pool / timeouts) ──────────────────────────
    private HttpClientConfigDto httpClientConfig;

    // ─── Global Filters ────────────────────────────────────────────────────
    private GlobalFiltersConfig globalFilters;

    // ─── Tenant Isolation ──────────────────────────────────────────────────
    private TenantIsolationConfig tenantIsolation;

    // ─── Global Filter Entries ──────────────────────────────────────────────
    /**
     * Operator-selected filters that are applied globally to every route.
     * Each entry references an existing filter definition by ID and carries its
     * execution order and enabled state. Global filter entries execute before
     * per-route filters in the order defined by the {@code order} field.
     */
    private List<GlobalFilterEntryDto> globalFilterEntries;

    // ─────────────────────────────────────────────────────────────────────────────
    // Nested configuration classes
    // ─────────────────────────────────────────────────────────────────────────────

    @Data @Builder @NoArgsConstructor @AllArgsConstructor
    public static class CorsConfig {
        private boolean enabled;
        private List<String> allowedOriginPatterns;
        private List<String> allowedMethods;
        private List<String> allowedHeaders;
        private List<String> exposedHeaders;
        private boolean allowCredentials;
        private long maxAge;
        /** Paths this CORS config applies to. Default: /** */
        private List<String> paths;
    }

    @Data @Builder @NoArgsConstructor @AllArgsConstructor
    public static class SecurityHeadersConfig {
        private boolean enabled;
        private boolean xContentTypeOptions;
        private boolean xFrameOptions;
        private String xFrameOptionsValue;   // DENY | SAMEORIGIN
        private boolean xXssProtection;
        private boolean strictTransportSecurity;
        private long stsMaxAge;
        private boolean stsIncludeSubDomains;
        private boolean stsPreload;
        private String referrerPolicy;
        private String permissionsPolicy;
        private String contentSecurityPolicy;
        private boolean removeServerHeader;
        private boolean removePoweredByHeader;
        /** Custom headers to add to every response */
        private Map<String, String> customHeaders;
    }

    @Data @Builder @NoArgsConstructor @AllArgsConstructor
    public static class RateLimitPolicyDto {
        private String id;
        private String name;
        private String description;
        private String algorithm;      // TOKEN_BUCKET | FIXED_WINDOW | SLIDING_WINDOW
        private String keyResolver;    // IP | USER | TENANT | API_KEY
        private int    replenishRate;
        private int    burstCapacity;
        private int    requestedTokens;
        private long   windowMs;
        private boolean enabled;
        /** Paths this policy applies to globally (aside from per-route). null = route-only */
        private List<String> globalPaths;
    }

    @Data @Builder @NoArgsConstructor @AllArgsConstructor
    public static class CircuitBreakerDefaultsDto {
        private String  slidingWindowType;          // COUNT_BASED | TIME_BASED
        private int     slidingWindowSize;
        private int     minimumNumberOfCalls;
        private double  failureRateThreshold;
        private double  slowCallRateThreshold;
        private long    slowCallDurationThresholdMs;
        private String  waitDurationInOpenState;    // e.g. "10s"
        private int     permittedNumberOfCallsInHalfOpenState;
        private boolean automaticTransitionFromOpenToHalfOpen;
        private String  fallbackUri;                // default fallback URI
        private boolean recordExceptions;
        private List<String> recordExceptionClasses;
        private List<String> ignoreExceptionClasses;
    }

    @Data @Builder @NoArgsConstructor @AllArgsConstructor
    public static class ResilienceDefaultsDto {
        // Retry
        private int     retryMaxAttempts;
        private String  retryWaitDuration;
        private boolean retryExponentialBackoff;
        private double  retryExponentialMultiplier;
        private String  retryMaxWaitDuration;
        private List<String> retryExceptions;

        // Timeout
        private String  timeoutDuration;            // e.g. "10s"
        private boolean timeoutCancelRunningFuture;

        // Bulkhead
        private boolean bulkheadEnabled;
        private int     bulkheadMaxConcurrentCalls;
        private int     bulkheadMaxWaitDuration;
    }

    @Data @Builder @NoArgsConstructor @AllArgsConstructor
    public static class AuthProviderDto {
        private String id;
        private String name;
        private String type;           // OAUTH2_CLIENT_CREDENTIALS | OAUTH2_PASSWORD | OAUTH2_INTROSPECT | BASIC | JWT_VERIFY
        private String uri;
        private String clientId;
        @SensitiveField
        private String clientSecret;   // masked in GET responses
        private String scope;
        private String username;       // for password grant
        @SensitiveField
        private String password;       // masked
        private String parameterStyle; // BODY | HEADER (for introspection)
        private String parameterName;
        private Map<String, String> additionalParameters;
        private boolean enabled;
        // JWT-specific
        private String jwksUri;
        private String issuer;
        private String audience;
        private String algorithm;
    }


    /**
     * TLS configuration stub — all certificate management is handled by routify-cert-vault.
     * The gateway loads certificates from the Vault at startup and reacts to
     * CERT_GROUP_EVENTS Kafka events for zero-downtime rotation.
     * Deprecated fields (expiryWarning, fileWatchInterval, fileSources, directorySources)
     * have been removed; use the Certificate Vault API and cert-group endpoints instead.
     */
    @Data @Builder
    @JsonAutoDetect(fieldVisibility = JsonAutoDetect.Visibility.ANY)
    public static class TlsConfigDto {
        // intentionally empty — all certificate management is handled by Cert Vault
    }


    @Data @Builder @NoArgsConstructor @AllArgsConstructor
    public static class ProxyConfigDto {
        private boolean enabled;
        private String  host;
        private int     port;
        private String  username;
        @SensitiveField
        private String  password;       // masked
        private List<String> nonProxyHosts;
        private String  type;           // HTTP | HTTPS | SOCKS5
    }

    @Data @Builder @NoArgsConstructor @AllArgsConstructor
    public static class HttpClientConfigDto {
        private int     connectTimeoutMs;
        private int     responseTimeoutMs;
        private int     maxConnections;
        private int     maxConnectionsPerRoute;
        private long    acquireTimeoutMs;
        private String  maxIdleTime;         // e.g. "20s"
        private String  maxLifeTime;         // e.g. "60s"
        private boolean compressionEnabled;
        private boolean followRedirects;
        private boolean wiretapEnabled;
    }

    @Data @Builder @NoArgsConstructor @AllArgsConstructor
    public static class GlobalFiltersConfig {
        private CorrelationIdConfig  correlationId;
        private RequestLoggerConfig  requestLogger;
        private SecurityHeadersRef   securityHeaders;
        private TenantContextConfig  tenantContext;
    }

    @Data @Builder @NoArgsConstructor @AllArgsConstructor
    public static class CorrelationIdConfig {
        private boolean enabled;
        private String  headerName;      // default: X-Correlation-Id
        private boolean generateIfMissing;
        private boolean propagateToResponse;
    }

    @Data @Builder @NoArgsConstructor @AllArgsConstructor
    public static class RequestLoggerConfig {
        private boolean enabled;
        private boolean logRequestHeaders;
        private boolean logResponseHeaders;
        private boolean logRequestBody;
        private boolean logResponseBody;
        private int     maxBodyLogSize;
        private List<String> excludePaths;
        private List<String> maskHeaders;     // header values to redact
    }

    @Data @Builder @NoArgsConstructor @AllArgsConstructor
    public static class SecurityHeadersRef {
        private boolean enabled;
    }

    @Data @Builder @NoArgsConstructor @AllArgsConstructor
    public static class TenantContextConfig {
        private boolean enabled;
        private String  tenantHeaderName;
        private boolean enforceOnAllRoutes;
    }

    @Data @Builder @NoArgsConstructor @AllArgsConstructor
    public static class TenantIsolationConfig {
        private boolean enabled;
        private String  tenantIdHeader;
        private boolean allowCrossTenantsForSuperAdmin;
    }

    /**
     * A reference to an existing filter that has been marked as globally applied
     * to every route in the gateway. Carries the filter's identity, type, execution
     * order, and enabled flag.
     */
    @Data @Builder @NoArgsConstructor @AllArgsConstructor
    public static class GlobalFilterEntryDto {
        private String  filterId;
        private String  filterName;
        private String  filterType;
        private int     order;
        private boolean enabled;
    }
}

