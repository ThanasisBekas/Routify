import { http, HttpResponse, delay } from 'msw'
import { tenants, buildPage } from '../db'
import type { TenantDto, TenantPlan, TenantUsageCurrent, TenantUsageHistory, DailyUsage } from '../../types'
import type { WorkspaceOption, CreateWorkspaceRequest, UpdateWorkspaceRequest } from '../../api/tenantsApi'

function genId() {
  return `aaaaaaaa-mock-${Date.now()}-${Math.random().toString(36).slice(2, 7)}`
}

export const tenantHandlers = [
  // ─── Public: workspaces list for login dropdown ──────────────────────────────
  http.get('/api/v1/admin/tenants/workspaces', async () => {
    await delay(150)
    const active = Array.from(tenants.values()).filter((t) => t.status === 'ACTIVE')
    const workspaces: WorkspaceOption[] = active.map((t) => ({ name: t.name, slug: t.slug }))
    return HttpResponse.json({ workspaces })
  }),

  // ─── List (paginated) ────────────────────────────────────────────────────────
  http.get('/api/v1/admin/tenants', async ({ request }) => {
    await delay(200)
    const url = new URL(request.url)
    const page = parseInt(url.searchParams.get('page') ?? '0', 10)
    const size = parseInt(url.searchParams.get('size') ?? '20', 10)
    const items = Array.from(tenants.values()).sort((a, b) => b.createdAt.localeCompare(a.createdAt))
    return HttpResponse.json(buildPage(items, page, size))
  }),

  // ─── Get single ──────────────────────────────────────────────────────────────
  http.get('/api/v1/admin/tenants/:id', async ({ params }) => {
    await delay(150)
    const tenant = tenants.get(params.id as string)
    if (!tenant) return HttpResponse.json({ status: 404, detail: 'Tenant not found' }, { status: 404 })
    return HttpResponse.json(tenant)
  }),

  // ─── Create workspace ────────────────────────────────────────────────────────
  http.post('/api/v1/admin/tenants', async ({ request }) => {
    await delay(500)
    const body = (await request.json()) as CreateWorkspaceRequest
    const dup = Array.from(tenants.values()).find((t) => t.slug === body.slug)
    if (dup)
      return HttpResponse.json(
        { status: 409, detail: `Workspace slug '${body.slug}' already exists.` },
        { status: 409 },
      )
    const now = new Date().toISOString()
    const tenant: TenantDto = {
      id: genId(),
      name: body.name,
      slug: body.slug,
      status: 'ACTIVE',
      plan: body.plan as TenantPlan,
      contactEmail: body.contactEmail,
      createdAt: now,
    }
    tenants.set(tenant.id, tenant)
    return HttpResponse.json(tenant, { status: 201 })
  }),

  // ─── Update workspace ────────────────────────────────────────────────────────
  http.put('/api/v1/admin/tenants/:id', async ({ params, request }) => {
    await delay(350)
    const tenant = tenants.get(params.id as string)
    if (!tenant) return HttpResponse.json({ status: 404, detail: 'Tenant not found' }, { status: 404 })
    const body = (await request.json()) as UpdateWorkspaceRequest
    const updated: TenantDto = {
      ...tenant,
      ...(body.name !== undefined && { name: body.name }),
      ...(body.plan !== undefined && { plan: body.plan as TenantPlan }),
      ...(body.contactEmail !== undefined && { contactEmail: body.contactEmail }),
    }
    tenants.set(tenant.id, updated)
    return HttpResponse.json(updated)
  }),

  // ─── Suspend ─────────────────────────────────────────────────────────────────
  http.post('/api/v1/admin/tenants/:id/suspend', async ({ params }) => {
    await delay(400)
    const tenant = tenants.get(params.id as string)
    if (!tenant) return HttpResponse.json({ status: 404, detail: 'Tenant not found' }, { status: 404 })
    const updated: TenantDto = { ...tenant, status: 'SUSPENDED' }
    tenants.set(tenant.id, updated)
    return HttpResponse.json(updated)
  }),

  // ─── Reactivate ──────────────────────────────────────────────────────────
  http.post('/api/v1/admin/tenants/:id/reactivate', async ({ params }) => {
    await delay(400)
    const tenant = tenants.get(params.id as string)
    if (!tenant) return HttpResponse.json({ status: 404, detail: 'Tenant not found' }, { status: 404 })
    const updated: TenantDto = { ...tenant, status: 'ACTIVE' }
    tenants.set(tenant.id, updated)
    return HttpResponse.json(updated)
  }),

  // ─── Usage (current period vs plan limits) ────────────────────────────────
  http.get('/api/v1/admin/tenants/:id/usage', async ({ params }) => {
    await delay(250)
    const tenant = tenants.get(params.id as string)
    if (!tenant) return HttpResponse.json({ status: 404, detail: 'Tenant not found' }, { status: 404 })

    const planLimits: Record<TenantPlan, { routes: number; filters: number; requests: number }> = {
      FREE: { routes: 10, filters: 5, requests: 1000 },
      STARTER: { routes: 50, filters: 20, requests: 10000 },
      PRO: { routes: 200, filters: 100, requests: 100000 },
      ENTERPRISE: { routes: 2147483647, filters: 2147483647, requests: 2147483647 },
    }
    const limits = planLimits[tenant.plan]
    const routesUsed = Math.floor(Math.random() * Math.min(15, limits.routes))
    const filtersUsed = Math.floor(Math.random() * Math.min(8, limits.filters))
    const requestsUsed = Math.floor(Math.random() * Math.min(800, limits.requests))

    const now = new Date()
    const periodStart = `${now.getFullYear()}-${String(now.getMonth() + 1).padStart(2, '0')}-01`
    const lastDay = new Date(now.getFullYear(), now.getMonth() + 1, 0).getDate()
    const periodEnd = `${now.getFullYear()}-${String(now.getMonth() + 1).padStart(2, '0')}-${lastDay}`

    const usage: TenantUsageCurrent = {
      tenantId: tenant.id,
      plan: tenant.plan,
      routes: { used: routesUsed, limit: limits.routes, percentage: Math.round((routesUsed / limits.routes) * 100) },
      filters: {
        used: filtersUsed,
        limit: limits.filters,
        percentage: Math.round((filtersUsed / limits.filters) * 100),
      },
      requests: {
        used: requestsUsed,
        limit: limits.requests,
        percentage: Math.round((requestsUsed / limits.requests) * 100),
      },
      periodStart,
      periodEnd,
    }
    return HttpResponse.json(usage)
  }),

  // ─── Usage History (daily trend) ──────────────────────────────────────────
  http.get('/api/v1/admin/tenants/:id/usage/history', async ({ params, request }) => {
    await delay(300)
    const tenant = tenants.get(params.id as string)
    if (!tenant) return HttpResponse.json({ status: 404, detail: 'Tenant not found' }, { status: 404 })

    const url = new URL(request.url)
    const days = parseInt(url.searchParams.get('days') ?? '30', 10)

    const entries: DailyUsage[] = []
    for (let i = days; i >= 1; i--) {
      const d = new Date()
      d.setDate(d.getDate() - i)
      entries.push({
        date: d.toISOString().slice(0, 10),
        routeCount: Math.floor(Math.random() * 12),
        filterCount: Math.floor(Math.random() * 6),
        requestCount: Math.floor(Math.random() * 500 + 50),
        errorCount: Math.floor(Math.random() * 20),
      })
    }

    const history: TenantUsageHistory = { tenantId: tenant.id, entries }
    return HttpResponse.json(history)
  }),
]
