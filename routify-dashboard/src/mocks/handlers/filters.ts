import { http, HttpResponse, delay } from 'msw'
import { filters, buildPage, MOCK_TENANT_ID } from '../db'
import type { FilterDefinitionDto, FilterSummary, CreateFilterRequest, UpdateFilterRequest } from '../../types'

function toSummary(f: FilterDefinitionDto): FilterSummary {
  return {
    id: f.id,
    name: f.name,
    filterType: f.filterType,
    enabled: f.enabled,
    usageCount: f.usageCount,
    createdAt: f.createdAt,
  }
}

function genId() {
  return `cccccccc-mock-${Date.now()}-${Math.random().toString(36).slice(2, 7)}`
}

const BASE = '/api/v1/admin/filters'

export const filterHandlers = [
  // ─── List ────────────────────────────────────────────────────────────────────
  http.get(BASE, async ({ request }) => {
    await delay(200)
    const url = new URL(request.url)
    const page = parseInt(url.searchParams.get('page') ?? '0', 10)
    const size = parseInt(url.searchParams.get('size') ?? '20', 10)
    const items = Array.from(filters.values()).sort((a, b) => b.createdAt.localeCompare(a.createdAt))
    return HttpResponse.json(buildPage(items.map(toSummary), page, size))
  }),

  // ─── Get single ──────────────────────────────────────────────────────────────
  http.get(`${BASE}/:id`, async ({ params }) => {
    await delay(150)
    const filter = filters.get(params.id as string)
    if (!filter) return HttpResponse.json({ status: 404, detail: 'Filter not found' }, { status: 404 })
    return HttpResponse.json(filter)
  }),

  // ─── Create ──────────────────────────────────────────────────────────────────
  http.post(BASE, async ({ request }) => {
    await delay(400)
    const body = (await request.json()) as CreateFilterRequest
    const now = new Date().toISOString()
    const filter: FilterDefinitionDto = {
      id: genId(),
      tenantId: MOCK_TENANT_ID,
      name: body.name,
      description: body.description,
      filterType: body.filterType,
      config: body.config ?? {},
      systemManaged: false,
      enabled: true,
      usageCount: 0,
      createdBy: 'admin',
      createdAt: now,
      updatedAt: now,
    }
    filters.set(filter.id, filter)
    return HttpResponse.json(filter, { status: 201 })
  }),

  // ─── Update ──────────────────────────────────────────────────────────────────
  http.put(`${BASE}/:id`, async ({ params, request }) => {
    await delay(350)
    const filter = filters.get(params.id as string)
    if (!filter) return HttpResponse.json({ status: 404, detail: 'Filter not found' }, { status: 404 })
    const body = (await request.json()) as UpdateFilterRequest
    const updated: FilterDefinitionDto = {
      ...filter,
      ...(body.name !== undefined && { name: body.name }),
      ...(body.description !== undefined && { description: body.description }),
      ...(body.config !== undefined && { config: body.config }),
      updatedAt: new Date().toISOString(),
    }
    filters.set(filter.id, updated)
    return HttpResponse.json(updated)
  }),

  // ─── Delete ──────────────────────────────────────────────────────────────────
  http.delete(`${BASE}/:id`, async ({ params }) => {
    await delay(300)
    if (!filters.has(params.id as string))
      return HttpResponse.json({ status: 404, detail: 'Filter not found' }, { status: 404 })
    filters.delete(params.id as string)
    return new HttpResponse(null, { status: 204 })
  }),
]
