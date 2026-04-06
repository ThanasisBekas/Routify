import { http, HttpResponse, delay } from 'msw'
import { routes, buildPage, MOCK_TENANT_ID } from '../db'
import type { RouteDto, RouteSummary, CreateRouteRequest, UpdateRouteRequest, AttachFilterRequest } from '../../types'
import { filters } from '../db'

function toSummary(r: RouteDto): RouteSummary {
  const pre = r.filters.filter((f) => f.phase === 'PRE').length
  const post = r.filters.filter((f) => f.phase === 'POST').length
  return {
    id: r.id,
    name: r.name,
    description: r.description,
    pathPattern: r.pathPattern,
    methods: r.methods,
    upstreamUri: r.upstreamUri,
    status: r.status,
    environment: r.environment ?? 'PRODUCTION',
    version: r.version,
    filterCount: r.filters.length,
    preFilterCount: pre,
    postFilterCount: post,
    createdAt: r.createdAt,
    activatedAt: r.activatedAt,
  }
}

function genId() {
  return `dddddddd-mock-${Date.now()}-${Math.random().toString(36).slice(2, 7)}`
}

const BASE = '/api/v1/admin/routes'

export const routeHandlers = [
  // ─── List (paginated) ────────────────────────────────────────────────────────
  http.get(BASE, async ({ request }) => {
    await delay(200)
    const url = new URL(request.url)
    const page = parseInt(url.searchParams.get('page') ?? '0', 10)
    const size = parseInt(url.searchParams.get('size') ?? '20', 10)
    const status = url.searchParams.get('status')

    let items = Array.from(routes.values())
    if (status) items = items.filter((r) => r.status === status)
    const environment = url.searchParams.get('environment')
    if (environment) items = items.filter((r) => (r.environment ?? 'PRODUCTION') === environment)
    items = items.sort((a, b) => b.createdAt.localeCompare(a.createdAt))
    return HttpResponse.json(buildPage(items.map(toSummary), page, size))
  }),

  // ─── Get single ──────────────────────────────────────────────────────────────
  http.get(`${BASE}/:id`, async ({ params }) => {
    await delay(150)
    const route = routes.get(params.id as string)
    if (!route) return HttpResponse.json({ status: 404, detail: 'Route not found' }, { status: 404 })
    return HttpResponse.json(route)
  }),

  // ─── Create ──────────────────────────────────────────────────────────────────
  http.post(BASE, async ({ request }) => {
    await delay(400)
    const body = (await request.json()) as CreateRouteRequest
    const now = new Date().toISOString()
    const route: RouteDto = {
      id: genId(),
      tenantId: MOCK_TENANT_ID,
      name: body.name,
      description: body.description,
      pathPattern: body.pathPattern,
      methods: body.methods,
      upstreamUri: body.upstreamUri,
      stripPrefix: body.stripPrefix,
      status: 'DRAFT',
      environment: body.environment ?? 'PRODUCTION',
      version: 1,
      filters: [],
      extraConfig: body.extraConfig,
      createdBy: 'admin',
      createdAt: now,
      updatedAt: now,
    }
    routes.set(route.id, route)
    return HttpResponse.json(route, { status: 201 })
  }),

  // ─── Update ──────────────────────────────────────────────────────────────────
  http.put(`${BASE}/:id`, async ({ params, request }) => {
    await delay(350)
    const route = routes.get(params.id as string)
    if (!route) return HttpResponse.json({ status: 404, detail: 'Route not found' }, { status: 404 })
    const body = (await request.json()) as UpdateRouteRequest
    const updated: RouteDto = { ...route, ...body, updatedAt: new Date().toISOString() }
    routes.set(route.id, updated)
    return HttpResponse.json(updated)
  }),

  // ─── Delete ──────────────────────────────────────────────────────────────────
  http.delete(`${BASE}/:id`, async ({ params }) => {
    await delay(300)
    if (!routes.has(params.id as string))
      return HttpResponse.json({ status: 404, detail: 'Route not found' }, { status: 404 })
    routes.delete(params.id as string)
    return new HttpResponse(null, { status: 204 })
  }),

  // ─── Activate ────────────────────────────────────────────────────────────────
  http.post(`${BASE}/:id/activate`, async ({ params }) => {
    await delay(500)
    const route = routes.get(params.id as string)
    if (!route) return HttpResponse.json({ status: 404, detail: 'Route not found' }, { status: 404 })
    if (route.status === 'ACTIVE')
      return HttpResponse.json({ status: 409, detail: 'Route is already active' }, { status: 409 })
    const updated: RouteDto = {
      ...route,
      status: 'ACTIVE',
      version: route.version + 1,
      activatedAt: new Date().toISOString(),
      updatedAt: new Date().toISOString(),
    }
    routes.set(route.id, updated)
    return HttpResponse.json(updated)
  }),

  // ─── Deactivate ──────────────────────────────────────────────────────────────
  http.post(`${BASE}/:id/deactivate`, async ({ params }) => {
    await delay(500)
    const route = routes.get(params.id as string)
    if (!route) return HttpResponse.json({ status: 404, detail: 'Route not found' }, { status: 404 })
    const updated: RouteDto = { ...route, status: 'DISABLED', updatedAt: new Date().toISOString() }
    routes.set(route.id, updated)
    return HttpResponse.json(updated)
  }),

  // ─── Clone ────────────────────────────────────────────────────────────────────
  http.post(`${BASE}/:id/clone`, async ({ params }) => {
    await delay(400)
    const route = routes.get(params.id as string)
    if (!route) return HttpResponse.json({ status: 404, detail: 'Route not found' }, { status: 404 })
    const now = new Date().toISOString()
    const clone: RouteDto = {
      ...route,
      id: genId(),
      name: `${route.name} (Copy)`,
      status: 'DRAFT',
      version: 1,
      filters: [...route.filters],
      createdAt: now,
      updatedAt: now,
      activatedAt: undefined,
    }
    routes.set(clone.id, clone)
    return HttpResponse.json(clone, { status: 201 })
  }),

  // ─── Promote ──────────────────────────────────────────────────────────────────
  http.post(`${BASE}/:id/promote`, async ({ params }) => {
    await delay(500)
    const staging = routes.get(params.id as string)
    if (!staging) return HttpResponse.json({ status: 404, detail: 'Route not found' }, { status: 404 })
    if ((staging.environment ?? 'PRODUCTION') !== 'STAGING')
      return HttpResponse.json({ status: 400, detail: 'Only STAGING routes can be promoted' }, { status: 400 })
    if (staging.status !== 'ACTIVE')
      return HttpResponse.json({ status: 400, detail: 'Only ACTIVE staging routes can be promoted' }, { status: 400 })

    // Find or create production counterpart
    const existingProd = Array.from(routes.values()).find(
      (r) => r.name === staging.name && (r.environment ?? 'PRODUCTION') === 'PRODUCTION',
    )
    const now = new Date().toISOString()
    const production: RouteDto = existingProd
      ? {
          ...existingProd,
          pathPattern: staging.pathPattern,
          methods: staging.methods,
          upstreamUri: staging.upstreamUri,
          stripPrefix: staging.stripPrefix,
          description: staging.description,
          filters: [...staging.filters],
          version: existingProd.version + 1,
          status: 'ACTIVE',
          activatedAt: now,
          updatedAt: now,
        }
      : {
          ...staging,
          id: genId(),
          environment: 'PRODUCTION',
          status: 'ACTIVE',
          version: 1,
          activatedAt: now,
          createdAt: now,
          updatedAt: now,
        }
    routes.set(production.id, production)

    // Archive staging
    routes.set(staging.id, { ...staging, status: 'ARCHIVED', updatedAt: now })

    return HttpResponse.json({ status: 'ACCEPTED', message: 'Route promotion in progress' }, { status: 202 })
  }),

  // ─── Attach filter ────────────────────────────────────────────────────────────
  http.post(`${BASE}/:routeId/filters`, async ({ params, request }) => {
    await delay(350)
    const route = routes.get(params.routeId as string)
    if (!route) return HttpResponse.json({ status: 404, detail: 'Route not found' }, { status: 404 })
    const body = (await request.json()) as AttachFilterRequest
    const filter = filters.get(body.filterId)
    if (!filter) return HttpResponse.json({ status: 404, detail: 'Filter not found' }, { status: 404 })
    // Remove existing attachment for same filterId if any
    const existing = route.filters.filter((f) => f.filterId !== body.filterId)
    const updated: RouteDto = {
      ...route,
      filters: [
        ...existing,
        {
          filterId: filter.id,
          filterName: filter.name,
          filterType: filter.filterType,
          order: body.order,
          phase: body.phase,
          enabled: true,
        },
      ],
      updatedAt: new Date().toISOString(),
    }
    routes.set(route.id, updated)
    return HttpResponse.json(updated)
  }),

  // ─── Detach filter ────────────────────────────────────────────────────────────
  http.delete(`${BASE}/:routeId/filters/:filterId`, async ({ params }) => {
    await delay(300)
    const route = routes.get(params.routeId as string)
    if (!route) return HttpResponse.json({ status: 404, detail: 'Route not found' }, { status: 404 })
    const updated: RouteDto = {
      ...route,
      filters: route.filters.filter((f) => f.filterId !== params.filterId),
      updatedAt: new Date().toISOString(),
    }
    routes.set(route.id, updated)
    return HttpResponse.json(updated)
  }),
]
