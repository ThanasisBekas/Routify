import { http, HttpResponse, delay } from 'msw'
import type { ApiKeyDto, CreateApiKeyRequest, ApiKeyCreatedResponse, ApiKeyDetailDto } from '../../types'

function genId() {
  return `aaaaaaaa-mock-${Date.now()}-${Math.random().toString(36).slice(2, 7)}`
}

const MOCK_TENANT = '00000000-0000-0000-0000-000000000001'

// In-memory mock store
const apiKeys = new Map<string, ApiKeyDto>()

// Seed some mock keys
const seedKeys: ApiKeyDto[] = [
  {
    id: genId(),
    tenantId: MOCK_TENANT,
    name: 'CI Pipeline',
    keyPrefix: 'rtfy_ci12ab',
    role: 'OPERATOR',
    email: 'ci@example.com',
    status: 'ACTIVE',
    expiresAt: new Date(Date.now() + 30 * 24 * 3600_000).toISOString(),
    createdAt: new Date(Date.now() - 7 * 24 * 3600_000).toISOString(),
  },
  {
    id: genId(),
    tenantId: MOCK_TENANT,
    name: 'Partner API',
    keyPrefix: 'rtfy_pa34cd',
    role: 'VIEWER',
    status: 'ACTIVE',
    createdAt: new Date(Date.now() - 14 * 24 * 3600_000).toISOString(),
  },
  {
    id: genId(),
    tenantId: MOCK_TENANT,
    name: 'Old Key (revoked)',
    keyPrefix: 'rtfy_ol56ef',
    role: 'OPERATOR',
    status: 'REVOKED',
    createdAt: new Date(Date.now() - 60 * 24 * 3600_000).toISOString(),
  },
]
seedKeys.forEach((k) => apiKeys.set(k.id, k))

const BASE = '/api/v1/admin/api-keys'

export const apiKeyHandlers = [
  // ─── List ──────────────────────────────────────────────────────────────────
  http.get(BASE, async ({ request }) => {
    await delay(200)
    const url = new URL(request.url)
    const page = parseInt(url.searchParams.get('page') ?? '0', 10)
    const size = parseInt(url.searchParams.get('size') ?? '20', 10)
    const items = Array.from(apiKeys.values()).sort((a, b) => b.createdAt.localeCompare(a.createdAt))
    const start = page * size
    const content = items.slice(start, start + size)
    return HttpResponse.json({
      content,
      totalElements: items.length,
      totalPages: Math.ceil(items.length / size),
      page,
      size,
      first: page === 0,
      last: start + size >= items.length,
    })
  }),

  // ─── Get ───────────────────────────────────────────────────────────────────
  http.get(`${BASE}/:id`, async ({ params }) => {
    await delay(150)
    const key = apiKeys.get(params.id as string)
    if (!key) return HttpResponse.json({ status: 404, detail: 'API key not found' }, { status: 404 })
    const detail: ApiKeyDetailDto = {
      ...key,
      userId: '00000000-0000-0000-0000-000000000099',
    }
    return HttpResponse.json(detail)
  }),

  // ─── Create ────────────────────────────────────────────────────────────────
  http.post(BASE, async ({ request }) => {
    await delay(300)
    const body = (await request.json()) as CreateApiKeyRequest
    const id = genId()
    const rawKey =
      'rtfy_' +
      Array.from({ length: 35 }, () => 'abcdefghijklmnopqrstuvwxyz0123456789'[Math.floor(Math.random() * 36)]).join('')
    const newKey: ApiKeyDto = {
      id,
      tenantId: MOCK_TENANT,
      name: body.name,
      keyPrefix: rawKey.substring(0, 12),
      role: (body.role ?? 'OPERATOR') as ApiKeyDto['role'],
      email: body.email,
      status: 'ACTIVE',
      expiresAt: body.expiresAt ? new Date(body.expiresAt).toISOString() : undefined,
      createdAt: new Date().toISOString(),
    }
    apiKeys.set(id, newKey)
    const resp: ApiKeyCreatedResponse = {
      id,
      rawKey,
      keyPrefix: newKey.keyPrefix,
      name: newKey.name,
      role: newKey.role,
      expiresAt: newKey.expiresAt,
      createdAt: newKey.createdAt,
    }
    return HttpResponse.json(resp)
  }),

  // ─── Revoke ────────────────────────────────────────────────────────────────
  http.post(`${BASE}/:id/revoke`, async ({ params }) => {
    await delay(200)
    const key = apiKeys.get(params.id as string)
    if (!key) return HttpResponse.json({ status: 404, detail: 'API key not found' }, { status: 404 })
    key.status = 'REVOKED'
    return HttpResponse.json({ ...key, userId: '00000000-0000-0000-0000-000000000099' })
  }),

  // ─── Rotate ────────────────────────────────────────────────────────────────
  http.post(`${BASE}/:id/rotate`, async ({ params }) => {
    await delay(300)
    const old = apiKeys.get(params.id as string)
    if (!old) return HttpResponse.json({ status: 404, detail: 'API key not found' }, { status: 404 })
    // Revoke old
    old.status = 'REVOKED'
    // Create new
    const id = genId()
    const rawKey =
      'rtfy_' +
      Array.from({ length: 35 }, () => 'abcdefghijklmnopqrstuvwxyz0123456789'[Math.floor(Math.random() * 36)]).join('')
    const newKey: ApiKeyDto = {
      ...old,
      id,
      keyPrefix: rawKey.substring(0, 12),
      status: 'ACTIVE',
      createdAt: new Date().toISOString(),
    }
    apiKeys.set(id, newKey)
    const resp: ApiKeyCreatedResponse = {
      id,
      rawKey,
      keyPrefix: newKey.keyPrefix,
      name: newKey.name,
      role: newKey.role,
      expiresAt: newKey.expiresAt,
      createdAt: newKey.createdAt,
    }
    return HttpResponse.json(resp)
  }),
]
