/**
 * auth handlers — login, refresh, logout, change-password
 *
 * Mock credentials:
 *   workspace: routify  |  admin / routify_admin_2025      → SUPER_ADMIN
 *   workspace: routify  |  viewer / password               → VIEWER
 *   workspace: routify  |  operator / password             → OPERATOR
 *   anything else                                          → 401
 *
 * refresh always returns the SUPER_ADMIN session so page-reload works.
 */
import { http, HttpResponse, delay } from 'msw'
import type { LoginResponse } from '../../types'
import { MOCK_TENANT_ID } from '../db'

export const MOCK_ACCESS_TOKEN = 'mock-access-token-super-admin'

/** A fake but plausible JWT payload encoded as base64url (not a real signed JWT) */
function mockToken(role: string, userId: string): string {
  const header  = btoa(JSON.stringify({ alg: 'RS256', typ: 'JWT' })).replace(/=/g, '')
  const payload = btoa(JSON.stringify({
    sub: userId, role, tenantId: MOCK_TENANT_ID,
    iss: 'routify-mock', exp: Math.floor(Date.now() / 1000) + 3600,
  })).replace(/=/g, '')
  return `${header}.${payload}.mock-signature`
}

const USERS: Record<string, { password: string; userId: string; role: string; username: string; email: string }> = {
  admin:    { password: 'routify_admin_2025', userId: 'bbbbbbbb-0000-0000-0000-000000000001', role: 'SUPER_ADMIN', username: 'admin',    email: 'admin@routify.demo'    },
  viewer:   { password: 'password',           userId: 'bbbbbbbb-0000-0000-0000-000000000002', role: 'VIEWER',      username: 'viewer',   email: 'viewer@routify.demo'   },
  operator: { password: 'password',           userId: 'bbbbbbbb-0000-0000-0000-000000000003', role: 'OPERATOR',    username: 'operator', email: 'ops@routify.demo'      },
}

function buildLoginResponse(userId: string, role: string, username: string, email: string): LoginResponse {
  return {
    accessToken:        mockToken(role, userId),
    tokenType:          'Bearer',
    expiresIn:          3600,
    mustChangePassword: false,
    user: { id: userId, tenantId: MOCK_TENANT_ID, username, email, role: role as LoginResponse['user']['role'] },
  }
}

export const authHandlers = [
  // ─── Login ──────────────────────────────────────────────────────────────────
  http.post('/api/v1/auth/login', async ({ request }) => {
    await delay(300)
    const body = await request.json() as { username?: string; password?: string; tenantSlug?: string }

    if (body.tenantSlug !== 'routify') {
      return HttpResponse.json(
        { type: 'UNAUTHORIZED', title: 'Unauthorized', status: 401, detail: 'Workspace not found.' },
        { status: 401 },
      )
    }

    const cred = USERS[body.username ?? '']
    if (!cred || cred.password !== body.password) {
      return HttpResponse.json(
        { type: 'UNAUTHORIZED', title: 'Unauthorized', status: 401, detail: 'Invalid username or password.' },
        { status: 401 },
      )
    }

    return HttpResponse.json(buildLoginResponse(cred.userId, cred.role, cred.username, cred.email))
  }),

  // ─── Refresh ─────────────────────────────────────────────────────────────────
  http.post('/api/v1/auth/refresh', async () => {
    await delay(100)
    // Always return the admin session so bootstrap auth succeeds in mock mode
    return HttpResponse.json(
      buildLoginResponse('bbbbbbbb-0000-0000-0000-000000000001', 'SUPER_ADMIN', 'admin', 'admin@routify.demo'),
    )
  }),

  // ─── Logout ──────────────────────────────────────────────────────────────────
  http.post('/api/v1/auth/logout', async () => {
    await delay(100)
    return new HttpResponse(null, { status: 200 })
  }),

  // ─── Change password ─────────────────────────────────────────────────────────
  http.post('/api/v1/auth/change-password', async ({ request }) => {
    await delay(200)
    const body = await request.json() as { currentPassword?: string; newPassword?: string }
    if (!body.currentPassword || !body.newPassword) {
      return HttpResponse.json({ success: false, message: 'Both current and new password are required.' }, { status: 400 })
    }
    return HttpResponse.json({ success: true, message: 'Password changed successfully.' })
  }),
]

