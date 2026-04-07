import apiClient from './client'
import type {
  GatewayConfig,
  GatewayCorsConfig,
  GatewaySecurityHeadersConfig,
  GatewayRateLimitPolicy,
  GatewayCircuitBreakerDefaults,
  GatewayResilienceDefaults,
  GatewayAuthProvider,
  GatewayProxyConfig,
  GatewayHttpClientConfig,
  GatewayTenantIsolationConfig,
  GatewayLiveStatus,
  GlobalFilterEntry,
  RouteHealthResponse,
  HealthTimeWindow,
  SloStatus,
  RouteSloConfig,
  FleetStatusResponse,
} from '../types'

const BASE = '/api/v1/admin/gateway'

export const gatewayApi = {
  // ─── Full config ─────────────────────────────────────────────────────────
  getConfig: () => apiClient.get<GatewayConfig>(`${BASE}/config`).then((r) => r.data),

  saveConfig: (config: GatewayConfig) => apiClient.put<GatewayConfig>(`${BASE}/config`, config).then((r) => r.data),

  // ─── Live status & control ────────────────────────────────────────────────
  getStatus: () => apiClient.get<GatewayLiveStatus>(`${BASE}/status`).then((r) => r.data),

  triggerReload: () => apiClient.post<Record<string, unknown>>(`${BASE}/reload`).then((r) => r.data),

  getMetrics: () => apiClient.get<Record<string, unknown>>(`${BASE}/metrics`).then((r) => r.data),

  // ─── CORS ─────────────────────────────────────────────────────────────────
  getCors: () => apiClient.get<GatewayCorsConfig>(`${BASE}/cors`).then((r) => r.data),

  updateCors: (cors: GatewayCorsConfig) => apiClient.put<GatewayConfig>(`${BASE}/cors`, cors).then((r) => r.data),

  // ─── Security Headers ─────────────────────────────────────────────────────
  getSecurityHeaders: () => apiClient.get<GatewaySecurityHeadersConfig>(`${BASE}/security-headers`).then((r) => r.data),

  updateSecurityHeaders: (sh: GatewaySecurityHeadersConfig) =>
    apiClient.put<GatewayConfig>(`${BASE}/security-headers`, sh).then((r) => r.data),

  // ─── Rate Limit Policies ──────────────────────────────────────────────────
  getRateLimitPolicies: () =>
    apiClient.get<GatewayRateLimitPolicy[]>(`${BASE}/rate-limit-policies`).then((r) => r.data),

  setRateLimitPolicies: (policies: GatewayRateLimitPolicy[]) =>
    apiClient.put<GatewayConfig>(`${BASE}/rate-limit-policies`, policies).then((r) => r.data),

  upsertRateLimitPolicy: (policy: GatewayRateLimitPolicy) =>
    apiClient.put<GatewayConfig>(`${BASE}/rate-limit-policies/${policy.id}`, policy).then((r) => r.data),

  deleteRateLimitPolicy: (policyId: string) =>
    apiClient.delete(`${BASE}/rate-limit-policies/${policyId}`).then((r) => r.data),

  // ─── Circuit Breaker ──────────────────────────────────────────────────────
  getCircuitBreakerDefaults: () =>
    apiClient.get<GatewayCircuitBreakerDefaults>(`${BASE}/circuit-breaker`).then((r) => r.data),

  updateCircuitBreakerDefaults: (cb: GatewayCircuitBreakerDefaults) =>
    apiClient.put<GatewayConfig>(`${BASE}/circuit-breaker`, cb).then((r) => r.data),

  getCircuitBreakerStates: () =>
    apiClient.get<Record<string, unknown>>(`${BASE}/circuit-breaker/states`).then((r) => r.data),

  // ─── Resilience ───────────────────────────────────────────────────────────
  getResilienceDefaults: () => apiClient.get<GatewayResilienceDefaults>(`${BASE}/resilience`).then((r) => r.data),

  updateResilienceDefaults: (rd: GatewayResilienceDefaults) =>
    apiClient.put<GatewayConfig>(`${BASE}/resilience`, rd).then((r) => r.data),

  // ─── Auth Providers ───────────────────────────────────────────────────────
  getAuthProviders: () => apiClient.get<GatewayAuthProvider[]>(`${BASE}/auth-providers`).then((r) => r.data),

  upsertAuthProvider: (provider: GatewayAuthProvider) =>
    apiClient.put<GatewayConfig>(`${BASE}/auth-providers/${provider.id}`, provider).then((r) => r.data),

  deleteAuthProvider: (providerId: string) =>
    apiClient.delete(`${BASE}/auth-providers/${providerId}`).then((r) => r.data),

  // ─── Proxy ────────────────────────────────────────────────────────────────
  getProxyConfig: () => apiClient.get<GatewayProxyConfig>(`${BASE}/proxy`).then((r) => r.data),

  updateProxyConfig: (proxy: GatewayProxyConfig) =>
    apiClient.put<GatewayConfig>(`${BASE}/proxy`, proxy).then((r) => r.data),

  // ─── HTTP Client ──────────────────────────────────────────────────────────
  getHttpClientConfig: () => apiClient.get<GatewayHttpClientConfig>(`${BASE}/http-client`).then((r) => r.data),

  updateHttpClientConfig: (httpClient: GatewayHttpClientConfig) =>
    apiClient.put<GatewayConfig>(`${BASE}/http-client`, httpClient).then((r) => r.data),

  // ─── Tenant Isolation ─────────────────────────────────────────────────────
  getTenantIsolation: () => apiClient.get<GatewayTenantIsolationConfig>(`${BASE}/tenant-isolation`).then((r) => r.data),

  updateTenantIsolation: (ti: GatewayTenantIsolationConfig) =>
    apiClient.put<GatewayConfig>(`${BASE}/tenant-isolation`, ti).then((r) => r.data),

  // ─── Global Filter Entries ─────────────────────────────────────────────
  getGlobalFilterEntries: () => apiClient.get<GlobalFilterEntry[]>(`${BASE}/global-filter-entries`).then((r) => r.data),

  updateGlobalFilterEntries: (entries: GlobalFilterEntry[]) =>
    apiClient.put<GatewayConfig>(`${BASE}/global-filter-entries`, entries).then((r) => r.data),

  // ─── Gateway Health Dashboard v2 ─────────────────────────────────────────
  getRouteHealth: (window: HealthTimeWindow = '24h') =>
    apiClient.get<RouteHealthResponse>(`/api/v1/admin/dashboard/route-health`, { params: { window } }).then((r) => r.data),

  getRouteSloStatus: (routeId: string) =>
    apiClient.get<SloStatus>(`/api/v1/admin/routes/${routeId}/slo-status`).then((r) => r.data),

  saveRouteSlo: (routeId: string, slo: RouteSloConfig) =>
    apiClient.put(`/api/v1/admin/routes/${routeId}/slo`, slo).then((r) => r.data),

  // ─── Multi-Gateway Fleet Status ─────────────────────────────────────────
  getFleetStatus: () =>
    apiClient.get<FleetStatusResponse>('/api/v1/admin/gateway/fleet').then((r) => r.data),
}
