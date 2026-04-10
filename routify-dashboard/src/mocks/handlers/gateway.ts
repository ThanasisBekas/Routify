import { http, HttpResponse, delay } from 'msw'
import { gatewayConfig, gatewayLiveStatus, routes, filters } from '../db'
import type {
  GatewayCorsConfig,
  GatewaySecurityHeadersConfig,
  GatewayRateLimitPolicy,
  GatewayCircuitBreakerDefaults,
  GatewayResilienceDefaults,
  GatewayAuthProvider,
  GatewayDownstreamCredential,
  GatewayProxyConfig,
  GatewayHttpClientConfig,
  GatewayTenantIsolationConfig,
  GlobalFilterEntry,
} from '../../types'

const BASE = '/api/v1/admin/gateway'

export const gatewayHandlers = [
  // ─── Full config ─────────────────────────────────────────────────────────────
  http.get(`${BASE}/config`, async () => {
    await delay(200)
    return HttpResponse.json(gatewayConfig)
  }),
  http.put(`${BASE}/config`, async ({ request }) => {
    await delay(400)
    const body = (await request.json()) as typeof gatewayConfig
    Object.assign(gatewayConfig, { ...body, updatedAt: new Date().toISOString(), updatedBy: 'admin' })
    return HttpResponse.json(gatewayConfig)
  }),

  // ─── Live status ─────────────────────────────────────────────────────────────
  http.get(`${BASE}/status`, async () => {
    await delay(150)
    // Reflect current active route count from db
    const activeCount = Array.from(routes.values()).filter((r) => r.status === 'ACTIVE').length
    return HttpResponse.json({
      ...gatewayLiveStatus,
      routes: { ...gatewayLiveStatus.routes, count: activeCount },
    })
  }),

  // ─── Reload ──────────────────────────────────────────────────────────────────
  http.post(`${BASE}/reload`, async () => {
    await delay(800)
    return HttpResponse.json({ triggered: true, message: 'Gateway reload triggered successfully.' })
  }),

  // ─── Metrics ─────────────────────────────────────────────────────────────────
  http.get(`${BASE}/metrics`, async () => {
    await delay(150)
    return HttpResponse.json({
      'gateway.requests.total': { value: 142350 },
      'gateway.requests.errors': { value: 312 },
      'gateway.requests.latency': { p50: 45, p95: 210, p99: 580 },
      'gateway.routes.active': { value: Array.from(routes.values()).filter((r) => r.status === 'ACTIVE').length },
    })
  }),

  // ─── CORS ─────────────────────────────────────────────────────────────────────
  http.get(`${BASE}/cors`, async () => {
    await delay(150)
    return HttpResponse.json(gatewayConfig.cors)
  }),
  http.put(`${BASE}/cors`, async ({ request }) => {
    await delay(350)
    const body = (await request.json()) as GatewayCorsConfig
    gatewayConfig.cors = body
    gatewayConfig.updatedAt = new Date().toISOString()
    return HttpResponse.json(gatewayConfig)
  }),

  // ─── Security Headers ─────────────────────────────────────────────────────────
  http.get(`${BASE}/security-headers`, async () => {
    await delay(150)
    return HttpResponse.json(gatewayConfig.securityHeaders)
  }),
  http.put(`${BASE}/security-headers`, async ({ request }) => {
    await delay(350)
    const body = (await request.json()) as GatewaySecurityHeadersConfig
    gatewayConfig.securityHeaders = body
    gatewayConfig.updatedAt = new Date().toISOString()
    return HttpResponse.json(gatewayConfig)
  }),

  // ─── Rate Limit Policies ──────────────────────────────────────────────────────
  http.get(`${BASE}/rate-limit-policies`, async () => {
    await delay(150)
    return HttpResponse.json(gatewayConfig.rateLimitPolicies)
  }),
  http.put(`${BASE}/rate-limit-policies`, async ({ request }) => {
    await delay(350)
    const body = (await request.json()) as GatewayRateLimitPolicy[]
    gatewayConfig.rateLimitPolicies = body
    gatewayConfig.updatedAt = new Date().toISOString()
    return HttpResponse.json(gatewayConfig)
  }),
  http.put(`${BASE}/rate-limit-policies/:policyId`, async ({ params, request }) => {
    await delay(350)
    const body = (await request.json()) as GatewayRateLimitPolicy
    const idx = gatewayConfig.rateLimitPolicies.findIndex((p) => p.id === params.policyId)
    if (idx >= 0) {
      gatewayConfig.rateLimitPolicies[idx] = body
    } else {
      gatewayConfig.rateLimitPolicies.push(body)
    }
    gatewayConfig.updatedAt = new Date().toISOString()
    return HttpResponse.json(gatewayConfig)
  }),
  http.delete(`${BASE}/rate-limit-policies/:policyId`, async ({ params }) => {
    await delay(300)
    gatewayConfig.rateLimitPolicies = gatewayConfig.rateLimitPolicies.filter((p) => p.id !== params.policyId)
    gatewayConfig.updatedAt = new Date().toISOString()
    return new HttpResponse(null, { status: 204 })
  }),

  // ─── Circuit Breaker ──────────────────────────────────────────────────────────
  http.get(`${BASE}/circuit-breaker`, async () => {
    await delay(150)
    return HttpResponse.json(gatewayConfig.circuitBreakerDefaults)
  }),
  http.put(`${BASE}/circuit-breaker`, async ({ request }) => {
    await delay(350)
    const body = (await request.json()) as GatewayCircuitBreakerDefaults
    gatewayConfig.circuitBreakerDefaults = body
    gatewayConfig.updatedAt = new Date().toISOString()
    return HttpResponse.json(gatewayConfig)
  }),
  http.get(`${BASE}/circuit-breaker/states`, async () => {
    await delay(150)
    return HttpResponse.json(gatewayLiveStatus.circuitBreakers)
  }),

  // ─── Resilience ───────────────────────────────────────────────────────────────
  http.get(`${BASE}/resilience`, async () => {
    await delay(150)
    return HttpResponse.json(gatewayConfig.resilienceDefaults)
  }),
  http.put(`${BASE}/resilience`, async ({ request }) => {
    await delay(350)
    const body = (await request.json()) as GatewayResilienceDefaults
    gatewayConfig.resilienceDefaults = body
    gatewayConfig.updatedAt = new Date().toISOString()
    return HttpResponse.json(gatewayConfig)
  }),

  // ─── Auth Providers ───────────────────────────────────────────────────────────
  http.get(`${BASE}/auth-providers`, async () => {
    await delay(150)
    return HttpResponse.json(gatewayConfig.authProviders)
  }),
  http.put(`${BASE}/auth-providers/:providerId`, async ({ params, request }) => {
    await delay(350)
    const body = (await request.json()) as GatewayAuthProvider
    const idx = gatewayConfig.authProviders.findIndex((p) => p.id === params.providerId)
    if (idx >= 0) {
      gatewayConfig.authProviders[idx] = body
    } else {
      gatewayConfig.authProviders.push(body)
    }
    gatewayConfig.updatedAt = new Date().toISOString()
    return HttpResponse.json(gatewayConfig)
  }),
  http.delete(`${BASE}/auth-providers/:providerId`, async ({ params }) => {
    await delay(300)
    gatewayConfig.authProviders = gatewayConfig.authProviders.filter((p) => p.id !== params.providerId)
    gatewayConfig.updatedAt = new Date().toISOString()
    return new HttpResponse(null, { status: 204 })
  }),

  // ─── Downstream Credentials ────────────────────────────────────────────────
  http.get(`${BASE}/downstream-credentials`, async () => {
    await delay(150)
    return HttpResponse.json(gatewayConfig.downstreamCredentials ?? [])
  }),
  http.put(`${BASE}/downstream-credentials/:credentialId`, async ({ params, request }) => {
    await delay(350)
    const body = (await request.json()) as GatewayDownstreamCredential
    const creds = gatewayConfig.downstreamCredentials ?? []
    const idx = creds.findIndex((c) => c.id === params.credentialId)
    if (idx >= 0) {
      creds[idx] = body
    } else {
      creds.push(body)
    }
    gatewayConfig.downstreamCredentials = creds
    gatewayConfig.updatedAt = new Date().toISOString()
    return HttpResponse.json(gatewayConfig)
  }),
  http.delete(`${BASE}/downstream-credentials/:credentialId`, async ({ params }) => {
    await delay(300)
    gatewayConfig.downstreamCredentials = (gatewayConfig.downstreamCredentials ?? []).filter(
      (c) => c.id !== params.credentialId,
    )
    gatewayConfig.updatedAt = new Date().toISOString()
    return new HttpResponse(null, { status: 204 })
  }),

  // ─── Proxy ────────────────────────────────────────────────────────────────────
  http.get(`${BASE}/proxy`, async () => {
    await delay(150)
    return HttpResponse.json(gatewayConfig.proxyConfig)
  }),
  http.put(`${BASE}/proxy`, async ({ request }) => {
    await delay(350)
    const body = (await request.json()) as GatewayProxyConfig
    gatewayConfig.proxyConfig = body
    gatewayConfig.updatedAt = new Date().toISOString()
    return HttpResponse.json(gatewayConfig)
  }),

  // ─── HTTP Client ──────────────────────────────────────────────────────────────
  http.get(`${BASE}/http-client`, async () => {
    await delay(150)
    return HttpResponse.json(gatewayConfig.httpClientConfig)
  }),
  http.put(`${BASE}/http-client`, async ({ request }) => {
    await delay(350)
    const body = (await request.json()) as GatewayHttpClientConfig
    gatewayConfig.httpClientConfig = body
    gatewayConfig.updatedAt = new Date().toISOString()
    return HttpResponse.json(gatewayConfig)
  }),

  // ─── Tenant Isolation ─────────────────────────────────────────────────────────
  http.get(`${BASE}/tenant-isolation`, async () => {
    await delay(150)
    return HttpResponse.json(gatewayConfig.tenantIsolation)
  }),
  http.put(`${BASE}/tenant-isolation`, async ({ request }) => {
    await delay(350)
    const body = (await request.json()) as GatewayTenantIsolationConfig
    gatewayConfig.tenantIsolation = body
    gatewayConfig.updatedAt = new Date().toISOString()
    return HttpResponse.json(gatewayConfig)
  }),

  // ─── Global Filter Entries ─────────────────────────────────────────────────
  http.get(`${BASE}/global-filter-entries`, async () => {
    await delay(150)
    return HttpResponse.json(gatewayConfig.globalFilterEntries ?? [])
  }),
  http.put(`${BASE}/global-filter-entries`, async ({ request }) => {
    await delay(350)
    const body = (await request.json()) as GlobalFilterEntry[]
    // Enrich entries with config from the filters DB (simulates backend enrichment)
    gatewayConfig.globalFilterEntries = body.map((entry) => {
      const filter = filters.get(entry.filterId)
      if (filter) {
        return {
          ...entry,
          config: filter.config ?? entry.config ?? {},
          gatewayConfigRef: filter.gatewayConfigRef ?? entry.gatewayConfigRef,
        }
      }
      return entry
    })
    gatewayConfig.updatedAt = new Date().toISOString()
    return HttpResponse.json(gatewayConfig)
  }),

  // ─── Fleet Status (Multi-Gateway Cluster Awareness) ─────────────────────────
  http.get(`${BASE}/fleet`, async () => {
    await delay(200)
    const now = new Date()
    const startedAt1 = new Date(now.getTime() - 3.9 * 60 * 60 * 1000).toISOString()
    const startedAt2 = new Date(now.getTime() - 12.5 * 60 * 60 * 1000).toISOString()
    const startedAt3 = new Date(now.getTime() - 1.2 * 60 * 60 * 1000).toISOString()

    return HttpResponse.json({
      globalConfigVersion: 47,
      instanceCount: 3,
      healthyCount: 2,
      staleCount: 1,
      instances: [
        {
          instanceId: 'gateway-pod-abc123:8080:f7a2',
          hostname: 'gateway-pod-abc123',
          configVersion: 47,
          routeCount: 125,
          filterCount: 89,
          status: 'HEALTHY',
          startedAt: startedAt1,
          lastReloadAt: new Date(now.getTime() - 5 * 60 * 1000).toISOString(),
          lastHeartbeatAt: new Date(now.getTime() - 3 * 1000).toISOString(),
          uptimeHours: 3.9,
        },
        {
          instanceId: 'gateway-pod-def456:8080:b3c1',
          hostname: 'gateway-pod-def456',
          configVersion: 45,
          routeCount: 123,
          filterCount: 87,
          status: 'STALE',
          startedAt: startedAt2,
          lastReloadAt: new Date(now.getTime() - 25 * 60 * 1000).toISOString(),
          lastHeartbeatAt: new Date(now.getTime() - 8 * 1000).toISOString(),
          uptimeHours: 12.5,
        },
        {
          instanceId: 'gateway-pod-ghi789:8080:e9d4',
          hostname: 'gateway-pod-ghi789',
          configVersion: 47,
          routeCount: 125,
          filterCount: 89,
          status: 'HEALTHY',
          startedAt: startedAt3,
          lastReloadAt: new Date(now.getTime() - 5 * 60 * 1000).toISOString(),
          lastHeartbeatAt: new Date(now.getTime() - 2 * 1000).toISOString(),
          uptimeHours: 1.2,
        },
      ],
    })
  }),
]
