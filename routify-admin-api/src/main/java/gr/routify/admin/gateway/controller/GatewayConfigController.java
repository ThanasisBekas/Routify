package gr.routify.admin.gateway.controller;

import gr.routify.admin.gateway.dto.GatewayConfigDto;
import gr.routify.admin.gateway.dto.GatewayConfigDto.*;
import gr.routify.admin.gateway.service.GatewayActuatorClient;
import gr.routify.admin.gateway.service.GatewayConfigService;
import gr.routify.admin.client.CertVaultMessagingClient;
import gr.routify.common.event.QueryResponse;
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
    public ResponseEntity<GatewayConfigDto> getConfig() {
        return ResponseEntity.ok(configService.getConfig());
    }

    @PutMapping("/config")
    public ResponseEntity<GatewayConfigDto> saveConfig(
            @RequestBody GatewayConfigDto dto,
            Authentication auth) {
        return ResponseEntity.ok(configService.saveConfig(dto, actor(auth)));
    }

    // ─── Live status ─────────────────────────────────────────────────────────

    @GetMapping("/status")
    public ResponseEntity<Map<String, Object>> getStatus() {
        Map<String, Object> health      = actuatorClient.getHealth();
        Map<String, Object> routes      = actuatorClient.getLiveRoutes();
        Map<String, Object> cbs         = actuatorClient.getCircuitBreakerStates();
        Map<String, Object> loadedConfig = actuatorClient.getLoadedConfig();
        return ResponseEntity.ok(Map.of(
                "health",          health,
                "routes",          routes,
                "circuitBreakers", cbs,
                "loadedConfig",    loadedConfig
        ));
    }

    @PostMapping("/reload")
    public ResponseEntity<Map<String, Object>> triggerReload(Authentication auth) {
        return ResponseEntity.ok(actuatorClient.triggerConfigReload());
    }

    @GetMapping("/metrics")
    public ResponseEntity<Map<String, Object>> getMetrics() {
        return ResponseEntity.ok(actuatorClient.getGatewayMetrics());
    }

    // ─── CORS ────────────────────────────────────────────────────────────────

    @GetMapping("/cors")
    public ResponseEntity<CorsConfig> getCors() {
        return ResponseEntity.ok(configService.getCors());
    }

    @PutMapping("/cors")
    public ResponseEntity<GatewayConfigDto> updateCors(
            @RequestBody @Valid CorsConfig cors,
            Authentication auth) {
        return ResponseEntity.ok(configService.updateCors(cors, actor(auth)));
    }

    // ─── Security Headers ────────────────────────────────────────────────────

    @GetMapping("/security-headers")
    public ResponseEntity<SecurityHeadersConfig> getSecurityHeaders() {
        return ResponseEntity.ok(configService.getSecurityHeaders());
    }

    @PutMapping("/security-headers")
    public ResponseEntity<GatewayConfigDto> updateSecurityHeaders(
            @RequestBody SecurityHeadersConfig sh,
            Authentication auth) {
        return ResponseEntity.ok(configService.updateSecurityHeaders(sh, actor(auth)));
    }

    // ─── Rate Limit Policies ─────────────────────────────────────────────────

    @GetMapping("/rate-limit-policies")
    public ResponseEntity<List<RateLimitPolicyDto>> getRateLimitPolicies() {
        return ResponseEntity.ok(configService.getRateLimitPolicies());
    }

    @PutMapping("/rate-limit-policies")
    public ResponseEntity<GatewayConfigDto> setRateLimitPolicies(
            @RequestBody List<RateLimitPolicyDto> policies,
            Authentication auth) {
        return ResponseEntity.ok(configService.updateRateLimitPolicies(policies, actor(auth)));
    }

    @PutMapping("/rate-limit-policies/{policyId}")
    public ResponseEntity<GatewayConfigDto> upsertRateLimitPolicy(
            @PathVariable String policyId,
            @RequestBody RateLimitPolicyDto policy,
            Authentication auth) {
        policy.setId(policyId);
        return ResponseEntity.ok(configService.upsertRateLimitPolicy(policy, actor(auth)));
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
    public ResponseEntity<CircuitBreakerDefaultsDto> getCircuitBreakerDefaults() {
        return ResponseEntity.ok(configService.getCircuitBreakerDefaults());
    }

    @PutMapping("/circuit-breaker")
    public ResponseEntity<GatewayConfigDto> updateCircuitBreakerDefaults(
            @RequestBody CircuitBreakerDefaultsDto cb,
            Authentication auth) {
        return ResponseEntity.ok(configService.updateCircuitBreakerDefaults(cb, actor(auth)));
    }

    @GetMapping("/circuit-breaker/states")
    public ResponseEntity<Map<String, Object>> getCircuitBreakerStates() {
        return ResponseEntity.ok(actuatorClient.getCircuitBreakerStates());
    }

    // ─── Resilience Defaults (Retry / Timeout / Bulkhead) ───────────────────

    @GetMapping("/resilience")
    public ResponseEntity<ResilienceDefaultsDto> getResilienceDefaults() {
        return ResponseEntity.ok(configService.getResilienceDefaults());
    }

    @PutMapping("/resilience")
    public ResponseEntity<GatewayConfigDto> updateResilienceDefaults(
            @RequestBody ResilienceDefaultsDto rd,
            Authentication auth) {
        return ResponseEntity.ok(configService.updateResilienceDefaults(rd, actor(auth)));
    }

    // ─── Auth Providers ──────────────────────────────────────────────────────

    @GetMapping("/auth-providers")
    public ResponseEntity<List<AuthProviderDto>> getAuthProviders() {
        return ResponseEntity.ok(maskSecrets(configService.getAuthProviders()));
    }

    @PutMapping("/auth-providers/{providerId}")
    public ResponseEntity<GatewayConfigDto> upsertAuthProvider(
            @PathVariable String providerId,
            @RequestBody AuthProviderDto provider,
            Authentication auth) {
        provider.setId(providerId);
        return ResponseEntity.ok(configService.upsertAuthProvider(provider, actor(auth)));
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
    public ResponseEntity<TlsConfigDto> getTlsConfig() {
        return ResponseEntity.ok(maskTlsSecrets(configService.getTlsConfig()));
    }

    @PutMapping("/tls")
    public ResponseEntity<GatewayConfigDto> updateTlsConfig(
            @RequestBody TlsConfigDto tls,
            Authentication auth) {
        return ResponseEntity.ok(configService.updateTlsConfig(tls, actor(auth)));
    }

    @GetMapping("/tls/certificates")
    public ResponseEntity<Map<String, Object>> getLiveCertificates() {
        return ResponseEntity.ok(actuatorClient.getCertificates());
    }

    /**
     * List active vault certificates that are already mapped to a gateway TLS logical ID.
     * Used by the admin dashboard TLS tab to show the vault ↔ gateway connection status.
     * Routes through cert-vault's {@code certs.gateway.snapshot} RabbitMQ queue.
     */
    @GetMapping("/tls/vault-certs")
    public ResponseEntity<QueryResponse.CertsList> getVaultCertificates(
            @RequestHeader(value = "X-Tenant-Id", required = false) java.util.UUID tenantId) {
        return ResponseEntity.ok(certVaultClient.listGatewayMappedCertificates(tenantId));
    }

    // ─── Upstream Proxy ──────────────────────────────────────────────────────

    @GetMapping("/proxy")
    public ResponseEntity<ProxyConfigDto> getProxyConfig() {
        ProxyConfigDto proxy = configService.getProxyConfig();
        if (proxy != null) proxy.setPassword(mask(proxy.getPassword()));
        return ResponseEntity.ok(proxy);
    }

    @PutMapping("/proxy")
    public ResponseEntity<GatewayConfigDto> updateProxyConfig(
            @RequestBody ProxyConfigDto proxy,
            Authentication auth) {
        return ResponseEntity.ok(configService.updateProxyConfig(proxy, actor(auth)));
    }

    // ─── HTTP Client ─────────────────────────────────────────────────────────

    @GetMapping("/http-client")
    public ResponseEntity<HttpClientConfigDto> getHttpClientConfig() {
        return ResponseEntity.ok(configService.getHttpClientConfig());
    }

    @PutMapping("/http-client")
    public ResponseEntity<GatewayConfigDto> updateHttpClientConfig(
            @RequestBody HttpClientConfigDto httpClient,
            Authentication auth) {
        return ResponseEntity.ok(configService.updateHttpClientConfig(httpClient, actor(auth)));
    }

    // ─── Global Filters ──────────────────────────────────────────────────────

    @GetMapping("/global-filters")
    public ResponseEntity<GlobalFiltersConfig> getGlobalFilters() {
        return ResponseEntity.ok(configService.getGlobalFilters());
    }

    @PutMapping("/global-filters")
    public ResponseEntity<GatewayConfigDto> updateGlobalFilters(
            @RequestBody GlobalFiltersConfig gf,
            Authentication auth) {
        return ResponseEntity.ok(configService.updateGlobalFilters(gf, actor(auth)));
    }

    // ─── Tenant Isolation ────────────────────────────────────────────────────

    @GetMapping("/tenant-isolation")
    public ResponseEntity<TenantIsolationConfig> getTenantIsolation() {
        return ResponseEntity.ok(configService.getTenantIsolation());
    }

    @PutMapping("/tenant-isolation")
    public ResponseEntity<GatewayConfigDto> updateTenantIsolation(
            @RequestBody TenantIsolationConfig ti,
            Authentication auth) {
        return ResponseEntity.ok(configService.updateTenantIsolation(ti, actor(auth)));
    }

    // ─── Helpers ─────────────────────────────────────────────────────────────

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
