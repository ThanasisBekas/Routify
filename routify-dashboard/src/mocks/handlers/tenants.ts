import { http, HttpResponse, delay } from 'msw'
import { tenants, buildPage } from '../db'
import type { TenantDto, TenantPlan } from '../../types'
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

  // ─── Reactivate ──────────────────────────────────────────────────────────────
  http.post('/api/v1/admin/tenants/:id/reactivate', async ({ params }) => {
    await delay(400)
    const tenant = tenants.get(params.id as string)
    if (!tenant) return HttpResponse.json({ status: 404, detail: 'Tenant not found' }, { status: 404 })
    const updated: TenantDto = { ...tenant, status: 'ACTIVE' }
    tenants.set(tenant.id, updated)
    return HttpResponse.json(updated)
  }),
]
