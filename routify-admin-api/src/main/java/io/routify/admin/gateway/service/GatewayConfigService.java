package io.routify.admin.gateway.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.routify.admin.gateway.dto.GatewayConfigDto;
import io.routify.admin.gateway.dto.GatewayConfigDto.*;
import io.routify.common.web.RoutifyHeaders;
import io.routify.common.web.Sensitive;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Gateway configuration service — DB-first, Redis write-through cache.
 *
 * <h3>Storage Architecture (Rollout-Safe)</h3>
 * <pre>
 *  Admin Dashboard
 *       │  PUT /api/v1/admin/gateway/...
 *       ▼
 *  routify-admin-api  (this service)
 *       │  1. Write full config to routify-route-service DB  ← durable source of truth
 *       │  2. Write to Redis cache  ← fast reads, TTL=1h
 *       │  3. GatewayConfigChanged published via Outbox→Kafka
 *       ▼
 *  routify.gateway.config Kafka topic
 *       │
 *       ├── gateway pod 1 ─► reload config from route-service DB
 *       ├── gateway pod 2 ─► reload config from route-service DB
 *       └── gateway pod N ─► reload config from route-service DB
 * </pre>
 *
 * <h3>On Gateway Startup / Rollout</h3>
 * <p>New pods call {@code GET /api/v1/gateway-config} on route-service at startup.
 * Route-service reads from PostgreSQL — Redis is never required for startup,
 * so Redis flushes and restarts are completely harmless.
 *
 * <h3>Redis Cache Strategy</h3>
 * <ul>
 *   <li>TTL: 1 hour (configurable)</li>
 *   <li>Write-through: every save writes to Redis immediately after DB write</li>
 *   <li>Read: Redis first, fall back to route-service DB if cache miss</li>
 *   <li>Cache miss on Redis flush → transparent fallback to DB, no manual intervention</li>
 * </ul>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class GatewayConfigService {

    /** Redis key for the cached full config */
    static final String CACHE_KEY = "routify:admin:gateway:config";
    /** Cache TTL — long enough to avoid DB hammering, short enough to self-heal */
    static final Duration CACHE_TTL = Duration.ofHours(1);

    /** BCrypt encoder (strength 12) — used to hash BASIC auth provider passwords before persisting. */
    private static final BCryptPasswordEncoder BCRYPT = new BCryptPasswordEncoder(12);

    private final StringRedisTemplate      redisTemplate;
    private final ObjectMapper             objectMapper;
    private final GatewayActuatorClient    gatewayActuatorClient;
    private final RouteServiceConfigClient routeServiceConfigClient;

    // ─── Read ─────────────────────────────────────────────────────────────────

    /**
     * Returns the full gateway config.
     * Priority: Redis cache → route-service DB → hardcoded defaults.
     */
    public GatewayConfigDto getConfig() {
        // 1. Try Redis cache (fast path)
        try {
            String json = redisTemplate.opsForValue().get(CACHE_KEY);
            if (json != null && !json.isBlank()) {
                log.debug("Gateway config loaded from Redis cache");
                return objectMapper.readValue(json, GatewayConfigDto.class);
            }
        } catch (Exception e) {
            log.warn("Redis cache miss or error: {}", e.getMessage());
        }

        // 2. Fall back to DB via route-service (always available, survives Redis flushes)
        try {
            Map<String, Object> dbConfig = routeServiceConfigClient.fetchConfig();
            if (dbConfig != null && !dbConfig.isEmpty()) {
                GatewayConfigDto dto = objectMapper.convertValue(dbConfig, GatewayConfigDto.class);
                // Re-populate cache from DB
                cacheToRedis(dto);
                log.info("Gateway config loaded from DB (cache was empty/stale)");
                return dto;
            }
        } catch (Exception e) {
            log.warn("Failed to load gateway config from DB: {}", e.getMessage());
        }

        // 3. Last resort: hardcoded defaults (first-run scenario)
        log.info("No persisted gateway config found — using defaults");
        return buildDefaults();
    }

    // Delegating getters
    public CorsConfig getCors()                                 { return getConfig().getCors(); }
    public SecurityHeadersConfig getSecurityHeaders()           { return getConfig().getSecurityHeaders(); }
    public List<RateLimitPolicyDto> getRateLimitPolicies()      { var l = getConfig().getRateLimitPolicies(); return l != null ? l : new ArrayList<>(); }
    public CircuitBreakerDefaultsDto getCircuitBreakerDefaults(){ return getConfig().getCircuitBreakerDefaults(); }
    public ResilienceDefaultsDto getResilienceDefaults()        { return getConfig().getResilienceDefaults(); }
    public List<AuthProviderDto> getAuthProviders()             { var l = getConfig().getAuthProviders(); return l != null ? l : new ArrayList<>(); }
    public TlsConfigDto getTlsConfig()                         { return getConfig().getTlsConfig(); }
    public ProxyConfigDto getProxyConfig()                     { return getConfig().getProxyConfig(); }
    public HttpClientConfigDto getHttpClientConfig()           { return getConfig().getHttpClientConfig(); }
    public GlobalFiltersConfig getGlobalFilters()              { return getConfig().getGlobalFilters(); }
    public TenantIsolationConfig getTenantIsolation()          { return getConfig().getTenantIsolation(); }
    public List<GlobalFilterEntryDto> getGlobalFilterEntries() { var l = getConfig().getGlobalFilterEntries(); return l != null ? l : new ArrayList<>(); }

    // ─── Write ────────────────────────────────────────────────────────────────

    /**
     * Persists the full config: DB first (durable), then Redis cache (fast reads).
     * Publishes {@code GatewayConfigChanged} via the transactional outbox in route-service,
     * causing all gateway pods to reload.
     */
    public GatewayConfigDto saveConfig(GatewayConfigDto dto, String updatedBy) {
        GatewayConfigDto existing = getConfig();
        mergeInto(existing, dto);
        hashBasicAuthPasswords(existing);
        return persistAndNotify(existing, updatedBy, "full");
    }

    public GatewayConfigDto updateCors(CorsConfig cors, String updatedBy) {
        GatewayConfigDto cfg = getConfig(); cfg.setCors(cors);
        return persistAndNotify(cfg, updatedBy, "CORS");
    }

    public GatewayConfigDto updateSecurityHeaders(SecurityHeadersConfig sh, String updatedBy) {
        GatewayConfigDto cfg = getConfig(); cfg.setSecurityHeaders(sh);
        return persistAndNotify(cfg, updatedBy, "SECURITY_HEADERS");
    }

    public GatewayConfigDto updateRateLimitPolicies(List<RateLimitPolicyDto> policies, String updatedBy) {
        GatewayConfigDto cfg = getConfig(); cfg.setRateLimitPolicies(policies);
        return persistAndNotify(cfg, updatedBy, "RATE_LIMIT");
    }

    public GatewayConfigDto upsertRateLimitPolicy(RateLimitPolicyDto policy, String updatedBy) {
        GatewayConfigDto cfg = getConfig();
        List<RateLimitPolicyDto> list = new ArrayList<>(cfg.getRateLimitPolicies() != null ? cfg.getRateLimitPolicies() : List.of());
        list.removeIf(p -> p.getId() != null && p.getId().equals(policy.getId()));
        list.add(policy);
        cfg.setRateLimitPolicies(list);
        return persistAndNotify(cfg, updatedBy, "RATE_LIMIT");
    }

    public GatewayConfigDto deleteRateLimitPolicy(String policyId, String updatedBy) {
        GatewayConfigDto cfg = getConfig();
        List<RateLimitPolicyDto> list = new ArrayList<>(cfg.getRateLimitPolicies() != null ? cfg.getRateLimitPolicies() : List.of());
        list.removeIf(p -> policyId.equals(p.getId()));
        cfg.setRateLimitPolicies(list);
        return persistAndNotify(cfg, updatedBy, "RATE_LIMIT");
    }

    public GatewayConfigDto updateCircuitBreakerDefaults(CircuitBreakerDefaultsDto cb, String updatedBy) {
        GatewayConfigDto cfg = getConfig(); cfg.setCircuitBreakerDefaults(cb);
        return persistAndNotify(cfg, updatedBy, "CIRCUIT_BREAKER");
    }

    public GatewayConfigDto updateResilienceDefaults(ResilienceDefaultsDto rd, String updatedBy) {
        GatewayConfigDto cfg = getConfig(); cfg.setResilienceDefaults(rd);
        return persistAndNotify(cfg, updatedBy, "RESILIENCE");
    }


    public GatewayConfigDto upsertAuthProvider(AuthProviderDto provider, String updatedBy) {
        GatewayConfigDto cfg = getConfig();
        List<AuthProviderDto> list = new ArrayList<>(cfg.getAuthProviders() != null ? cfg.getAuthProviders() : List.of());

        // If the incoming secrets are the mask sentinel, preserve the currently stored values.
        list.stream()
            .filter(p -> provider.getId() != null && provider.getId().equals(p.getId()))
            .findFirst()
            .ifPresent(existing -> {
                if (Sensitive.isMasked(provider.getClientSecret())) {
                    provider.setClientSecret(existing.getClientSecret());
                }
                if (Sensitive.isMasked(provider.getPassword())) {
                    provider.setPassword(existing.getPassword());
                }
            });

        // BCrypt-hash the password for BASIC auth providers before persisting.
        // Skip if the password is null/blank, masked (preserved above), or already hashed.
        if ("BASIC".equalsIgnoreCase(provider.getType())
                && provider.getPassword() != null
                && !provider.getPassword().isBlank()
                && !Sensitive.isMasked(provider.getPassword())
                && !provider.getPassword().startsWith("$2")) {
            provider.setPassword(BCRYPT.encode(provider.getPassword()));
            log.info("BCrypt-hashed BASIC auth provider password for provider '{}'", provider.getId());
        }

        list.removeIf(p -> p.getId() != null && p.getId().equals(provider.getId()));
        list.add(provider);
        cfg.setAuthProviders(list);
        return persistAndNotify(cfg, updatedBy, "AUTH_PROVIDERS");
    }

    public GatewayConfigDto deleteAuthProvider(String providerId, String updatedBy) {
        GatewayConfigDto cfg = getConfig();
        List<AuthProviderDto> list = new ArrayList<>(cfg.getAuthProviders() != null ? cfg.getAuthProviders() : List.of());
        list.removeIf(p -> providerId.equals(p.getId()));
        cfg.setAuthProviders(list);
        return persistAndNotify(cfg, updatedBy, "AUTH_PROVIDERS");
    }


    /**
     * Updates the TLS config section.
     * All certificate lifecycle is now handled by routify-cert-vault — this method
     * simply propagates any remaining config metadata (currently an empty stub).
     * The deprecated fileSources / directorySources / expiryWarning / fileWatchInterval
     * fields have been removed; use the Certificate Vault API instead.
     */
    public GatewayConfigDto updateTlsConfig(TlsConfigDto tls, String updatedBy) {
        GatewayConfigDto cfg = getConfig();

        cfg.setTlsConfig(tls);
        return persistAndNotify(cfg, updatedBy, "TLS");
    }

    public GatewayConfigDto updateProxyConfig(ProxyConfigDto proxy, String updatedBy) {
        GatewayConfigDto cfg = getConfig();
        // Preserve the stored password when the mask sentinel is submitted
        if (proxy != null && Sensitive.isMasked(proxy.getPassword())
                && cfg.getProxyConfig() != null) {
            proxy.setPassword(cfg.getProxyConfig().getPassword());
        }
        cfg.setProxyConfig(proxy);
        return persistAndNotify(cfg, updatedBy, "PROXY");
    }

    public GatewayConfigDto updateHttpClientConfig(HttpClientConfigDto httpClient, String updatedBy) {
        GatewayConfigDto cfg = getConfig(); cfg.setHttpClientConfig(httpClient);
        return persistAndNotify(cfg, updatedBy, "HTTP_CLIENT");
    }

    public GatewayConfigDto updateGlobalFilters(GlobalFiltersConfig gf, String updatedBy) {
        GatewayConfigDto cfg = getConfig(); cfg.setGlobalFilters(gf);
        return persistAndNotify(cfg, updatedBy, "GLOBAL_FILTERS");
    }

    public GatewayConfigDto updateTenantIsolation(TenantIsolationConfig ti, String updatedBy) {
        GatewayConfigDto cfg = getConfig(); cfg.setTenantIsolation(ti);
        return persistAndNotify(cfg, updatedBy, "TENANT_ISOLATION");
    }

    public GatewayConfigDto updateGlobalFilterEntries(List<GlobalFilterEntryDto> entries, String updatedBy) {
        GatewayConfigDto cfg = getConfig(); cfg.setGlobalFilterEntries(entries);
        return persistAndNotify(cfg, updatedBy, "GLOBAL_FILTER_ENTRIES");
    }

    // ─── Private ──────────────────────────────────────────────────────────────

    /**
     * BCrypt-hashes plain-text passwords on all BASIC auth providers in the config.
     * Skips null/blank values, masked sentinels, and values that are already BCrypt hashes.
     */
    private void hashBasicAuthPasswords(GatewayConfigDto config) {
        if (config.getAuthProviders() == null) return;
        for (AuthProviderDto provider : config.getAuthProviders()) {
            if ("BASIC".equalsIgnoreCase(provider.getType())
                    && provider.getPassword() != null
                    && !provider.getPassword().isBlank()
                    && !Sensitive.isMasked(provider.getPassword())
                    && !provider.getPassword().startsWith("$2")) {
                provider.setPassword(BCRYPT.encode(provider.getPassword()));
                log.info("BCrypt-hashed BASIC auth provider password for provider '{}'", provider.getId());
            }
        }
    }

    /**
     * Core save: DB write → Redis cache update → gateway notification.
     * The DB write is the most important step — it guarantees durability across rollouts.
     */
    private GatewayConfigDto persistAndNotify(GatewayConfigDto config, String updatedBy, String section) {
        config.setUpdatedAt(Instant.now());
        config.setUpdatedBy(updatedBy);

        // Step 1: Write to DB via route-service (DURABLE — survives restarts/rollouts)
        // Step 1: Save config to route-service via RabbitMQ (DURABLE — persists to PostgreSQL).
        // route-service's transactional outbox will automatically publish GatewayConfigChanged
        // to Kafka, causing all gateway pods to reload.
        try {
            Map<String, Object> configMap = objectMapper.convertValue(config, new TypeReference<>() {});
            routeServiceConfigClient.saveConfig(configMap, updatedBy, section);
            log.info("Gateway config persisted to DB via RabbitMQ: section={} by={}", section, updatedBy);
        } catch (Exception e) {
            log.error("CRITICAL: Failed to persist gateway config to DB: {}", e.getMessage(), e);
            throw new RuntimeException("Failed to save gateway configuration to database — changes not applied", e);
        }

        // Step 2: Write to Redis cache (fast reads, NOT source of truth)
        cacheToRedis(config);

        // Step 3: Additional manual Kafka trigger (belt-and-suspenders — the outbox already handles this)
        try {
            gatewayActuatorClient.triggerConfigReload();
        } catch (Exception e) {
            log.warn("Manual Kafka reload trigger failed (outbox event will still trigger reload): {}", e.getMessage());
        }

        return config;
    }

    private void cacheToRedis(GatewayConfigDto config) {
        try {
            String json = objectMapper.writeValueAsString(config);
            redisTemplate.opsForValue().set(CACHE_KEY, json, CACHE_TTL);
            log.debug("Gateway config written to Redis cache (TTL={})", CACHE_TTL);
        } catch (Exception e) {
            log.warn("Failed to write gateway config to Redis cache (DB is still the source of truth): {}", e.getMessage());
        }
    }

    private void mergeInto(GatewayConfigDto existing, GatewayConfigDto incoming) {
        if (incoming.getCors() != null)                existing.setCors(incoming.getCors());
        if (incoming.getSecurityHeaders() != null)     existing.setSecurityHeaders(incoming.getSecurityHeaders());
        if (incoming.getRateLimitPolicies() != null)   existing.setRateLimitPolicies(incoming.getRateLimitPolicies());
        if (incoming.getCircuitBreakerDefaults() != null) existing.setCircuitBreakerDefaults(incoming.getCircuitBreakerDefaults());
        if (incoming.getResilienceDefaults() != null)  existing.setResilienceDefaults(incoming.getResilienceDefaults());
        if (incoming.getAuthProviders() != null)       existing.setAuthProviders(incoming.getAuthProviders());
        if (incoming.getTlsConfig() != null)           existing.setTlsConfig(incoming.getTlsConfig());
        if (incoming.getProxyConfig() != null)         existing.setProxyConfig(incoming.getProxyConfig());
        if (incoming.getHttpClientConfig() != null)    existing.setHttpClientConfig(incoming.getHttpClientConfig());
        if (incoming.getGlobalFilters() != null)       existing.setGlobalFilters(incoming.getGlobalFilters());
        if (incoming.getTenantIsolation() != null)     existing.setTenantIsolation(incoming.getTenantIsolation());
        if (incoming.getGlobalFilterEntries() != null) existing.setGlobalFilterEntries(incoming.getGlobalFilterEntries());
    }

    private GatewayConfigDto buildDefaults() {
        return GatewayConfigDto.builder()
                .cors(CorsConfig.builder()
                        .enabled(true)
                        .allowedOriginPatterns(List.of("http://localhost:5173"))
                        .allowedMethods(List.of("GET","POST","PUT","DELETE","PATCH","OPTIONS"))
                        .allowedHeaders(List.of("*"))
                        .exposedHeaders(List.of(RoutifyHeaders.CORRELATION_ID, RoutifyHeaders.ROUTE_VERSION))
                        .allowCredentials(true).maxAge(3600).paths(List.of("/**")).build())
                .securityHeaders(SecurityHeadersConfig.builder()
                        .enabled(true).xContentTypeOptions(true).xFrameOptions(true)
                        .xFrameOptionsValue("DENY").xXssProtection(true)
                        .strictTransportSecurity(true).stsMaxAge(31536000L)
                        .stsIncludeSubDomains(true).stsPreload(false)
                        .referrerPolicy("strict-origin-when-cross-origin")
                        .permissionsPolicy("geolocation=(), camera=(), microphone=()")
                        .removeServerHeader(true).removePoweredByHeader(true).build())
                .rateLimitPolicies(List.of())
                .circuitBreakerDefaults(CircuitBreakerDefaultsDto.builder()
                        .slidingWindowType("COUNT_BASED").slidingWindowSize(100)
                        .minimumNumberOfCalls(10).failureRateThreshold(50.0)
                        .slowCallRateThreshold(100.0).slowCallDurationThresholdMs(60000)
                        .waitDurationInOpenState("10s").permittedNumberOfCallsInHalfOpenState(3)
                        .automaticTransitionFromOpenToHalfOpen(true)
                        .fallbackUri("forward:/fallback/503").build())
                .resilienceDefaults(ResilienceDefaultsDto.builder()
                        .retryMaxAttempts(3).retryWaitDuration("500ms")
                        .retryExponentialBackoff(true).retryExponentialMultiplier(2.0)
                        .retryMaxWaitDuration("2s").retryExceptions(List.of("java.io.IOException"))
                        .timeoutDuration("10s").timeoutCancelRunningFuture(true)
                        .bulkheadEnabled(false).bulkheadMaxConcurrentCalls(25).build())
                .authProviders(List.of())
                // TLS config is now an empty stub — all certificate management is
                // handled by routify-cert-vault via Cert Vault groups and CERT_GROUP_EVENTS.
                .tlsConfig(TlsConfigDto.builder().build())
                .proxyConfig(ProxyConfigDto.builder().enabled(false).type("HTTP").nonProxyHosts(List.of()).build())
                .httpClientConfig(HttpClientConfigDto.builder()
                        .connectTimeoutMs(6000).responseTimeoutMs(10000).maxConnections(500)
                        .maxConnectionsPerRoute(50).acquireTimeoutMs(45000)
                        .maxIdleTime("20s").maxLifeTime("60s")
                        .compressionEnabled(false).followRedirects(false).wiretapEnabled(false).build())
                .globalFilters(GlobalFiltersConfig.builder()
                        .correlationId(CorrelationIdConfig.builder().enabled(true)
                                .headerName(RoutifyHeaders.CORRELATION_ID).generateIfMissing(true).propagateToResponse(true).build())
                        .requestLogger(RequestLoggerConfig.builder().enabled(true)
                                .logRequestHeaders(true).logResponseHeaders(false)
                                .logRequestBody(false).logResponseBody(false).maxBodyLogSize(4096)
                                .excludePaths(List.of("/actuator/**"))
                                .maskHeaders(List.of("Authorization", RoutifyHeaders.API_KEY, "Cookie")).build())
                        .securityHeaders(SecurityHeadersRef.builder().enabled(true).build())
                        .tenantContext(TenantContextConfig.builder().enabled(true)
                                .tenantHeaderName(RoutifyHeaders.TENANT_ID).enforceOnAllRoutes(false).build())
                        .build())
                .tenantIsolation(TenantIsolationConfig.builder()
                        .enabled(true)
                        .tenantIdHeader(RoutifyHeaders.TENANT_ID).allowCrossTenantsForSuperAdmin(true).build())
                .globalFilterEntries(List.of())
                .build();
    }
}

