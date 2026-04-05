package gr.routify.admin.gateway.controller;

import gr.routify.admin.gateway.dto.GatewayConfigDto;
import gr.routify.admin.gateway.dto.GatewayConfigDto.*;
import gr.routify.admin.gateway.service.GatewayActuatorClient;
import gr.routify.admin.gateway.service.GatewayConfigService;
import gr.routify.admin.client.CertVaultMessagingClient;
import gr.routify.common.event.QueryResponse;
import gr.routify.common.web.RoutifyHeaders;
import gr.routify.common.web.Sensitive;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
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
 *
 * <p>Authorization:
 * <ul>
 *   <li>Read (GET): any authenticated user (VIEWER and above)</li>
 *   <li>Write (PUT/POST/DELETE): TENANT_ADMIN or SUPER_ADMIN</li>
 * </ul>
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
    @PreAuthorize("hasAnyRole('SUPER_ADMIN','TENANT_ADMIN','OPERATOR','VIEWER')")
    public ResponseEntity<GatewayConfigDto> getConfig() {
        return ResponseEntity.ok(configService.getConfig());
    }

    @PutMapping("/config")
    @PreAuthorize("hasAnyRole('SUPER_ADMIN','TENANT_ADMIN')")
    public ResponseEntity<GatewayConfigDto> saveConfig(
            @RequestBody GatewayConfigDto dto,
            Authentication auth) {
        return ResponseEntity.ok(configService.saveConfig(dto, actor(auth)));
    }

    // ─── Live status ─────────────────────────────────────────────────────────

    @GetMapping("/status")
    @PreAuthorize("hasAnyRole('SUPER_ADMIN','TENANT_ADMIN','OPERATOR','VIEWER')")
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
    @PreAuthorize("hasAnyRole('SUPER_ADMIN','TENANT_ADMIN')")
    public ResponseEntity<Map<String, Object>> triggerReload(Authentication auth) {
        return ResponseEntity.ok(actuatorClient.triggerConfigReload());
    }

    @GetMapping("/metrics")
    @PreAuthorize("hasAnyRole('SUPER_ADMIN','TENANT_ADMIN','OPERATOR','VIEWER')")
    public ResponseEntity<Map<String, Object>> getMetrics() {
        return ResponseEntity.ok(actuatorClient.getGatewayMetrics());
    }

    // ─── CORS ────────────────────────────────────────────────────────────────

    @GetMapping("/cors")
    @PreAuthorize("hasAnyRole('SUPER_ADMIN','TENANT_ADMIN','OPERATOR','VIEWER')")
    public ResponseEntity<CorsConfig> getCors() {
        return ResponseEntity.ok(configService.getCors());
    }

    @PutMapping("/cors")
    @PreAuthorize("hasAnyRole('SUPER_ADMIN','TENANT_ADMIN')")
    public ResponseEntity<GatewayConfigDto> updateCors(
            @RequestBody @Valid CorsConfig cors,
            Authentication auth) {
        return ResponseEntity.ok(configService.updateCors(cors, actor(auth)));
    }

    // ─── Security Headers ────────────────────────────────────────────────────

    @GetMapping("/security-headers")
    @PreAuthorize("hasAnyRole('SUPER_ADMIN','TENANT_ADMIN','OPERATOR','VIEWER')")
    public ResponseEntity<SecurityHeadersConfig> getSecurityHeaders() {
        return ResponseEntity.ok(configService.getSecurityHeaders());
    }

    @PutMapping("/security-headers")
    @PreAuthorize("hasAnyRole('SUPER_ADMIN','TENANT_ADMIN')")
    public ResponseEntity<GatewayConfigDto> updateSecurityHeaders(
            @RequestBody SecurityHeadersConfig sh,
            Authentication auth) {
        return ResponseEntity.ok(configService.updateSecurityHeaders(sh, actor(auth)));
    }

    // ─── Rate Limit Policies ─────────────────────────────────────────────────

    @GetMapping("/rate-limit-policies")
    @PreAuthorize("hasAnyRole('SUPER_ADMIN','TENANT_ADMIN','OPERATOR','VIEWER')")
    public ResponseEntity<List<RateLimitPolicyDto>> getRateLimitPolicies() {
        return ResponseEntity.ok(configService.getRateLimitPolicies());
    }

    @PutMapping("/rate-limit-policies")
    @PreAuthorize("hasAnyRole('SUPER_ADMIN','TENANT_ADMIN')")
    public ResponseEntity<GatewayConfigDto> setRateLimitPolicies(
            @RequestBody List<RateLimitPolicyDto> policies,
            Authentication auth) {
        return ResponseEntity.ok(configService.updateRateLimitPolicies(policies, actor(auth)));
    }

    @PutMapping("/rate-limit-policies/{policyId}")
    @PreAuthorize("hasAnyRole('SUPER_ADMIN','TENANT_ADMIN')")
    public ResponseEntity<GatewayConfigDto> upsertRateLimitPolicy(
            @PathVariable String policyId,
            @RequestBody RateLimitPolicyDto policy,
            Authentication auth) {
        policy.setId(policyId);
        return ResponseEntity.ok(configService.upsertRateLimitPolicy(policy, actor(auth)));
    }

    @DeleteMapping("/rate-limit-policies/{policyId}")
    @PreAuthorize("hasAnyRole('SUPER_ADMIN','TENANT_ADMIN')")
    public ResponseEntity<Void> deleteRateLimitPolicy(
            @PathVariable String policyId,
            Authentication auth) {
        configService.deleteRateLimitPolicy(policyId, actor(auth));
        return ResponseEntity.noContent().build();
    }

    // ─── Circuit Breaker Defaults ────────────────────────────────────────────

    @GetMapping("/circuit-breaker")
    @PreAuthorize("hasAnyRole('SUPER_ADMIN','TENANT_ADMIN','OPERATOR','VIEWER')")
    public ResponseEntity<CircuitBreakerDefaultsDto> getCircuitBreakerDefaults() {
        return ResponseEntity.ok(configService.getCircuitBreakerDefaults());
    }

    @PutMapping("/circuit-breaker")
    @PreAuthorize("hasAnyRole('SUPER_ADMIN','TENANT_ADMIN')")
    public ResponseEntity<GatewayConfigDto> updateCircuitBreakerDefaults(
            @RequestBody CircuitBreakerDefaultsDto cb,
            Authentication auth) {
        return ResponseEntity.ok(configService.updateCircuitBreakerDefaults(cb, actor(auth)));
    }

    @GetMapping("/circuit-breaker/states")
    @PreAuthorize("hasAnyRole('SUPER_ADMIN','TENANT_ADMIN','OPERATOR','VIEWER')")
    public ResponseEntity<Map<String, Object>> getCircuitBreakerStates() {
        return ResponseEntity.ok(actuatorClient.getCircuitBreakerStates());
    }

    // ─── Resilience Defaults (Retry / Timeout / Bulkhead) ───────────────────

    @GetMapping("/resilience")
    @PreAuthorize("hasAnyRole('SUPER_ADMIN','TENANT_ADMIN','OPERATOR','VIEWER')")
    public ResponseEntity<ResilienceDefaultsDto> getResilienceDefaults() {
        return ResponseEntity.ok(configService.getResilienceDefaults());
    }

    @PutMapping("/resilience")
    @PreAuthorize("hasAnyRole('SUPER_ADMIN','TENANT_ADMIN')")
    public ResponseEntity<GatewayConfigDto> updateResilienceDefaults(
            @RequestBody ResilienceDefaultsDto rd,
            Authentication auth) {
        return ResponseEntity.ok(configService.updateResilienceDefaults(rd, actor(auth)));
    }

    // ─── Auth Providers ──────────────────────────────────────────────────────

    @GetMapping("/auth-providers")
    @PreAuthorize("hasAnyRole('SUPER_ADMIN','TENANT_ADMIN','OPERATOR','VIEWER')")
    public ResponseEntity<List<AuthProviderDto>> getAuthProviders() {
        List<AuthProviderDto> providers = configService.getAuthProviders();
        providers.forEach(Sensitive::maskFields);
        return ResponseEntity.ok(providers);
    }

    @PutMapping("/auth-providers/{providerId}")
    @PreAuthorize("hasAnyRole('SUPER_ADMIN','TENANT_ADMIN')")
    public ResponseEntity<GatewayConfigDto> upsertAuthProvider(
            @PathVariable String providerId,
            @RequestBody AuthProviderDto provider,
            Authentication auth) {
        provider.setId(providerId);
        return ResponseEntity.ok(configService.upsertAuthProvider(provider, actor(auth)));
    }

    @DeleteMapping("/auth-providers/{providerId}")
    @PreAuthorize("hasAnyRole('SUPER_ADMIN','TENANT_ADMIN')")
    public ResponseEntity<Void> deleteAuthProvider(
            @PathVariable String providerId,
            Authentication auth) {
        configService.deleteAuthProvider(providerId, actor(auth));
        return ResponseEntity.noContent().build();
    }

    // ─── TLS / Certificate Vault ─────────────────────────────────────────────
    //
    // All certificate management is now handled exclusively by routify-cert-vault.
    // The gateway loads certs at startup via CertificateVaultLoader and reacts to
    // CERT_GROUP_EVENTS Kafka events for zero-downtime rotation.
    //
    // The deprecated PUT /tls endpoint (and its fileSources/directorySources/
    // expiryWarning/fileWatchInterval fields) has been removed.
    // Use the Cert Vault API (/api/v1/admin/cert-groups, /api/v1/admin/certs) instead.

    @GetMapping("/tls")
    @PreAuthorize("hasAnyRole('SUPER_ADMIN','TENANT_ADMIN','OPERATOR','VIEWER')")
    public ResponseEntity<TlsConfigDto> getTlsConfig() {
        return ResponseEntity.ok(configService.getTlsConfig());
    }

    @GetMapping("/tls/certificates")
    @PreAuthorize("hasAnyRole('SUPER_ADMIN','TENANT_ADMIN','OPERATOR','VIEWER')")
    public ResponseEntity<Map<String, Object>> getLiveCertificates() {
        return ResponseEntity.ok(actuatorClient.getCertificates());
    }

    /**
     * List active vault certificates that are already mapped to a gateway TLS logical ID.
     * Used by the admin dashboard TLS tab to show the vault ↔ gateway connection status.
     * Routes through cert-vault's {@code certs.gateway.snapshot} RabbitMQ queue.
     */
    @GetMapping("/tls/vault-certs")
    @PreAuthorize("hasAnyRole('SUPER_ADMIN','TENANT_ADMIN','OPERATOR','VIEWER')")
    public ResponseEntity<QueryResponse.CertsList> getVaultCertificates(
            @RequestHeader(value = RoutifyHeaders.TENANT_ID, required = false) java.util.UUID tenantId) {
        return ResponseEntity.ok(certVaultClient.listGatewayMappedCertificates(tenantId));
    }

    // ─── Upstream Proxy ──────────────────────────────────────────────────────

    @GetMapping("/proxy")
    @PreAuthorize("hasAnyRole('SUPER_ADMIN','TENANT_ADMIN','OPERATOR','VIEWER')")
    public ResponseEntity<ProxyConfigDto> getProxyConfig() {
        ProxyConfigDto proxy = configService.getProxyConfig();
        Sensitive.maskFields(proxy);
        return ResponseEntity.ok(proxy);
    }

    @PutMapping("/proxy")
    @PreAuthorize("hasAnyRole('SUPER_ADMIN','TENANT_ADMIN')")
    public ResponseEntity<GatewayConfigDto> updateProxyConfig(
            @RequestBody ProxyConfigDto proxy,
            Authentication auth) {
        return ResponseEntity.ok(configService.updateProxyConfig(proxy, actor(auth)));
    }

    // ─── HTTP Client ─────────────────────────────────────────────────────────

    @GetMapping("/http-client")
    @PreAuthorize("hasAnyRole('SUPER_ADMIN','TENANT_ADMIN','OPERATOR','VIEWER')")
    public ResponseEntity<HttpClientConfigDto> getHttpClientConfig() {
        return ResponseEntity.ok(configService.getHttpClientConfig());
    }

    @PutMapping("/http-client")
    @PreAuthorize("hasAnyRole('SUPER_ADMIN','TENANT_ADMIN')")
    public ResponseEntity<GatewayConfigDto> updateHttpClientConfig(
            @RequestBody HttpClientConfigDto httpClient,
            Authentication auth) {
        return ResponseEntity.ok(configService.updateHttpClientConfig(httpClient, actor(auth)));
    }

    // ─── Global Filters ──────────────────────────────────────────────────────

    @GetMapping("/global-filters")
    @PreAuthorize("hasAnyRole('SUPER_ADMIN','TENANT_ADMIN','OPERATOR','VIEWER')")
    public ResponseEntity<GlobalFiltersConfig> getGlobalFilters() {
        return ResponseEntity.ok(configService.getGlobalFilters());
    }

    @PutMapping("/global-filters")
    @PreAuthorize("hasAnyRole('SUPER_ADMIN','TENANT_ADMIN')")
    public ResponseEntity<GatewayConfigDto> updateGlobalFilters(
            @RequestBody GlobalFiltersConfig gf,
            Authentication auth) {
        return ResponseEntity.ok(configService.updateGlobalFilters(gf, actor(auth)));
    }

    // ─── Tenant Isolation ────────────────────────────────────────────────────

    @GetMapping("/tenant-isolation")
    @PreAuthorize("hasAnyRole('SUPER_ADMIN','TENANT_ADMIN','OPERATOR','VIEWER')")
    public ResponseEntity<TenantIsolationConfig> getTenantIsolation() {
        return ResponseEntity.ok(configService.getTenantIsolation());
    }

    @PutMapping("/tenant-isolation")
    @PreAuthorize("hasAnyRole('SUPER_ADMIN','TENANT_ADMIN')")
    public ResponseEntity<GatewayConfigDto> updateTenantIsolation(
            @RequestBody TenantIsolationConfig ti,
            Authentication auth) {
        return ResponseEntity.ok(configService.updateTenantIsolation(ti, actor(auth)));
    }

    // ─── Global Filter Entries ────────────────────────────────────────────────

    @GetMapping("/global-filter-entries")
    @PreAuthorize("hasAnyRole('SUPER_ADMIN','TENANT_ADMIN','OPERATOR','VIEWER')")
    public ResponseEntity<List<GlobalFilterEntryDto>> getGlobalFilterEntries() {
        return ResponseEntity.ok(configService.getGlobalFilterEntries());
    }

    @PutMapping("/global-filter-entries")
    @PreAuthorize("hasAnyRole('SUPER_ADMIN','TENANT_ADMIN')")
    public ResponseEntity<GatewayConfigDto> updateGlobalFilterEntries(
            @RequestBody List<GlobalFilterEntryDto> entries,
            Authentication auth) {
        return ResponseEntity.ok(configService.updateGlobalFilterEntries(entries, actor(auth)));
    }

    // ─── Helpers ─────────────────────────────────────────────────────────────

    private String actor(Authentication auth) {
        return RoutifyHeaders.resolveActor(null, auth != null ? auth.getName() : null);
    }
}
