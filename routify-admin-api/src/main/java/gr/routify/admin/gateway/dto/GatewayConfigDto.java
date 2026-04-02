package gr.routify.admin.gateway.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import gr.routify.common.web.SensitiveField;
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


    @Data @Builder @NoArgsConstructor @AllArgsConstructor
    public static class TlsConfigDto {
        private String  expiryWarning;          // e.g. "30d"
        private String  fileWatchInterval;      // e.g. "30s"
        private List<CertificateSourceDto> fileSources;
        private List<DirectorySourceDto>   directorySources;
    }

    @Data @Builder @NoArgsConstructor @AllArgsConstructor
    public static class CertificateSourceDto {
        private String logicalId;
        private String certificatePath;
        private String privateKeyPath;
        @SensitiveField
        private String privateKeyPassword;  // write-only; masked in GET responses
        private String  status;          // VALID | EXPIRING_SOON | EXPIRED
    }

    @Data @Builder @NoArgsConstructor @AllArgsConstructor
    public static class DirectorySourceDto {
        private String directoryPath;
        private String logicalId;
        private String privateKeyPath;
        @SensitiveField
        private String privateKeyPassword;  // write-only; masked in GET responses
        private boolean watchForChanges;
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
        private boolean enforceHeaderPredicate;
        private String  tenantIdHeader;
        private boolean allowCrossTenantsForSuperAdmin;
    }
}

