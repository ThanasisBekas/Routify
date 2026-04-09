import { http, HttpResponse, delay } from 'msw'
import { users, buildPage, MOCK_TENANT_ID } from '../db'
import type { UserDto, CreateUserRequest, UserRole } from '../../types'

function genId() {
  return `bbbbbbbb-mock-${Date.now()}-${Math.random().toString(36).slice(2, 7)}`
}

const BASE = '/api/v1/admin/users'

export const userHandlers = [
  // ─── List ────────────────────────────────────────────────────────────────────
  http.get(BASE, async ({ request }) => {
    await delay(200)
    const url = new URL(request.url)
    const page = parseInt(url.searchParams.get('page') ?? '0', 10)
    const size = parseInt(url.searchParams.get('size') ?? '20', 10)
    const items = Array.from(users.values()).sort((a, b) => b.createdAt.localeCompare(a.createdAt))
    return HttpResponse.json(buildPage(items, page, size))
  }),

  // ─── Get single ──────────────────────────────────────────────────────────────
  http.get(`${BASE}/:id`, async ({ params }) => {
    await delay(150)
    const user = users.get(params.id as string)
    if (!user) return HttpResponse.json({ status: 404, detail: 'User not found' }, { status: 404 })
    return HttpResponse.json(user)
  }),

  // ─── Create ──────────────────────────────────────────────────────────────────
  http.post(BASE, async ({ request }) => {
    await delay(400)
    const body = (await request.json()) as CreateUserRequest
    // Check for duplicate username
    const duplicate = Array.from(users.values()).find((u) => u.username === body.username)
    if (duplicate)
      return HttpResponse.json(
        { status: 409, detail: `Username '${body.username}' is already taken.` },
        { status: 409 },
      )
    const now = new Date().toISOString()
    const tenantId = request.headers.get('X-Tenant-Id') || MOCK_TENANT_ID
    const user: UserDto = {
      id: genId(),
      tenantId,
      username: body.username,
      email: body.email,
      role: body.role,
      roleId: body.roleId,
      status: 'ACTIVE',
      mustChangePassword: true,
      createdAt: now,
    }
    users.set(user.id, user)
    return HttpResponse.json(user, { status: 201 })
  }),

  // ─── Update role ──────────────────────────────────────────────────────────────
  http.put(`${BASE}/:id`, async ({ params, request }) => {
    await delay(300)
    const user = users.get(params.id as string)
    if (!user) return HttpResponse.json({ status: 404, detail: 'User not found' }, { status: 404 })
    const body = (await request.json()) as { role?: UserRole; roleId?: string }
    const updated: UserDto = {
      ...user,
      ...(body.role && { role: body.role }),
      ...(body.roleId && { roleId: body.roleId }),
    }
    users.set(user.id, updated)
    return HttpResponse.json(updated)
  }),

  // ─── Delete ──────────────────────────────────────────────────────────────────
  http.delete(`${BASE}/:id`, async ({ params }) => {
    await delay(300)
    if (!users.has(params.id as string))
      return HttpResponse.json({ status: 404, detail: 'User not found' }, { status: 404 })
    users.delete(params.id as string)
    return new HttpResponse(null, { status: 204 })
  }),

  // ─── Reset password ───────────────────────────────────────────────────────────
  http.post(`${BASE}/:id/reset-password`, async ({ params }) => {
    await delay(300)
    const user = users.get(params.id as string)
    if (!user) return HttpResponse.json({ status: 404, detail: 'User not found' }, { status: 404 })
    const updated: UserDto = { ...user, mustChangePassword: true }
    users.set(user.id, updated)
    return HttpResponse.json({
      success: true,
      message: `Password reset for ${user.username}. User must change it on next login.`,
    })
  }),
]
