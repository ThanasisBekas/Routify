import { http, HttpResponse, delay } from 'msw'
import { gatewayConfig, gatewayLiveStatus, routes } from '../db'
import type {
  GatewayCorsConfig, GatewaySecurityHeadersConfig,
  GatewayRateLimitPolicy, GatewayCircuitBreakerDefaults,
  GatewayResilienceDefaults, GatewayAuthProvider,
  GatewayProxyConfig, GatewayHttpClientConfig,
  GatewayTenantIsolationConfig,
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
    const body = await request.json() as typeof gatewayConfig
    Object.assign(gatewayConfig, { ...body, updatedAt: new Date().toISOString(), updatedBy: 'admin' })
    return HttpResponse.json(gatewayConfig)
  }),

  // ─── Live status ─────────────────────────────────────────────────────────────
  http.get(`${BASE}/status`, async () => {
    await delay(150)
    // Reflect current active route count from db
    const activeCount = Array.from(routes.values()).filter(r => r.status === 'ACTIVE').length
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
      'gateway.requests.total':    { value: 142350 },
      'gateway.requests.errors':   { value: 312 },
      'gateway.requests.latency':  { p50: 45, p95: 210, p99: 580 },
      'gateway.routes.active':     { value: Array.from(routes.values()).filter(r => r.status === 'ACTIVE').length },
    })
  }),

  // ─── CORS ─────────────────────────────────────────────────────────────────────
  http.get(`${BASE}/cors`, async () => {
    await delay(150)
    return HttpResponse.json(gatewayConfig.cors)
  }),
  http.put(`${BASE}/cors`, async ({ request }) => {
    await delay(350)
    const body = await request.json() as GatewayCorsConfig
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
    const body = await request.json() as GatewaySecurityHeadersConfig
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
    const body = await request.json() as GatewayRateLimitPolicy[]
    gatewayConfig.rateLimitPolicies = body
    gatewayConfig.updatedAt = new Date().toISOString()
    return HttpResponse.json(gatewayConfig)
  }),
  http.put(`${BASE}/rate-limit-policies/:policyId`, async ({ params, request }) => {
    await delay(350)
    const body = await request.json() as GatewayRateLimitPolicy
    const idx  = gatewayConfig.rateLimitPolicies.findIndex(p => p.id === params.policyId)
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
    gatewayConfig.rateLimitPolicies = gatewayConfig.rateLimitPolicies.filter(p => p.id !== params.policyId)
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
    const body = await request.json() as GatewayCircuitBreakerDefaults
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
    const body = await request.json() as GatewayResilienceDefaults
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
    const body = await request.json() as GatewayAuthProvider
    const idx  = gatewayConfig.authProviders.findIndex(p => p.id === params.providerId)
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
    gatewayConfig.authProviders = gatewayConfig.authProviders.filter(p => p.id !== params.providerId)
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
    const body = await request.json() as GatewayProxyConfig
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
    const body = await request.json() as GatewayHttpClientConfig
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
    const body = await request.json() as GatewayTenantIsolationConfig
    gatewayConfig.tenantIsolation = body
    gatewayConfig.updatedAt = new Date().toISOString()
    return HttpResponse.json(gatewayConfig)
  }),
]

