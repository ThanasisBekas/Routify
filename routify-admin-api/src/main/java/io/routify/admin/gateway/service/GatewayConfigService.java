package io.routify.admin.gateway.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.routify.admin.client.RouteFilterMessagingClient;
import io.routify.admin.gateway.dto.GatewayConfigDto;
import io.routify.admin.gateway.dto.GatewayConfigDto.*;
import io.routify.common.crypto.FieldEncryptionService;
import io.routify.common.event.QueryResponse;
import io.routify.common.exception.RoutifyException;
import io.routify.common.web.RoutifyHeaders;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

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

    /** Prefix for AES-256-GCM encrypted values produced by {@link FieldEncryptionService}. */
    private static final String ENC_PREFIX = "{enc}";

    private final StringRedisTemplate        redisTemplate;
    private final ObjectMapper               objectMapper;
    private final GatewayActuatorClient      gatewayActuatorClient;
    private final RouteServiceConfigClient   routeServiceConfigClient;
    private final RouteFilterMessagingClient  routeFilterMessagingClient;
    private final FieldEncryptionService     fieldEncryptionService;

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
    public List<DownstreamCredentialDto> getDownstreamCredentials() { var l = getConfig().getDownstreamCredentials(); return l != null ? l : new ArrayList<>(); }

    // ─── Write ────────────────────────────────────────────────────────────────

    /**
     * Persists the full config: DB first (durable), then Redis cache (fast reads).
     * Publishes {@code GatewayConfigChanged} via the transactional outbox in route-service,
     * causing all gateway pods to reload.
     */
    public GatewayConfigDto saveConfig(GatewayConfigDto dto, String updatedBy) {
        GatewayConfigDto existing = getConfig();
        mergeInto(existing, dto);
        encryptSecrets(existing);
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

    public void deleteRateLimitPolicy(String policyId, String updatedBy) {
        GatewayConfigDto cfg = getConfig();
        List<RateLimitPolicyDto> list = new ArrayList<>(cfg.getRateLimitPolicies() != null ? cfg.getRateLimitPolicies() : List.of());
        list.removeIf(p -> policyId.equals(p.getId()));
        cfg.setRateLimitPolicies(list);
        persistAndNotify(cfg, updatedBy, "RATE_LIMIT");
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

        // Handle password encryption / preservation for BASIC auth providers:
        //  1. null/blank  → leave untouched
        //  2. already AES-encrypted ({enc} prefix) → skip re-encryption
        //  3. legacy BCrypt hash ($2 prefix) → cannot reverse; leave as-is (gateway still supports BCrypt matching)
        //  4. plaintext  → AES-encrypt it
        if ("BASIC".equalsIgnoreCase(provider.getType())
                && provider.getPassword() != null
                && !provider.getPassword().isBlank()) {

            if (provider.getPassword().startsWith(ENC_PREFIX)) {
                // Already encrypted — no action needed
                log.debug("Password already AES-encrypted for BASIC auth provider '{}'", provider.getId());
            } else if (provider.getPassword().startsWith("$2")) {
                // Legacy BCrypt hash — leave as-is; gateway still supports BCrypt matching
                log.info("Preserving legacy BCrypt hash for BASIC auth provider '{}' — " +
                         "set a new plaintext password to migrate to AES encryption", provider.getId());
            } else {
                // New plaintext password — encrypt with AES-256-GCM
                provider.setPassword(fieldEncryptionService.encrypt(provider.getPassword()));
                log.info("AES-encrypted BASIC auth provider password for provider '{}'", provider.getId());
            }
        }

        // Handle clientSecret encryption for OAuth2 providers
        if (provider.getClientSecret() != null
                && !provider.getClientSecret().isBlank()
                && !provider.getClientSecret().startsWith(ENC_PREFIX)) {
            provider.setClientSecret(fieldEncryptionService.encrypt(provider.getClientSecret()));
            log.info("AES-encrypted clientSecret for auth provider '{}'", provider.getId());
        }

        list.removeIf(p -> p.getId() != null && p.getId().equals(provider.getId()));
        list.add(provider);
        cfg.setAuthProviders(list);
        return persistAndNotify(cfg, updatedBy, "AUTH_PROVIDERS");
    }

    public void deleteAuthProvider(String providerId, String updatedBy) {
        GatewayConfigDto cfg = getConfig();
        List<AuthProviderDto> list = new ArrayList<>(cfg.getAuthProviders() != null ? cfg.getAuthProviders() : List.of());
        list.removeIf(p -> providerId.equals(p.getId()));
        cfg.setAuthProviders(list);
        persistAndNotify(cfg, updatedBy, "AUTH_PROVIDERS");
    }

    public GatewayConfigDto updateProxyConfig(ProxyConfigDto proxy, String updatedBy) {
        GatewayConfigDto cfg = getConfig();
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
        GatewayConfigDto cfg = getConfig();
        cfg.setGlobalFilterEntries(enrichGlobalFilterEntries(entries));
        return persistAndNotify(cfg, updatedBy, "GLOBAL_FILTER_ENTRIES");
    }

    public GatewayConfigDto upsertDownstreamCredential(DownstreamCredentialDto credential, String updatedBy) {
        GatewayConfigDto cfg = getConfig();
        List<DownstreamCredentialDto> list = new ArrayList<>(cfg.getDownstreamCredentials() != null ? cfg.getDownstreamCredentials() : List.of());

        // Encrypt sensitive credential fields
        if (credential.getPassword() != null && !credential.getPassword().isBlank()
                && !credential.getPassword().startsWith(ENC_PREFIX)) {
            credential.setPassword(fieldEncryptionService.encrypt(credential.getPassword()));
        }
        if (credential.getHeaderValue() != null && !credential.getHeaderValue().isBlank()
                && !credential.getHeaderValue().startsWith(ENC_PREFIX)) {
            credential.setHeaderValue(fieldEncryptionService.encrypt(credential.getHeaderValue()));
        }

        list.removeIf(c -> c.getId() != null && c.getId().equals(credential.getId()));
        list.add(credential);
        cfg.setDownstreamCredentials(list);
        return persistAndNotify(cfg, updatedBy, "DOWNSTREAM_CREDENTIALS");
    }

    public void deleteDownstreamCredential(String credentialId, String updatedBy) {
        GatewayConfigDto cfg = getConfig();
        List<DownstreamCredentialDto> list = new ArrayList<>(cfg.getDownstreamCredentials() != null ? cfg.getDownstreamCredentials() : List.of());
        list.removeIf(c -> credentialId.equals(c.getId()));
        cfg.setDownstreamCredentials(list);
        persistAndNotify(cfg, updatedBy, "DOWNSTREAM_CREDENTIALS");
    }

    // ─── Private ──────────────────────────────────────────────────────────────

    /**
     * Enriches global filter entries with each filter's config and gatewayConfigRef
     * from the database. The gateway needs these to build correct filter definitions
     * (e.g. rate limiter settings, auth provider refs) — without them, global filters
     * that require config would silently fall back to defaults or produce no-ops.
     */
    private List<GlobalFilterEntryDto> enrichGlobalFilterEntries(List<GlobalFilterEntryDto> entries) {
        if (entries == null || entries.isEmpty()) return entries;

        List<GlobalFilterEntryDto> enriched = new ArrayList<>(entries.size());
        for (GlobalFilterEntryDto entry : entries) {
            try {
                UUID filterId = UUID.fromString(entry.getFilterId());
                // Fetch filter detail from route-service — includes config JSONB and gatewayConfigRef
                QueryResponse.FilterDetail detail = routeFilterMessagingClient.getFilter(filterId, null);
                if (detail != null) {
                    Map<String, Object> config = detail.config() != null ? detail.config() : Map.of();
                    Map<String, Object> gcRef = detail.gatewayConfigRef() != null
                            ? Map.of(
                                "refType", detail.gatewayConfigRef().refType(),
                                "refId",   detail.gatewayConfigRef().refId(),
                                "refName", detail.gatewayConfigRef().refName() != null
                                           ? detail.gatewayConfigRef().refName() : "")
                            : null;
                    enriched.add(GlobalFilterEntryDto.builder()
                            .filterId(entry.getFilterId())
                            .filterName(entry.getFilterName())
                            .filterType(entry.getFilterType())
                            .order(entry.getOrder())
                            .enabled(entry.isEnabled())
                            .config(config)
                            .gatewayConfigRef(gcRef)
                            .build());
                    log.debug("Enriched global filter entry '{}' (type={}) with config ({} keys) and gatewayConfigRef={}",
                            entry.getFilterName(), entry.getFilterType(), config.size(), gcRef != null);
                } else {
                    log.warn("Could not fetch filter detail for global entry filterId={} — persisting without config",
                            entry.getFilterId());
                    enriched.add(entry);
                }
            } catch (Exception e) {
                log.warn("Failed to enrich global filter entry filterId={}: {} — persisting without config",
                        entry.getFilterId(), e.getMessage());
                enriched.add(entry);
            }
        }
        return enriched;
    }

    /**
     * AES-encrypts plaintext passwords and secrets on all auth providers in the config.
     * Skips null/blank values and values that are already AES-encrypted ({@code {enc}} prefix)
     * or legacy BCrypt hashes ({@code $2} prefix).
     */
    private void encryptSecrets(GatewayConfigDto config) {
        if (config.getAuthProviders() != null) {
            for (AuthProviderDto provider : config.getAuthProviders()) {
                // Encrypt BASIC auth passwords
                if ("BASIC".equalsIgnoreCase(provider.getType())
                        && provider.getPassword() != null
                        && !provider.getPassword().isBlank()
                        && !provider.getPassword().startsWith(ENC_PREFIX)
                        && !provider.getPassword().startsWith("$2")) {
                    provider.setPassword(fieldEncryptionService.encrypt(provider.getPassword()));
                }
                // Encrypt OAuth2 client secrets
                if (provider.getClientSecret() != null
                        && !provider.getClientSecret().isBlank()
                        && !provider.getClientSecret().startsWith(ENC_PREFIX)) {
                    provider.setClientSecret(fieldEncryptionService.encrypt(provider.getClientSecret()));
                }
            }
        }
        // Encrypt proxy password
        if (config.getProxyConfig() != null
                && config.getProxyConfig().getPassword() != null
                && !config.getProxyConfig().getPassword().isBlank()
                && !config.getProxyConfig().getPassword().startsWith(ENC_PREFIX)) {
            config.getProxyConfig().setPassword(
                    fieldEncryptionService.encrypt(config.getProxyConfig().getPassword()));
        }
        // Encrypt downstream credential secrets
        if (config.getDownstreamCredentials() != null) {
            for (DownstreamCredentialDto cred : config.getDownstreamCredentials()) {
                if (cred.getPassword() != null && !cred.getPassword().isBlank()
                        && !cred.getPassword().startsWith(ENC_PREFIX)) {
                    cred.setPassword(fieldEncryptionService.encrypt(cred.getPassword()));
                }
                if (cred.getHeaderValue() != null && !cred.getHeaderValue().isBlank()
                        && !cred.getHeaderValue().startsWith(ENC_PREFIX)) {
                    cred.setHeaderValue(fieldEncryptionService.encrypt(cred.getHeaderValue()));
                }
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
            throw new RoutifyException.GatewayError("Failed to save gateway configuration to database — changes not applied", e);
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
        if (incoming.getDownstreamCredentials() != null) existing.setDownstreamCredentials(incoming.getDownstreamCredentials());
    }

    // ─── Secret decryption (read path) ────────────────────────────────────────

    /**
     * Returns the full config with all AES-encrypted secrets decrypted to plaintext.
     * Used by controller read endpoints so the dashboard can display and edit actual values.
     *
     * <p>Legacy BCrypt-hashed passwords ({@code $2} prefix) are returned as-is —
     * they cannot be reversed. The dashboard will show the hash; re-saving with a
     * new plaintext password migrates the entry to AES encryption.
     */
    public GatewayConfigDto getConfigDecrypted() {
        GatewayConfigDto cfg = getConfig();
        decryptAuthProviderSecrets(cfg);
        decryptDownstreamCredentialSecrets(cfg);
        decryptProxyPassword(cfg);
        return cfg;
    }

    /**
     * Returns auth providers with secrets decrypted.
     */
    public List<AuthProviderDto> getAuthProvidersDecrypted() {
        List<AuthProviderDto> list = getAuthProviders();
        list.forEach(this::decryptSingleAuthProvider);
        return list;
    }

    /**
     * Returns downstream credentials with secrets decrypted.
     */
    public List<DownstreamCredentialDto> getDownstreamCredentialsDecrypted() {
        List<DownstreamCredentialDto> list = getDownstreamCredentials();
        list.forEach(this::decryptSingleDownstreamCredential);
        return list;
    }

    /**
     * Returns proxy config with password decrypted.
     */
    public ProxyConfigDto getProxyConfigDecrypted() {
        ProxyConfigDto proxy = getProxyConfig();
        if (proxy != null && proxy.getPassword() != null
                && proxy.getPassword().startsWith(ENC_PREFIX)) {
            proxy.setPassword(fieldEncryptionService.decrypt(proxy.getPassword()));
        }
        return proxy;
    }

    private void decryptAuthProviderSecrets(GatewayConfigDto cfg) {
        if (cfg.getAuthProviders() == null) return;
        cfg.getAuthProviders().forEach(this::decryptSingleAuthProvider);
    }

    private void decryptSingleAuthProvider(AuthProviderDto p) {
        if (p.getPassword() != null && p.getPassword().startsWith(ENC_PREFIX)) {
            p.setPassword(fieldEncryptionService.decrypt(p.getPassword()));
        }
        if (p.getClientSecret() != null && p.getClientSecret().startsWith(ENC_PREFIX)) {
            p.setClientSecret(fieldEncryptionService.decrypt(p.getClientSecret()));
        }
    }

    private void decryptDownstreamCredentialSecrets(GatewayConfigDto cfg) {
        if (cfg.getDownstreamCredentials() == null) return;
        cfg.getDownstreamCredentials().forEach(this::decryptSingleDownstreamCredential);
    }

    private void decryptSingleDownstreamCredential(DownstreamCredentialDto c) {
        if (c.getPassword() != null && c.getPassword().startsWith(ENC_PREFIX)) {
            c.setPassword(fieldEncryptionService.decrypt(c.getPassword()));
        }
        if (c.getHeaderValue() != null && c.getHeaderValue().startsWith(ENC_PREFIX)) {
            c.setHeaderValue(fieldEncryptionService.decrypt(c.getHeaderValue()));
        }
    }

    private void decryptProxyPassword(GatewayConfigDto cfg) {
        if (cfg.getProxyConfig() != null
                && cfg.getProxyConfig().getPassword() != null
                && cfg.getProxyConfig().getPassword().startsWith(ENC_PREFIX)) {
            cfg.getProxyConfig().setPassword(
                    fieldEncryptionService.decrypt(cfg.getProxyConfig().getPassword()));
        }
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
                // Replaced from global filter entries mark as deprecated
                .globalFilters(GlobalFiltersConfig.builder().build())
                .tenantIsolation(TenantIsolationConfig.builder()
                        .enabled(true)
                        .tenantIdHeader(RoutifyHeaders.TENANT_ID).allowCrossTenantsForSuperAdmin(true).build())
                .globalFilterEntries(List.of())
                .downstreamCredentials(List.of())
                .build();
    }
}

