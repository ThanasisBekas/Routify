package gr.routify.admin.gateway.controller;

import gr.routify.admin.gateway.dto.GatewayConfigDto;
import gr.routify.admin.gateway.dto.GatewayConfigDto.*;
import gr.routify.admin.gateway.service.GatewayActuatorClient;
import gr.routify.admin.gateway.service.GatewayConfigService;
import gr.routify.admin.client.CertVaultMessagingClient;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

/**
 * Gateway Configuration Controller.
 *
 * <p>Provides full CRUD management of every configurable capability in routify-api-gateway:
 * CORS, security headers, rate-limit policies, circuit breaker defaults,
 * retry &amp; timeout defaults, auth providers, downstream credentials,
 * client-ID mappings, TLS / certificate sources, upstream proxy,
 * HTTP client pool, global filter toggles, and tenant isolation settings.
 *
 * <p>Every write immediately persists to Redis and notifies the gateway
 * to hot-reload — no restart required.
 */
@RestController
@RequestMapping("/api/v1/admin/gateway")
@RequiredArgsConstructor
public class GatewayConfigController {

    private final GatewayConfigService     configService;
    private final GatewayActuatorClient    actuatorClient;
    private final CertVaultMessagingClient certVaultClient;

    // ─── Full config ─────────────────────────────────────────────────────────

    @GetMapping("/config")
    public GatewayConfigDto getConfig() {
        return configService.getConfig();
    }

    @PutMapping("/config")
    public GatewayConfigDto saveConfig(
            @RequestBody GatewayConfigDto dto,
            Authentication auth) {
        return configService.saveConfig(dto, actor(auth));
    }

    // ─── Live status ─────────────────────────────────────────────────────────

    @GetMapping("/status")
    public Map<String, Object> getStatus() {
        Map<String, Object> health      = actuatorClient.getHealth();
        Map<String, Object> routes      = actuatorClient.getLiveRoutes();
        Map<String, Object> cbs         = actuatorClient.getCircuitBreakerStates();
        Map<String, Object> loadedConfig = actuatorClient.getLoadedConfig();
        return Map.of(
                "health",          health,
                "routes",          routes,
                "circuitBreakers", cbs,
                "loadedConfig",    loadedConfig
        );
    }

    @PostMapping("/reload")
    public Map<String, Object> triggerReload(Authentication auth) {
        return actuatorClient.triggerConfigReload();
    }

    @GetMapping("/metrics")
    public Map<String, Object> getMetrics() {
        return actuatorClient.getGatewayMetrics();
    }

    // ─── CORS ────────────────────────────────────────────────────────────────

    @GetMapping("/cors")
    public CorsConfig getCors() {
        return configService.getCors();
    }

    @PutMapping("/cors")
    public GatewayConfigDto updateCors(
            @RequestBody @Valid CorsConfig cors,
            Authentication auth) {
        return configService.updateCors(cors, actor(auth));
    }

    // ─── Security Headers ────────────────────────────────────────────────────

    @GetMapping("/security-headers")
    public SecurityHeadersConfig getSecurityHeaders() {
        return configService.getSecurityHeaders();
    }

    @PutMapping("/security-headers")
    public GatewayConfigDto updateSecurityHeaders(
            @RequestBody SecurityHeadersConfig sh,
            Authentication auth) {
        return configService.updateSecurityHeaders(sh, actor(auth));
    }

    // ─── Rate Limit Policies ─────────────────────────────────────────────────

    @GetMapping("/rate-limit-policies")
    public List<RateLimitPolicyDto> getRateLimitPolicies() {
        return configService.getRateLimitPolicies();
    }

    @PutMapping("/rate-limit-policies")
    public GatewayConfigDto setRateLimitPolicies(
            @RequestBody List<RateLimitPolicyDto> policies,
            Authentication auth) {
        return configService.updateRateLimitPolicies(policies, actor(auth));
    }

    @PutMapping("/rate-limit-policies/{policyId}")
    public GatewayConfigDto upsertRateLimitPolicy(
            @PathVariable String policyId,
            @RequestBody RateLimitPolicyDto policy,
            Authentication auth) {
        policy.setId(policyId);
        return configService.upsertRateLimitPolicy(policy, actor(auth));
    }

    @DeleteMapping("/rate-limit-policies/{policyId}")
    public ResponseEntity<Void> deleteRateLimitPolicy(
            @PathVariable String policyId,
            Authentication auth) {
        configService.deleteRateLimitPolicy(policyId, actor(auth));
        return ResponseEntity.noContent().build();
    }

    // ─── Circuit Breaker Defaults ────────────────────────────────────────────

    @GetMapping("/circuit-breaker")
    public CircuitBreakerDefaultsDto getCircuitBreakerDefaults() {
        return configService.getCircuitBreakerDefaults();
    }

    @PutMapping("/circuit-breaker")
    public GatewayConfigDto updateCircuitBreakerDefaults(
            @RequestBody CircuitBreakerDefaultsDto cb,
            Authentication auth) {
        return configService.updateCircuitBreakerDefaults(cb, actor(auth));
    }

    @GetMapping("/circuit-breaker/states")
    public Map<String, Object> getCircuitBreakerStates() {
        return actuatorClient.getCircuitBreakerStates();
    }

    // ─── Resilience Defaults (Retry / Timeout / Bulkhead) ───────────────────

    @GetMapping("/resilience")
    public ResilienceDefaultsDto getResilienceDefaults() {
        return configService.getResilienceDefaults();
    }

    @PutMapping("/resilience")
    public GatewayConfigDto updateResilienceDefaults(
            @RequestBody ResilienceDefaultsDto rd,
            Authentication auth) {
        return configService.updateResilienceDefaults(rd, actor(auth));
    }

    // ─── Auth Providers ──────────────────────────────────────────────────────

    @GetMapping("/auth-providers")
    public List<AuthProviderDto> getAuthProviders() {
        return maskSecrets(configService.getAuthProviders());
    }

    @PutMapping("/auth-providers/{providerId}")
    public GatewayConfigDto upsertAuthProvider(
            @PathVariable String providerId,
            @RequestBody AuthProviderDto provider,
            Authentication auth) {
        provider.setId(providerId);
        return configService.upsertAuthProvider(provider, actor(auth));
    }

    @DeleteMapping("/auth-providers/{providerId}")
    public ResponseEntity<Void> deleteAuthProvider(
            @PathVariable String providerId,
            Authentication auth) {
        configService.deleteAuthProvider(providerId, actor(auth));
        return ResponseEntity.noContent().build();
    }


    // ─── TLS / Certificates ──────────────────────────────────────────────────

    @GetMapping("/tls")
    public TlsConfigDto getTlsConfig() {
        return maskTlsSecrets(configService.getTlsConfig());
    }

    @PutMapping("/tls")
    public GatewayConfigDto updateTlsConfig(
            @RequestBody TlsConfigDto tls,
            Authentication auth) {
        return configService.updateTlsConfig(tls, actor(auth));
    }

    @GetMapping("/tls/certificates")
    public Map<String, Object> getLiveCertificates() {
        return actuatorClient.getCertificates();
    }

    /**
     * List active vault certificates that are already mapped to a gateway TLS logical ID.
     * Used by the admin dashboard TLS tab to show the vault ↔ gateway connection status.
     * Routes through cert-vault's {@code certs.gateway.snapshot} RabbitMQ queue.
     */
    @GetMapping("/tls/vault-certs")
    public Object getVaultCertificates(
            @RequestHeader(value = "X-Tenant-Id", required = false) java.util.UUID tenantId) {
        return certVaultClient.listGatewayMappedCertificates(tenantId);
    }

    // ─── Upstream Proxy ──────────────────────────────────────────────────────

    @GetMapping("/proxy")
    public ProxyConfigDto getProxyConfig() {
        ProxyConfigDto proxy = configService.getProxyConfig();
        if (proxy != null) proxy.setPassword(mask(proxy.getPassword()));
        return proxy;
    }

    @PutMapping("/proxy")
    public GatewayConfigDto updateProxyConfig(
            @RequestBody ProxyConfigDto proxy,
            Authentication auth) {
        return configService.updateProxyConfig(proxy, actor(auth));
    }

    // ─── HTTP Client ─────────────────────────────────────────────────────────

    @GetMapping("/http-client")
    public HttpClientConfigDto getHttpClientConfig() {
        return configService.getHttpClientConfig();
    }

    @PutMapping("/http-client")
    public GatewayConfigDto updateHttpClientConfig(
            @RequestBody HttpClientConfigDto httpClient,
            Authentication auth) {
        return configService.updateHttpClientConfig(httpClient, actor(auth));
    }

    // ─── Global Filters ──────────────────────────────────────────────────────

    @GetMapping("/global-filters")
    public GlobalFiltersConfig getGlobalFilters() {
        return configService.getGlobalFilters();
    }

    @PutMapping("/global-filters")
    public GatewayConfigDto updateGlobalFilters(
            @RequestBody GlobalFiltersConfig gf,
            Authentication auth) {
        return configService.updateGlobalFilters(gf, actor(auth));
    }

    // ─── Tenant Isolation ────────────────────────────────────────────────────

    @GetMapping("/tenant-isolation")
    public TenantIsolationConfig getTenantIsolation() {
        return configService.getTenantIsolation();
    }

    @PutMapping("/tenant-isolation")
    public GatewayConfigDto updateTenantIsolation(
            @RequestBody TenantIsolationConfig ti,
            Authentication auth) {
        return configService.updateTenantIsolation(ti, actor(auth));
    }

    // ─── Helpers ───────────────────────────────────────────────────────────���─

    private String actor(Authentication auth) {
        return auth != null ? auth.getName() : "system";
    }

    private List<AuthProviderDto> maskSecrets(List<AuthProviderDto> providers) {
        return providers.stream().map(p -> {
            var copy = new AuthProviderDto();
            copy.setId(p.getId());
            copy.setName(p.getName());
            copy.setType(p.getType());
            copy.setUri(p.getUri());
            copy.setClientId(p.getClientId());
            copy.setClientSecret(mask(p.getClientSecret()));
            copy.setScope(p.getScope());
            copy.setUsername(p.getUsername());
            copy.setPassword(mask(p.getPassword()));
            copy.setParameterStyle(p.getParameterStyle());
            copy.setParameterName(p.getParameterName());
            copy.setAdditionalParameters(p.getAdditionalParameters());
            copy.setEnabled(p.isEnabled());
            copy.setJwksUri(p.getJwksUri());
            copy.setIssuer(p.getIssuer());
            copy.setAudience(p.getAudience());
            copy.setAlgorithm(p.getAlgorithm());
            return copy;
        }).toList();
    }


    private String mask(String value) {
        if (value == null || value.isBlank()) return null;
        return "••••••••";
    }

    private TlsConfigDto maskTlsSecrets(TlsConfigDto tls) {
        if (tls == null) return null;
        if (tls.getFileSources() != null) {
            tls.getFileSources().forEach(s -> s.setPrivateKeyPassword(mask(s.getPrivateKeyPassword())));
        }
        if (tls.getDirectorySources() != null) {
            tls.getDirectorySources().forEach(s -> s.setPrivateKeyPassword(mask(s.getPrivateKeyPassword())));
        }
        return tls;
    }
}
