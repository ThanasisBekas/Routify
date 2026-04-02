import apiClient from './client'
import type {
  GatewayConfig,
  GatewayCorsConfig,
  GatewaySecurityHeadersConfig,
  GatewayRateLimitPolicy,
  GatewayCircuitBreakerDefaults,
  GatewayResilienceDefaults,
  GatewayAuthProvider,
  GatewayTlsConfig,
  GatewayProxyConfig,
  GatewayHttpClientConfig,
  GatewayTenantIsolationConfig,
  GatewayLiveStatus,
} from '../types'

const BASE = '/api/v1/admin/gateway'

export const gatewayApi = {
  // ─── Full config ─────────────────────────────────────────────────────────
  getConfig: () =>
    apiClient.get<GatewayConfig>(`${BASE}/config`).then(r => r.data),

  saveConfig: (config: GatewayConfig) =>
    apiClient.put<GatewayConfig>(`${BASE}/config`, config).then(r => r.data),

  // ─── Live status & control ────────────────────────────────────────────────
  getStatus: () =>
    apiClient.get<GatewayLiveStatus>(`${BASE}/status`).then(r => r.data),

  triggerReload: () =>
    apiClient.post<Record<string, unknown>>(`${BASE}/reload`).then(r => r.data),

  getMetrics: () =>
    apiClient.get<Record<string, unknown>>(`${BASE}/metrics`).then(r => r.data),

  // ─── CORS ─────────────────────────────────────────────────────────────────
  getCors: () =>
    apiClient.get<GatewayCorsConfig>(`${BASE}/cors`).then(r => r.data),

  updateCors: (cors: GatewayCorsConfig) =>
    apiClient.put<GatewayConfig>(`${BASE}/cors`, cors).then(r => r.data),

  // ─── Security Headers ─────────────────────────────────────────────────────
  getSecurityHeaders: () =>
    apiClient.get<GatewaySecurityHeadersConfig>(`${BASE}/security-headers`).then(r => r.data),

  updateSecurityHeaders: (sh: GatewaySecurityHeadersConfig) =>
    apiClient.put<GatewayConfig>(`${BASE}/security-headers`, sh).then(r => r.data),

  // ─── Rate Limit Policies ──────────────────────────────────────────────────
  getRateLimitPolicies: () =>
    apiClient.get<GatewayRateLimitPolicy[]>(`${BASE}/rate-limit-policies`).then(r => r.data),

  setRateLimitPolicies: (policies: GatewayRateLimitPolicy[]) =>
    apiClient.put<GatewayConfig>(`${BASE}/rate-limit-policies`, policies).then(r => r.data),

  upsertRateLimitPolicy: (policy: GatewayRateLimitPolicy) =>
    apiClient.put<GatewayConfig>(`${BASE}/rate-limit-policies/${policy.id}`, policy).then(r => r.data),

  deleteRateLimitPolicy: (policyId: string) =>
    apiClient.delete(`${BASE}/rate-limit-policies/${policyId}`).then(r => r.data),

  // ─── Circuit Breaker ──────────────────────────────────────────────────────
  getCircuitBreakerDefaults: () =>
    apiClient.get<GatewayCircuitBreakerDefaults>(`${BASE}/circuit-breaker`).then(r => r.data),

  updateCircuitBreakerDefaults: (cb: GatewayCircuitBreakerDefaults) =>
    apiClient.put<GatewayConfig>(`${BASE}/circuit-breaker`, cb).then(r => r.data),

  getCircuitBreakerStates: () =>
    apiClient.get<Record<string, unknown>>(`${BASE}/circuit-breaker/states`).then(r => r.data),

  // ─── Resilience ───────────────────────────────────────────────────────────
  getResilienceDefaults: () =>
    apiClient.get<GatewayResilienceDefaults>(`${BASE}/resilience`).then(r => r.data),

  updateResilienceDefaults: (rd: GatewayResilienceDefaults) =>
    apiClient.put<GatewayConfig>(`${BASE}/resilience`, rd).then(r => r.data),

  // ─── Auth Providers ───────────────────────────────────────────────────────
  getAuthProviders: () =>
    apiClient.get<GatewayAuthProvider[]>(`${BASE}/auth-providers`).then(r => r.data),

  upsertAuthProvider: (provider: GatewayAuthProvider) =>
    apiClient.put<GatewayConfig>(`${BASE}/auth-providers/${provider.id}`, provider).then(r => r.data),

  deleteAuthProvider: (providerId: string) =>
    apiClient.delete(`${BASE}/auth-providers/${providerId}`).then(r => r.data),


  // ─── TLS / Certificates ───────────────────────────────────────────────────
  getTlsConfig: () =>
    apiClient.get<GatewayTlsConfig>(`${BASE}/tls`).then(r => r.data),

  updateTlsConfig: (tls: GatewayTlsConfig) =>
    apiClient.put<GatewayConfig>(`${BASE}/tls`, tls).then(r => r.data),

  getLiveCertificates: () =>
    apiClient.get<Record<string, unknown>>(`${BASE}/tls/certificates`).then(r => r.data),

  /** List active vault certificates for the gateway TLS mapping picker */
  getVaultCertificates: (tenantId: string) =>
    apiClient
      .get<import('../types').CertificateDto[]>(`${BASE}/tls/vault-certs`, {
        headers: { 'X-Tenant-Id': tenantId },
      })
      .then(r => r.data),

  /**
   * List active certificate groups for the filter config picker.
   * Groups are what the gateway registry and cert-vault filters bind to via their stable logicalId.
   */
  getVaultCertGroups: (tenantId: string) =>
    apiClient
      .get<import('../types').Page<import('../types').CertGroupDto>>(
        '/api/v1/admin/cert-groups',
        {
          params: { status: 'ACTIVE', page: 0, size: 200, sortBy: 'logicalId', sortDir: 'ASC' },
          headers: { 'X-Tenant-Id': tenantId },
        },
      )
      .then(r => r.data),

  // ─── Proxy ────────────────────────────────────────────────────────────────
  getProxyConfig: () =>
    apiClient.get<GatewayProxyConfig>(`${BASE}/proxy`).then(r => r.data),

  updateProxyConfig: (proxy: GatewayProxyConfig) =>
    apiClient.put<GatewayConfig>(`${BASE}/proxy`, proxy).then(r => r.data),

  // ─── HTTP Client ──────────────────────────────────────────────────────────
  getHttpClientConfig: () =>
    apiClient.get<GatewayHttpClientConfig>(`${BASE}/http-client`).then(r => r.data),

  updateHttpClientConfig: (httpClient: GatewayHttpClientConfig) =>
    apiClient.put<GatewayConfig>(`${BASE}/http-client`, httpClient).then(r => r.data),


  // ─── Tenant Isolation ─────────────────────────────────────────────────────
  getTenantIsolation: () =>
    apiClient.get<GatewayTenantIsolationConfig>(`${BASE}/tenant-isolation`).then(r => r.data),

  updateTenantIsolation: (ti: GatewayTenantIsolationConfig) =>
    apiClient.put<GatewayConfig>(`${BASE}/tenant-isolation`, ti).then(r => r.data),
}

