/**
 * MSW v2 request handlers — one file per API domain.
 * All handlers use the in-memory db.ts store so mutations are reflected
 * in subsequent reads within the same browser session.
 */

import { http, HttpResponse, delay } from 'msw'
import {
  routes, filters, users, auditEvents, requestLogs, failedRequests, tenants,
  buildPage, toRouteSummary, toFilterSummary,
  nextId, now, gatewayConfig,
} from './db'
import type {
  RouteDto, FilterDefinitionDto, UserDto, TenantDto,
  CreateRouteRequest, UpdateRouteRequest,
  CreateFilterRequest, UpdateFilterRequest,
  CreateUserRequest, GatewayConfig,
  GatewayCorsConfig, GatewaySecurityHeadersConfig,
  GatewayRateLimitPolicy, GatewayCircuitBreakerDefaults,
  GatewayResilienceDefaults, GatewayAuthProvider,
  GatewayTlsConfig, GatewayProxyConfig, GatewayHttpClientConfig,
  GatewayTenantIsolationConfig,
  GatewayCertificateSource, CertGroupDto, CertificateDto, CertFormat,
} from '../types'

const BASE = 'http://localhost:8082'

// Simulated network latency (ms)
const LAT = 300

// ─── Auth ─────────────────────────────────────────────────────────────────────

const MOCK_ACCESS_TOKEN = 'mock.access.token'
// MOCK_REFRESH_TOKEN is sent as an HttpOnly cookie in production; not used in mock response body

const authHandlers = [
  http.post(`${BASE}/api/v1/auth/login`, async ({ request }) => {
    await delay(LAT)
    const body = await request.json() as { username?: string; password?: string; tenantSlug?: string }
    const tenant = tenants.find(t => t.slug === body.tenantSlug && t.status === 'ACTIVE')
    if (body.username === 'admin' && body.password === 'admin' && tenant) {
      return HttpResponse.json({
        accessToken: MOCK_ACCESS_TOKEN,
        // refreshToken is NOT in the body — delivered via HttpOnly cookie in production
        tokenType: 'Bearer',
        expiresIn: 3600,
        mustChangePassword: false,
        user: {
          id: 'usr-001',
          tenantId: tenant.id,
          username: 'admin',
          email: 'admin@routify.dev',
          role: 'SUPER_ADMIN',
          mustChangePassword: false,
        },
      })
    }
    return HttpResponse.json(
      { type: 'about:blank', title: 'Unauthorized', status: 401, detail: 'Invalid credentials.' },
      { status: 401 },
    )
  }),

  http.post(`${BASE}/api/v1/auth/refresh`, async () => {
    await delay(LAT)
    // In mock mode the cookie is not real — always succeed to keep DX smooth
    return HttpResponse.json({
      accessToken: MOCK_ACCESS_TOKEN,
      tokenType: 'Bearer',
      expiresIn: 3600,
      mustChangePassword: false,
      user: {
        id: 'usr-001',
        tenantId: 'ten-platform',
        username: 'admin',
        email: 'admin@routify.dev',
        role: 'SUPER_ADMIN',
        mustChangePassword: false,
      },
    })
  }),

  http.post(`${BASE}/api/v1/auth/logout`, async () => {
    await delay(100)
    return HttpResponse.json({ message: 'Logged out' })
  }),
]

// ─── Routes ───────────────────────────────────────────────────────────────────

const routeHandlers = [
  // LIST
  http.get(`${BASE}/api/v1/admin/routes`, async ({ request }) => {
    await delay(LAT)
    const url = new URL(request.url)
    const status  = url.searchParams.get('status') ?? ''
    const page    = Number(url.searchParams.get('page')  ?? 0)
    const size    = Number(url.searchParams.get('size')  ?? 20)
    const sortDir = url.searchParams.get('sortDir') ?? 'DESC'

    let list = [...routes]
    if (status) list = list.filter(r => r.status === status)
    if (sortDir === 'DESC') list = list.reverse()

    return HttpResponse.json(buildPage(list.map(toRouteSummary), page, size))
  }),

  // GET ONE
  http.get(`${BASE}/api/v1/admin/routes/:id`, async ({ params }) => {
    await delay(LAT)
    const route = routes.find(r => r.id === params.id)
    if (!route) return HttpResponse.json({ status: 404, detail: 'Route not found' }, { status: 404 })
    return HttpResponse.json(route)
  }),

  // CREATE
  http.post(`${BASE}/api/v1/admin/routes`, async ({ request }) => {
    await delay(LAT)
    const body = await request.json() as CreateRouteRequest
    const newRoute: RouteDto = {
      id: nextId(),
      tenantId: 'ten-platform',
      ...body,
      status: 'DRAFT',
      version: 1,
      filters: [],
      createdBy: 'admin',
      createdAt: now(),
      updatedAt: now(),
    }
    routes.unshift(newRoute)
    return HttpResponse.json(newRoute, { status: 201 })
  }),

  // UPDATE
  http.put(`${BASE}/api/v1/admin/routes/:id`, async ({ params, request }) => {
    await delay(LAT)
    const idx = routes.findIndex(r => r.id === params.id)
    if (idx === -1) return HttpResponse.json({ status: 404 }, { status: 404 })
    const body = await request.json() as UpdateRouteRequest
    const updated = { ...routes[idx], ...body, updatedAt: now(), version: routes[idx].version + 1 }
    routes[idx] = updated
    return HttpResponse.json(updated)
  }),

  // ACTIVATE
  http.post(`${BASE}/api/v1/admin/routes/:id/activate`, async ({ params }) => {
    await delay(LAT)
    const idx = routes.findIndex(r => r.id === params.id)
    if (idx === -1) return HttpResponse.json({ status: 404 }, { status: 404 })
    routes[idx] = { ...routes[idx], status: 'ACTIVE', activatedAt: now(), updatedAt: now() }
    return HttpResponse.json(routes[idx])
  }),

  // DEACTIVATE
  http.post(`${BASE}/api/v1/admin/routes/:id/deactivate`, async ({ params }) => {
    await delay(LAT)
    const idx = routes.findIndex(r => r.id === params.id)
    if (idx === -1) return HttpResponse.json({ status: 404 }, { status: 404 })
    routes[idx] = { ...routes[idx], status: 'DISABLED', updatedAt: now() }
    return HttpResponse.json(routes[idx])
  }),

  // DELETE
  http.delete(`${BASE}/api/v1/admin/routes/:id`, async ({ params }) => {
    await delay(LAT)
    const idx = routes.findIndex(r => r.id === params.id)
    if (idx === -1) return HttpResponse.json({ status: 404 }, { status: 404 })
    routes.splice(idx, 1)
    return new HttpResponse(null, { status: 204 })
  }),

  // CLONE
  http.post(`${BASE}/api/v1/admin/routes/:id/clone`, async ({ params }) => {
    await delay(LAT)
    const source = routes.find(r => r.id === params.id)
    if (!source) return HttpResponse.json({ status: 404 }, { status: 404 })
    const cloned: RouteDto = {
      ...source,
      id: nextId(),
      name: `${source.name} (copy)`,
      status: 'DRAFT',
      version: 1,
      filters: source.filters.map(f => ({ ...f })),
      activatedAt: undefined,
      createdAt: now(),
      updatedAt: now(),
    }
    routes.unshift(cloned)
    return HttpResponse.json(cloned, { status: 201 })
  }),

  // ATTACH FILTER
  http.post(`${BASE}/api/v1/admin/routes/:routeId/filters`, async ({ params, request }) => {
    await delay(LAT)
    const idx = routes.findIndex(r => r.id === params.routeId)
    if (idx === -1) return HttpResponse.json({ status: 404 }, { status: 404 })
    const body = await request.json() as { filterId: string; order: number; phase: 'PRE' | 'POST' }
    const filter = filters.find(f => f.id === body.filterId)
    if (!filter) return HttpResponse.json({ status: 404, detail: 'Filter not found' }, { status: 404 })
    const ref = {
      filterId: filter.id,
      filterName: filter.name,
      filterType: filter.filterType,
      order: body.order,
      phase: body.phase,
      enabled: true,
    }
    routes[idx] = {
      ...routes[idx],
      filters: [...routes[idx].filters, ref],
      updatedAt: now(),
    }
    return HttpResponse.json(routes[idx])
  }),

  // DETACH FILTER
  http.delete(`${BASE}/api/v1/admin/routes/:routeId/filters/:filterId`, async ({ params }) => {
    await delay(LAT)
    const idx = routes.findIndex(r => r.id === params.routeId)
    if (idx === -1) return HttpResponse.json({ status: 404 }, { status: 404 })
    routes[idx] = {
      ...routes[idx],
      filters: routes[idx].filters.filter(f => f.filterId !== params.filterId),
      updatedAt: now(),
    }
    return HttpResponse.json(routes[idx])
  }),
]

// ─── Filters ──────────────────────────────────────────────────────────────────

const filterHandlers = [
  // LIST
  http.get(`${BASE}/api/v1/admin/filters`, async ({ request }) => {
    await delay(LAT)
    const url  = new URL(request.url)
    const page = Number(url.searchParams.get('page') ?? 0)
    const size = Number(url.searchParams.get('size') ?? 20)
    return HttpResponse.json(buildPage(filters.map(toFilterSummary), page, size))
  }),

  // GET ONE
  http.get(`${BASE}/api/v1/admin/filters/:id`, async ({ params }) => {
    await delay(LAT)
    const f = filters.find(f => f.id === params.id)
    if (!f) return HttpResponse.json({ status: 404 }, { status: 404 })
    return HttpResponse.json(f)
  }),

  // CREATE
  http.post(`${BASE}/api/v1/admin/filters`, async ({ request }) => {
    await delay(LAT)
    const body = await request.json() as CreateFilterRequest
    const newFilter: FilterDefinitionDto = {
      id: nextId(),
      tenantId: 'ten-platform',
      ...body,
      systemManaged: false,
      enabled: true,
      usageCount: 0,
      createdBy: 'admin',
      createdAt: now(),
      updatedAt: now(),
    }
    filters.unshift(newFilter)
    return HttpResponse.json(newFilter, { status: 201 })
  }),

  // UPDATE
  http.put(`${BASE}/api/v1/admin/filters/:id`, async ({ params, request }) => {
    await delay(LAT)
    const idx = filters.findIndex(f => f.id === params.id)
    if (idx === -1) return HttpResponse.json({ status: 404 }, { status: 404 })
    const body = await request.json() as UpdateFilterRequest
    const updated: FilterDefinitionDto = { ...filters[idx], ...body, gatewayConfigRef: (body.gatewayConfigRef ?? undefined), updatedAt: now() }
    filters[idx] = updated
    return HttpResponse.json(updated)
  }),

  // DELETE
  http.delete(`${BASE}/api/v1/admin/filters/:id`, async ({ params }) => {
    await delay(LAT)
    const idx = filters.findIndex(f => f.id === params.id)
    if (idx === -1) return HttpResponse.json({ status: 404 }, { status: 404 })
    filters.splice(idx, 1)
    return new HttpResponse(null, { status: 204 })
  }),
]

// ─── Users ────────────────────────────────────────────────────────────────────

const userHandlers = [
  // LIST
  http.get(`${BASE}/api/v1/admin/users`, async ({ request }) => {
    await delay(LAT)
    const url  = new URL(request.url)
    const page = Number(url.searchParams.get('page') ?? 0)
    const size = Number(url.searchParams.get('size') ?? 20)
    return HttpResponse.json(buildPage([...users], page, size))
  }),

  // GET ONE
  http.get(`${BASE}/api/v1/admin/users/:id`, async ({ params }) => {
    await delay(LAT)
    const u = users.find(u => u.id === params.id)
    if (!u) return HttpResponse.json({ status: 404 }, { status: 404 })
    return HttpResponse.json(u)
  }),

  // CREATE
  http.post(`${BASE}/api/v1/admin/users`, async ({ request }) => {
    await delay(LAT)
    const body = await request.json() as CreateUserRequest
    const existing = users.find(u => u.username === body.username)
    if (existing) {
      return HttpResponse.json(
        { status: 409, detail: `Username "${body.username}" is already taken.` },
        { status: 409 },
      )
    }
    const newUser: UserDto = {
      id: nextId(),
      tenantId: 'ten-platform',
      username: body.username,
      email: body.email,
      role: body.role,
      status: 'ACTIVE',
      createdAt: now(),
    }
    users.push(newUser)
    return HttpResponse.json(newUser, { status: 201 })
  }),

  // UPDATE ROLE
  http.put(`${BASE}/api/v1/admin/users/:id`, async ({ params, request }) => {
    await delay(LAT)
    const idx = users.findIndex(u => u.id === params.id)
    if (idx === -1) return HttpResponse.json({ status: 404 }, { status: 404 })
    const body = await request.json() as { role: string }
    users[idx] = { ...users[idx], role: body.role as UserDto['role'] }
    return HttpResponse.json(users[idx])
  }),

  // DELETE
  http.delete(`${BASE}/api/v1/admin/users/:id`, async ({ params }) => {
    await delay(LAT)
    const idx = users.findIndex(u => u.id === params.id)
    if (idx === -1) return HttpResponse.json({ status: 404 }, { status: 404 })
    users.splice(idx, 1)
    return new HttpResponse(null, { status: 204 })
  }),
]

// ─── Audit ────────────────────────────────────────────────────────────────────

const auditHandlers = [
  // ─── Domain events ─────────────────────────────────────────────────────────
  http.get(`${BASE}/api/v1/admin/audit/events`, async ({ request }) => {
    await delay(LAT)
    const url  = new URL(request.url)
    const page = Number(url.searchParams.get('page') ?? 0)
    const size = Number(url.searchParams.get('size') ?? 50)
    const eventType     = url.searchParams.get('eventType')
    const aggregateType = url.searchParams.get('aggregateType')
    let list = [...auditEvents].reverse()
    if (eventType)     list = list.filter(e => e.eventType === eventType)
    if (aggregateType) list = list.filter(e => e.aggregateType === aggregateType)
    return HttpResponse.json(buildPage(list, page, size))
  }),

  http.get(`${BASE}/api/v1/admin/audit/events/route/:routeId`, async ({ params }) => {
    await delay(LAT)
    const list = auditEvents.filter(e => e.aggregateId === params.routeId)
    return HttpResponse.json(list)
  }),

  // ─── Request logs ──────────────────────────────────────────────────────────
  http.get(`${BASE}/api/v1/admin/audit/requests`, async ({ request }) => {
    await delay(LAT)
    const url     = new URL(request.url)
    const page    = Number(url.searchParams.get('page') ?? 0)
    const size    = Number(url.searchParams.get('size') ?? 50)
    const routeId = url.searchParams.get('routeId')
    let list = [...requestLogs]
    if (routeId) list = list.filter(r => r.routeId === routeId)
    return HttpResponse.json(buildPage(list, page, size))
  }),

  http.get(`${BASE}/api/v1/admin/audit/requests/stats/:routeId`, async ({ params }) => {
    await delay(LAT)
    const logs = requestLogs.filter(r => r.routeId === params.routeId)
    const total = logs.length
    const avgDuration = total > 0 ? logs.reduce((s, r) => s + (r.durationMs ?? 0), 0) / total : 0
    const maxDuration = total > 0 ? Math.max(...logs.map(r => r.durationMs ?? 0)) : 0
    const errorCount  = logs.filter(r => (r.responseStatus ?? 0) >= 400).length
    return HttpResponse.json({ routeId: params.routeId, totalRequests: total, avgDurationMs: avgDuration, maxDurationMs: maxDuration, errorCount })
  }),

  // ─── Replay — via admin-api proxy ─────────────────────────────────────────
  http.get(`${BASE}/api/v1/admin/audit/replay/failed`, async ({ request }) => {
    await delay(LAT)
    const url     = new URL(request.url)
    const page    = Number(url.searchParams.get('page') ?? 0)
    const size    = Number(url.searchParams.get('size') ?? 50)
    const routeId = url.searchParams.get('routeId')
    let list = [...failedRequests]
    if (routeId) list = list.filter(r => r.routeId === routeId)
    return HttpResponse.json(buildPage(list, page, size))
  }),

  http.get(`${BASE}/api/v1/admin/audit/replay/pending`, async ({ request }) => {
    await delay(LAT)
    const url     = new URL(request.url)
    const page    = Number(url.searchParams.get('page') ?? 0)
    const size    = Number(url.searchParams.get('size') ?? 50)
    const routeId = url.searchParams.get('routeId')
    let list = failedRequests.filter(r => r.replayStatus === 'PENDING' || r.replayStatus === 'FAILED')
    if (routeId) list = list.filter(r => r.routeId === routeId)
    return HttpResponse.json(buildPage(list, page, size))
  }),

  http.get(`${BASE}/api/v1/admin/audit/replay/stats`, async () => {
    await delay(LAT)
    const count = (s: string) => failedRequests.filter(r => r.replayStatus === s).length
    return HttpResponse.json({
      pending:    count('PENDING'),
      inProgress: count('IN_PROGRESS'),
      succeeded:  count('SUCCEEDED'),
      failed:     count('FAILED'),
      skipped:    count('SKIPPED'),
    })
  }),

  // POST /replay/:id — replay single (via admin-api proxy)
  http.post(`${BASE}/api/v1/admin/audit/replay/:id`, async ({ params }) => {
    await delay(LAT * 2) // simulate real HTTP call
    const id  = params.id as string
    const idx = failedRequests.findIndex(r => r.id === id)
    if (idx === -1) {
      return HttpResponse.json({ type: 'Not Found', title: 'Not Found', status: 404, detail: 'Request log not found' }, { status: 404 })
    }
    const entry = failedRequests[idx]
    if (entry.replayCount >= 5) {
      failedRequests[idx] = { ...entry, replayStatus: 'SKIPPED', replayError: 'Max attempts exceeded' }
      return HttpResponse.json({ requestLogId: id, outcome: 'SKIPPED', message: 'Max attempts exceeded' })
    }
    // Simulate 70% success rate
    const success = Math.random() > 0.3
    if (success) {
      failedRequests[idx] = {
        ...entry,
        replayStatus: 'SUCCEEDED',
        replayCount: entry.replayCount + 1,
        replayedAt: now(),
        replayResponseStatus: 200,
        replayError: undefined,
      }
      return HttpResponse.json({ requestLogId: id, outcome: 'SUCCEEDED', responseStatus: 200 })
    } else {
      failedRequests[idx] = {
        ...entry,
        replayStatus: 'FAILED',
        replayCount: entry.replayCount + 1,
        replayedAt: now(),
        replayError: 'Upstream still returning 503',
      }
      return HttpResponse.json({ requestLogId: id, outcome: 'FAILED', message: 'Upstream still returning 503' }, { status: 502 })
    }
  }),

  // POST /replay/bulk (via admin-api proxy)
  http.post(`${BASE}/api/v1/admin/audit/replay/bulk`, async ({ request }) => {
    await delay(LAT * 3)
    const url   = new URL(request.url)
    const limit = Math.min(Number(url.searchParams.get('limit') ?? 50), 200)
    const candidates = failedRequests
      .filter(r => (r.replayStatus === 'PENDING' || r.replayStatus === 'FAILED') && r.replayCount < 5)
      .slice(0, limit)

    let succeeded = 0, failed = 0; const skipped = 0
    candidates.forEach(entry => {
      const idx = failedRequests.findIndex(r => r.id === entry.id)
      if (idx === -1) return
      if (Math.random() > 0.35) {
        failedRequests[idx] = { ...entry, replayStatus: 'SUCCEEDED', replayCount: entry.replayCount + 1, replayedAt: now(), replayResponseStatus: 200 }
        succeeded++
      } else {
        failedRequests[idx] = { ...entry, replayStatus: 'FAILED', replayCount: entry.replayCount + 1, replayedAt: now(), replayError: 'Upstream error' }
        failed++
      }
    })
    return HttpResponse.json({ total: candidates.length, succeeded, failed, skipped })
  }),
]


// ─── Gateway Configuration ────────────────────────────────────────────────────

const gatewayHandlers = [
  // Full config
  http.get(`${BASE}/api/v1/admin/gateway/config`, async () => {
    await delay(LAT)
    return HttpResponse.json(gatewayConfig)
  }),

  http.put(`${BASE}/api/v1/admin/gateway/config`, async ({ request }) => {
    await delay(LAT)
    const body = await request.json() as GatewayConfig
    Object.assign(gatewayConfig, body, { updatedAt: now(), updatedBy: 'admin' })
    return HttpResponse.json(gatewayConfig)
  }),

  // Live status
  http.get(`${BASE}/api/v1/admin/gateway/status`, async () => {
    await delay(LAT * 2)
    return HttpResponse.json({
      health: { status: 'UP', components: { redis: { status: 'UP' }, kafka: { status: 'UP' } } },
      routes: { timestamp: now(), count: routes.filter(r => r.status === 'ACTIVE').length, routes: [] },
      circuitBreakers: {
        'route-service-cb': { state: 'CLOSED', failureRate: 0, slowCallRate: 0, bufferedCalls: 0 },
        'admin-cb': { state: 'CLOSED', failureRate: 0, slowCallRate: 0, bufferedCalls: 0 },
      },
    })
  }),

  http.post(`${BASE}/api/v1/admin/gateway/reload`, async () => {
    await delay(LAT)
    return HttpResponse.json({ status: 'Route refresh event published', timestamp: now() })
  }),

  http.get(`${BASE}/api/v1/admin/gateway/metrics`, async () => {
    await delay(LAT)
    return HttpResponse.json({
      name: 'spring.cloud.gateway.requests',
      measurements: [{ statistic: 'COUNT', value: 48291 }],
      availableTags: [],
    })
  }),

  // CORS
  http.get(`${BASE}/api/v1/admin/gateway/cors`, async () => {
    await delay(LAT)
    return HttpResponse.json(gatewayConfig.cors)
  }),
  http.put(`${BASE}/api/v1/admin/gateway/cors`, async ({ request }) => {
    await delay(LAT)
    const body = await request.json() as GatewayCorsConfig
    gatewayConfig.cors = body
    gatewayConfig.updatedAt = now()
    return HttpResponse.json(gatewayConfig)
  }),

  // Security Headers
  http.get(`${BASE}/api/v1/admin/gateway/security-headers`, async () => {
    await delay(LAT)
    return HttpResponse.json(gatewayConfig.securityHeaders)
  }),
  http.put(`${BASE}/api/v1/admin/gateway/security-headers`, async ({ request }) => {
    await delay(LAT)
    const body = await request.json() as GatewaySecurityHeadersConfig
    gatewayConfig.securityHeaders = body
    gatewayConfig.updatedAt = now()
    return HttpResponse.json(gatewayConfig)
  }),

  // Rate Limit Policies
  http.get(`${BASE}/api/v1/admin/gateway/rate-limit-policies`, async () => {
    await delay(LAT)
    return HttpResponse.json(gatewayConfig.rateLimitPolicies)
  }),
  http.put(`${BASE}/api/v1/admin/gateway/rate-limit-policies`, async ({ request }) => {
    await delay(LAT)
    const body = await request.json() as GatewayRateLimitPolicy[]
    gatewayConfig.rateLimitPolicies = body
    gatewayConfig.updatedAt = now()
    return HttpResponse.json(gatewayConfig)
  }),
  http.put(`${BASE}/api/v1/admin/gateway/rate-limit-policies/:policyId`, async ({ params, request }) => {
    await delay(LAT)
    const body = await request.json() as GatewayRateLimitPolicy
    const policies = gatewayConfig.rateLimitPolicies.filter(p => p.id !== params.policyId)
    gatewayConfig.rateLimitPolicies = [...policies, { ...body, id: params.policyId as string }]
    gatewayConfig.updatedAt = now()
    return HttpResponse.json(gatewayConfig)
  }),
  http.delete(`${BASE}/api/v1/admin/gateway/rate-limit-policies/:policyId`, async ({ params }) => {
    await delay(LAT)
    gatewayConfig.rateLimitPolicies = gatewayConfig.rateLimitPolicies.filter(p => p.id !== params.policyId)
    gatewayConfig.updatedAt = now()
    return new HttpResponse(null, { status: 204 })
  }),

  // Circuit Breaker
  http.get(`${BASE}/api/v1/admin/gateway/circuit-breaker`, async () => {
    await delay(LAT)
    return HttpResponse.json(gatewayConfig.circuitBreakerDefaults)
  }),
  http.put(`${BASE}/api/v1/admin/gateway/circuit-breaker`, async ({ request }) => {
    await delay(LAT)
    const body = await request.json() as GatewayCircuitBreakerDefaults
    gatewayConfig.circuitBreakerDefaults = body
    gatewayConfig.updatedAt = now()
    return HttpResponse.json(gatewayConfig)
  }),
  http.get(`${BASE}/api/v1/admin/gateway/circuit-breaker/states`, async () => {
    await delay(LAT)
    return HttpResponse.json({
      'route-service-cb': { state: 'CLOSED', failureRate: 2.1, slowCallRate: 0, bufferedCalls: 100 },
      'admin-cb': { state: 'CLOSED', failureRate: 0, slowCallRate: 0, bufferedCalls: 15 },
      'default': { state: 'CLOSED', failureRate: 1.5, slowCallRate: 0, bufferedCalls: 250 },
    })
  }),

  // Resilience
  http.get(`${BASE}/api/v1/admin/gateway/resilience`, async () => {
    await delay(LAT)
    return HttpResponse.json(gatewayConfig.resilienceDefaults)
  }),
  http.put(`${BASE}/api/v1/admin/gateway/resilience`, async ({ request }) => {
    await delay(LAT)
    const body = await request.json() as GatewayResilienceDefaults
    gatewayConfig.resilienceDefaults = body
    gatewayConfig.updatedAt = now()
    return HttpResponse.json(gatewayConfig)
  }),

  // Auth Providers
  http.get(`${BASE}/api/v1/admin/gateway/auth-providers`, async () => {
    await delay(LAT)
    return HttpResponse.json(gatewayConfig.authProviders)
  }),
  http.put(`${BASE}/api/v1/admin/gateway/auth-providers/:providerId`, async ({ params, request }) => {
    await delay(LAT)
    const body = await request.json() as GatewayAuthProvider
    const list = gatewayConfig.authProviders.filter(p => p.id !== params.providerId)
    gatewayConfig.authProviders = [...list, { ...body, id: params.providerId as string }]
    gatewayConfig.updatedAt = now()
    return HttpResponse.json(gatewayConfig)
  }),
  http.delete(`${BASE}/api/v1/admin/gateway/auth-providers/:providerId`, async ({ params }) => {
    await delay(LAT)
    gatewayConfig.authProviders = gatewayConfig.authProviders.filter(p => p.id !== params.providerId)
    gatewayConfig.updatedAt = now()
    return new HttpResponse(null, { status: 204 })
  }),


  // TLS / Certificates
  // GET TLS config (used by CertGatewayMapModal to show configured file-source slots)
  http.get(`${BASE}/api/v1/admin/gateway/tls`, async () => {
    await delay(LAT)
    return HttpResponse.json(gatewayConfig.tlsConfig)
  }),

  // Live in-memory certificate registry (flat map: logicalId → entry)
  http.get(`${BASE}/api/v1/admin/gateway/tls/certificates`, async () => {
    await delay(LAT)
    const registryEntries: Record<string, {
      fingerprint?: string; notAfter?: string; source?: string; status?: string
    }> = {}
    gatewayConfig.tlsConfig.fileSources.forEach((s: GatewayCertificateSource) => {
      if (s.logicalId) {
        registryEntries[s.logicalId] = {
          fingerprint: 'AA:BB:CC:DD:EE:FF:00:11:22:33:44:55:66:77:88:99:AA:BB:CC:DD',
          notAfter: s.expiresAt ?? new Date(Date.now() + 365 * 86400 * 1000).toISOString(),
          source: `file:${s.certificatePath}`,
          status: s.status ?? 'VALID',
        }
      }
    })
    // Add group-bound active certs under their group's logicalId (effectiveGatewayLogicalId)
    mockCertGroups.filter((g: CertGroupDto) => g.status === 'ACTIVE').forEach((g: CertGroupDto) => {
      const activeMembers = mockCerts.filter((c: CertificateDto) => c.groupId === g.id && c.status === 'ACTIVE')
      if (activeMembers.length > 0) {
        const worst = activeMembers.find((c: CertificateDto) => c.expiryStatus === 'EXPIRED')
          ?? activeMembers.find((c: CertificateDto) => c.expiryStatus === 'EXPIRING_SOON')
          ?? activeMembers[0]
        registryEntries[g.logicalId] = {
          fingerprint: worst.fingerprintSha256 ?? worst.fingerprintSha1 ?? 'group-cert-fp',
          notAfter: worst.notAfter,
          source: `group:${g.id}`,
          status: worst.expiryStatus === 'EXPIRED' ? 'EXPIRED' : worst.expiryStatus === 'EXPIRING_SOON' ? 'EXPIRING_SOON' : 'VALID',
        }
      }
    })
    return HttpResponse.json(registryEntries)
  }),

  http.get(`${BASE}/api/v1/admin/gateway/tls/vault-certs`, async () => {
    await delay(LAT)
    // Return active certs that have an effective gateway logical ID
    return HttpResponse.json(mockCerts.filter((c: CertificateDto) => c.status === 'ACTIVE' && c.effectiveGatewayLogicalId))
  }),
  http.put(`${BASE}/api/v1/admin/gateway/tls`, async ({ request }) => {
    await delay(LAT)
    const body = await request.json() as GatewayTlsConfig
    gatewayConfig.tlsConfig = body
    gatewayConfig.updatedAt = now()
    return HttpResponse.json(gatewayConfig)
  }),

  // Proxy
  http.get(`${BASE}/api/v1/admin/gateway/proxy`, async () => {
    await delay(LAT)
    return HttpResponse.json(gatewayConfig.proxyConfig)
  }),
  http.put(`${BASE}/api/v1/admin/gateway/proxy`, async ({ request }) => {
    await delay(LAT)
    const body = await request.json() as GatewayProxyConfig
    gatewayConfig.proxyConfig = body
    gatewayConfig.updatedAt = now()
    return HttpResponse.json(gatewayConfig)
  }),

  // HTTP Client
  http.get(`${BASE}/api/v1/admin/gateway/http-client`, async () => {
    await delay(LAT)
    return HttpResponse.json(gatewayConfig.httpClientConfig)
  }),
  http.put(`${BASE}/api/v1/admin/gateway/http-client`, async ({ request }) => {
    await delay(LAT)
    const body = await request.json() as GatewayHttpClientConfig
    gatewayConfig.httpClientConfig = body
    gatewayConfig.updatedAt = now()
    return HttpResponse.json(gatewayConfig)
  }),

  // Global Filters (not yet in GatewayConfig schema — stubbed for future use)
  http.get(`${BASE}/api/v1/admin/gateway/global-filters`, async () => {
    await delay(LAT)
    return HttpResponse.json([])
  }),
  http.put(`${BASE}/api/v1/admin/gateway/global-filters`, async () => {
    await delay(LAT)
    return HttpResponse.json(gatewayConfig)
  }),

  // Tenant Isolation
  http.get(`${BASE}/api/v1/admin/gateway/tenant-isolation`, async () => {
    await delay(LAT)
    return HttpResponse.json(gatewayConfig.tenantIsolation)
  }),
  http.put(`${BASE}/api/v1/admin/gateway/tenant-isolation`, async ({ request }) => {
    await delay(LAT)
    const body = await request.json() as GatewayTenantIsolationConfig
    gatewayConfig.tenantIsolation = body
    gatewayConfig.updatedAt = now()
    return HttpResponse.json(gatewayConfig)
  }),
]

// ─── Exports ──────────────────────────────────────────────────────────────────

// ─── Certificate Vault Handlers ───────────────────────────────────────────────

// ── In-memory cert groups store ───────────────────────────────────────────────
const mockCertGroups: CertGroupDto[] = [
  {
    id: 'grp-001',
    tenantId: 'ten-platform',
    logicalId: 'api-inbound-tls',
    alias: 'API Gateway Inbound TLS',
    description: 'Main inbound TLS group for the API gateway',
    status: 'ACTIVE',
    memberCount: 1,
    expiryHealthStatus: 'VALID',
    createdBy: 'admin',
    createdAt: '2024-01-01T10:00:00Z',
    updatedAt: '2024-01-01T10:00:00Z',
  },
  {
    id: 'grp-002',
    tenantId: 'ten-platform',
    logicalId: 'mtls-client-ca',
    alias: 'mTLS Client CA Group',
    description: 'CA certificates for mutual TLS client authentication',
    status: 'ACTIVE',
    memberCount: 1,
    expiryHealthStatus: 'EXPIRING_SOON',
    createdBy: 'admin',
    createdAt: '2023-01-01T09:00:00Z',
    updatedAt: '2023-01-01T09:00:00Z',
  },
]

// ── In-memory certs store (linked to groups) ──────────────────────────────────
const mockCerts: CertificateDto[] = [
  {
    id: 'cert-001',
    tenantId: 'ten-platform',
    logicalId: 'cert-cert-001',
    alias: 'API Gateway Inbound TLS — 2024',
    description: 'Main inbound TLS certificate for API gateway',
    format: 'PEM',
    status: 'ACTIVE',
    expiryStatus: 'VALID',
    subjectDn: 'CN=api.routify.dev, O=Routify Inc, C=GR',
    issuerDn: 'CN=Let\'s Encrypt R3, O=Let\'s Encrypt, C=US',
    serialNumber: '03A1B2C3D4E5F6',
    notBefore: '2024-01-01T00:00:00Z',
    notAfter: '2027-01-01T00:00:00Z',
    signatureAlg: 'SHA256withRSA',
    keyAlgorithm: 'RSA',
    keySize: 2048,
    fingerprintSha1: 'AA:BB:CC:DD:EE:FF:00:11:22:33:44:55:66:77:88:99:AA:BB:CC:DD',
    fingerprintSha256: 'AA:BB:CC:DD:EE:FF:00:11:22:33:44:55:66:77:88:99:AA:BB:CC:DD:EE:FF:00:11:22:33:44:55:66:77:88:99',
    sanDns: ['api.routify.dev', '*.routify.dev'],
    sanIp: [],
    isCa: false,
    hasPrivateKey: true,
    groupId: 'grp-001',
    groupLogicalId: 'api-inbound-tls',
    memberAlias: 'primary',
    effectiveGatewayLogicalId: 'api-inbound-tls',
    gatewayTlsLogicalId: undefined,
    uploadedBy: 'admin',
    createdAt: '2024-01-01T10:00:00Z',
    updatedAt: '2024-01-01T10:00:00Z',
    expiresAt: '2027-01-01T00:00:00Z',
  },
  {
    id: 'cert-002',
    tenantId: 'ten-platform',
    logicalId: 'cert-cert-002',
    alias: 'mTLS Client CA Certificate',
    description: 'CA certificate for mutual TLS client authentication',
    format: 'PEM',
    status: 'ACTIVE',
    expiryStatus: 'EXPIRING_SOON',
    subjectDn: 'CN=Client CA, O=Routify Inc, C=GR',
    issuerDn: 'CN=Client CA, O=Routify Inc, C=GR',
    serialNumber: '01',
    notBefore: '2023-01-01T00:00:00Z',
    notAfter: '2025-04-30T00:00:00Z',
    signatureAlg: 'SHA256withECDSA',
    keyAlgorithm: 'EC',
    keySize: 256,
    fingerprintSha1: '11:22:33:44:55:66:77:88:99:AA:BB:CC:DD:EE:FF:00:11:22:33:44',
    fingerprintSha256: undefined,
    sanDns: [],
    sanIp: [],
    isCa: true,
    hasPrivateKey: false,
    groupId: 'grp-002',
    groupLogicalId: 'mtls-client-ca',
    memberAlias: 'ca-root',
    effectiveGatewayLogicalId: 'mtls-client-ca',
    gatewayTlsLogicalId: undefined,
    uploadedBy: 'admin',
    createdAt: '2023-01-01T09:00:00Z',
    updatedAt: '2023-01-01T09:00:00Z',
    expiresAt: '2025-04-30T00:00:00Z',
  },
]

// Helper: recompute group memberCount + expiryHealthStatus from current mockCerts
function syncGroupStats() {
  mockCertGroups.forEach(g => {
    const members = mockCerts.filter(c => c.groupId === g.id && c.status !== 'DELETED')
    g.memberCount = members.length
    const statuses = members.filter(c => c.status === 'ACTIVE').map((c: CertificateDto) => c.expiryStatus as string)
    if (statuses.includes('EXPIRED'))       g.expiryHealthStatus = 'EXPIRED'
    else if (statuses.includes('EXPIRING_SOON')) g.expiryHealthStatus = 'EXPIRING_SOON'
    else g.expiryHealthStatus = 'VALID'
  })
}

// Helper: build group detail (with members array)
function buildGroupDetail(g: CertGroupDto) {
  const members = mockCerts.filter(c => c.groupId === g.id && c.status !== 'DELETED')
  return { ...g, members }
}

const certGroupHandlers = [
  // LIST groups
  http.get(`${BASE}/api/v1/admin/cert-groups`, async ({ request }) => {
    await delay(LAT)
    syncGroupStats()
    const url    = new URL(request.url)
    const status = url.searchParams.get('status')
    const page   = Number(url.searchParams.get('page') ?? 0)
    const size   = Number(url.searchParams.get('size') ?? 20)
    let list = [...mockCertGroups]
    if (status) list = list.filter(g => g.status === status)
    return HttpResponse.json(buildPage(list, page, size))
  }),

  // GET single group (with members)
  http.get(`${BASE}/api/v1/admin/cert-groups/:id`, async ({ params }) => {
    await delay(LAT)
    syncGroupStats()
    const g = mockCertGroups.find(g => g.id === params.id)
    if (!g) return HttpResponse.json({ status: 404, detail: 'Group not found' }, { status: 404 })
    return HttpResponse.json(buildGroupDetail(g))
  }),

  // CREATE group
  http.post(`${BASE}/api/v1/admin/cert-groups`, async ({ request }) => {
    await delay(LAT * 2)
    const body = await request.json() as Record<string, unknown>
    if (mockCertGroups.find(g => g.logicalId === body.logicalId)) {
      return HttpResponse.json({ status: 409, detail: `Group with logicalId '${body.logicalId}' already exists` }, { status: 409 })
    }
    if (mockCertGroups.find(g => g.alias === body.alias)) {
      return HttpResponse.json({ status: 409, detail: `Group with alias '${body.alias}' already exists` }, { status: 409 })
    }
    const newGroup: CertGroupDto = {
      id: `grp-${Date.now()}`,
      tenantId: 'ten-platform',
      logicalId: body.logicalId as string,
      alias: body.alias as string,
      description: (body.description as string) ?? undefined,
      status: 'ACTIVE',
      memberCount: 0,
      expiryHealthStatus: 'VALID',
      createdBy: 'admin',
      createdAt: now(),
      updatedAt: now(),
    }
    mockCertGroups.unshift(newGroup)
    return HttpResponse.json({ status: 'accepted', message: 'Certificate group creation in progress' }, { status: 202 })
  }),

  // UPDATE group
  http.put(`${BASE}/api/v1/admin/cert-groups/:id`, async ({ params, request }) => {
    await delay(LAT)
    const g = mockCertGroups.find(g => g.id === params.id)
    if (!g) return HttpResponse.json({ status: 404 }, { status: 404 })
    const body = await request.json() as Record<string, unknown>
    if (body.alias) g.alias = body.alias as string
    if (body.description !== undefined) g.description = body.description as string
    g.updatedAt = now()
    return HttpResponse.json({ status: 'accepted', message: 'Certificate group update in progress' })
  }),

  // ARCHIVE group
  http.post(`${BASE}/api/v1/admin/cert-groups/:id/archive`, async ({ params }) => {
    await delay(LAT)
    const g = mockCertGroups.find(g => g.id === params.id)
    if (!g) return HttpResponse.json({ status: 404 }, { status: 404 })
    g.status = 'ARCHIVED'
    g.updatedAt = now()
    return HttpResponse.json({ status: 'accepted', message: 'Certificate group archival in progress' })
  }),

  // DELETE group
  http.delete(`${BASE}/api/v1/admin/cert-groups/:id`, async ({ params }) => {
    await delay(LAT)
    const idx = mockCertGroups.findIndex(g => g.id === params.id)
    if (idx === -1) return HttpResponse.json({ status: 404 }, { status: 404 })
    // Detach members
    mockCerts.filter(c => c.groupId === params.id).forEach(c => {
      c.groupId = undefined; c.groupLogicalId = undefined; c.memberAlias = undefined; c.effectiveGatewayLogicalId = c.gatewayTlsLogicalId
    })
    mockCertGroups.splice(idx, 1)
    return new HttpResponse(null, { status: 202 })
  }),

  // LIST group members
  http.get(`${BASE}/api/v1/admin/cert-groups/:id/members`, async ({ params }) => {
    await delay(LAT)
    const g = mockCertGroups.find(g => g.id === params.id)
    if (!g) return HttpResponse.json({ status: 404 }, { status: 404 })
    return HttpResponse.json(mockCerts.filter(c => c.groupId === params.id && c.status !== 'DELETED'))
  }),

  // ADD member to group (existing cert)
  http.post(`${BASE}/api/v1/admin/cert-groups/:id/members`, async ({ params, request }) => {
    await delay(LAT)
    const g = mockCertGroups.find(g => g.id === params.id)
    if (!g) return HttpResponse.json({ status: 404 }, { status: 404 })
    const body = await request.json() as Record<string, unknown>
    const cert = mockCerts.find(c => c.id === body.certId)
    if (!cert) return HttpResponse.json({ status: 404, detail: 'Certificate not found' }, { status: 404 })
    cert.groupId = g.id
    cert.groupLogicalId = g.logicalId
    cert.memberAlias = (body.memberAlias as string) ?? undefined
    cert.effectiveGatewayLogicalId = g.logicalId
    syncGroupStats()
    return HttpResponse.json({ status: 'accepted', message: 'Certificate group member addition in progress' }, { status: 202 })
  }),

  // REMOVE member from group
  http.delete(`${BASE}/api/v1/admin/cert-groups/:id/members/:certId`, async ({ params }) => {
    await delay(LAT)
    const cert = mockCerts.find(c => c.id === params.certId)
    if (!cert) return HttpResponse.json({ status: 404 }, { status: 404 })
    cert.groupId = undefined
    cert.groupLogicalId = undefined
    cert.memberAlias = undefined
    cert.effectiveGatewayLogicalId = cert.gatewayTlsLogicalId
    syncGroupStats()
    return HttpResponse.json({ status: 'accepted', message: 'Certificate group member removal in progress' })
  }),
]

const certHandlers = [
  http.get(`${BASE}/api/v1/admin/certificates`, async ({ request }) => {
    await delay(LAT)
    const url    = new URL(request.url)
    const status = url.searchParams.get('status')
    let certs    = mockCerts.filter(c => c.status !== 'DELETED')
    if (status) certs = certs.filter(c => c.status === status)
    return HttpResponse.json({
      content: certs, totalElements: certs.length, totalPages: 1, size: 20, page: 0, first: true, last: true,
    })
  }),

  http.get(`${BASE}/api/v1/admin/certificates/active`, async () => {
    await delay(LAT)
    return HttpResponse.json(mockCerts.filter(c => c.status === 'ACTIVE'))
  }),

  http.get(`${BASE}/api/v1/admin/certificates/stats`, async () => {
    await delay(LAT)
    const active = mockCerts.filter(c => c.status === 'ACTIVE').length
    const expiringSoon = mockCerts.filter(c => c.status === 'ACTIVE' && c.expiryStatus === 'EXPIRING_SOON').length
    return HttpResponse.json({ total: mockCerts.length, active, expiringSoon, counts: { ACTIVE: active } })
  }),

  http.get(`${BASE}/api/v1/admin/certificates/:id`, async ({ params }) => {
    await delay(LAT)
    const cert = mockCerts.find(c => c.id === params.id)
    if (!cert) return HttpResponse.json({ status: 404, detail: 'Not found' }, { status: 404 })
    return HttpResponse.json(cert)
  }),

  // Upload certificate — now expects groupId + memberAlias instead of logicalId
  http.post(`${BASE}/api/v1/admin/certificates`, async ({ request }) => {
    await delay(LAT * 2)
    const body = await request.json() as Record<string, unknown>
    const group = mockCertGroups.find(g => g.id === body.groupId)
    if (!group) {
      return HttpResponse.json({ status: 400, detail: 'Certificate group not found' }, { status: 400 })
    }
    const certId  = `cert-${Date.now()}`
    const newCert: CertificateDto = {
      id: certId,
      tenantId: 'ten-platform',
      logicalId: `cert-${certId}`,
      alias: body.alias as string,
      description: (body.description as string) ?? undefined,
      format: ((body.format as string) ?? 'PEM') as CertFormat,
      status: 'ACTIVE',
      expiryStatus: 'VALID',
      subjectDn: 'CN=uploaded.cert',
      issuerDn: 'CN=Unknown CA',
      serialNumber: Date.now().toString(16).toUpperCase(),
      notBefore: now(),
      notAfter: new Date(Date.now() + 365 * 86400 * 1000).toISOString(),
      signatureAlg: 'SHA256withRSA',
      keyAlgorithm: 'RSA',
      keySize: 2048,
      fingerprintSha1: undefined,
      fingerprintSha256: undefined,
      sanDns: [],
      sanIp: [],
      isCa: false,
      hasPrivateKey: !!body.privateKey,
      groupId: group.id,
      groupLogicalId: group.logicalId,
      memberAlias: (body.memberAlias as string) ?? undefined,
      effectiveGatewayLogicalId: group.logicalId,
      gatewayTlsLogicalId: undefined,
      uploadedBy: 'admin',
      createdAt: now(),
      updatedAt: now(),
      expiresAt: new Date(Date.now() + 365 * 86400 * 1000).toISOString(),
    }
    mockCerts.push(newCert)
    syncGroupStats()
    return HttpResponse.json({ status: 'accepted', message: 'Certificate upload in progress' }, { status: 202 })
  }),

  http.post(`${BASE}/api/v1/admin/certificates/:id/revoke`, async ({ params }) => {
    await delay(LAT)
    const cert = mockCerts.find(c => c.id === params.id)
    if (cert) { cert.status = 'REVOKED'; syncGroupStats() }
    return HttpResponse.json({ status: 'accepted', message: 'Certificate revocation in progress' })
  }),

  http.delete(`${BASE}/api/v1/admin/certificates/:id`, async ({ params }) => {
    await delay(LAT)
    const cert = mockCerts.find(c => c.id === params.id)
    if (cert) { cert.status = 'DELETED'; syncGroupStats() }
    return HttpResponse.json({ status: 'accepted', message: 'Certificate deletion in progress' }, { status: 202 })
  }),
]

// ─── Tenant / Workspace handlers ──────────────────────────────────────────────

const tenantHandlers = [
  // Public — login-page workspace dropdown
  http.get(`${BASE}/api/v1/admin/tenants/workspaces`, async () => {
    await delay(LAT)
    const workspaces = tenants
      .filter(t => t.status === 'ACTIVE')
      .map(t => ({ name: t.name, slug: t.slug }))
    return HttpResponse.json({ workspaces })
  }),

  // Paginated tenant list
  http.get(`${BASE}/api/v1/admin/tenants`, async ({ request }) => {
    await delay(LAT)
    const url  = new URL(request.url)
    const page = Number(url.searchParams.get('page') ?? 0)
    const size = Number(url.searchParams.get('size') ?? 20)
    return HttpResponse.json(buildPage([...tenants], page, size))
  }),

  // Single tenant
  http.get(`${BASE}/api/v1/admin/tenants/:id`, async ({ params }) => {
    await delay(LAT)
    const tenant = tenants.find(t => t.id === params.id)
    if (!tenant) return HttpResponse.json({ error: 'Not found' }, { status: 404 })
    return HttpResponse.json(tenant)
  }),

  // Create workspace (SUPER_ADMIN only — enforced server-side; mock accepts any authed request)
  http.post(`${BASE}/api/v1/admin/tenants`, async ({ request }) => {
    await delay(LAT)
    const body = await request.json() as Partial<TenantDto> & { name: string; slug: string; plan?: string }
    if (tenants.some(t => t.slug === body.slug)) {
      return HttpResponse.json(
        { type: 'about:blank', title: 'Conflict', status: 409, detail: `Workspace slug '${body.slug}' already exists.` },
        { status: 409 },
      )
    }
    const newTenant: TenantDto = {
      id: nextId(),
      name: body.name,
      slug: body.slug,
      status: 'ACTIVE',
      plan: (body.plan ?? 'FREE') as TenantDto['plan'],
      contactEmail: body.contactEmail,
      createdAt: now(),
    }
    tenants.push(newTenant)
    return HttpResponse.json(newTenant, { status: 201 })
  }),

  // Suspend workspace
  http.post(`${BASE}/api/v1/admin/tenants/:id/suspend`, async ({ params }) => {
    await delay(LAT)
    const tenant = tenants.find(t => t.id === params.id)
    if (!tenant) return HttpResponse.json({ error: 'Not found' }, { status: 404 })
    tenant.status = 'SUSPENDED'
    return HttpResponse.json(tenant)
  }),

  // Reactivate workspace
  http.post(`${BASE}/api/v1/admin/tenants/:id/reactivate`, async ({ params }) => {
    await delay(LAT)
    const tenant = tenants.find(t => t.id === params.id)
    if (!tenant) return HttpResponse.json({ error: 'Not found' }, { status: 404 })
    tenant.status = 'ACTIVE'
    return HttpResponse.json(tenant)
  }),
]

export const handlers = [
  ...authHandlers,
  ...routeHandlers,
  ...filterHandlers,
  ...userHandlers,
  ...auditHandlers,
  ...gatewayHandlers,
  ...certGroupHandlers,
  ...certHandlers,
  ...tenantHandlers,
]

