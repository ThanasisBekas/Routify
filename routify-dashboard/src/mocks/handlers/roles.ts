import { http, HttpResponse } from 'msw'

const BUILT_IN_ROLES = [
  {
    id: '00000000-0000-0000-0000-000000000001',
    tenantId: null,
    name: 'SUPER_ADMIN',
    description: 'Full platform access',
    builtIn: true,
    permissions: [
      'ROUTES_READ', 'ROUTES_WRITE', 'ROUTES_ACTIVATE', 'ROUTES_DELETE', 'ROUTES_PROMOTE',
      'FILTERS_READ', 'FILTERS_WRITE', 'FILTERS_DELETE',
      'USERS_READ', 'USERS_WRITE', 'USERS_DELETE',
      'CERTS_READ', 'CERTS_WRITE', 'CERTS_ADMIN',
      'AUDIT_READ', 'AUDIT_REPLAY',
      'GATEWAY_CONFIG_READ', 'GATEWAY_CONFIG_WRITE',
      'API_KEYS_READ', 'API_KEYS_ADMIN',
      'WEBHOOKS_READ', 'WEBHOOKS_ADMIN',
      'AI_POLICY_READ', 'AI_POLICY_WRITE',
      'TENANTS_READ', 'TENANTS_WRITE', 'TENANTS_SUSPEND',
    ],
    createdAt: '2025-01-01T00:00:00Z',
  },
  {
    id: '00000000-0000-0000-0000-000000000002',
    tenantId: null,
    name: 'TENANT_ADMIN',
    description: 'Full tenant access',
    builtIn: true,
    permissions: [
      'ROUTES_READ', 'ROUTES_WRITE', 'ROUTES_ACTIVATE', 'ROUTES_DELETE', 'ROUTES_PROMOTE',
      'FILTERS_READ', 'FILTERS_WRITE', 'FILTERS_DELETE',
      'USERS_READ', 'USERS_WRITE', 'USERS_DELETE',
      'CERTS_READ', 'CERTS_WRITE', 'CERTS_ADMIN',
      'AUDIT_READ', 'AUDIT_REPLAY',
      'GATEWAY_CONFIG_READ', 'GATEWAY_CONFIG_WRITE',
      'API_KEYS_READ', 'API_KEYS_ADMIN',
      'WEBHOOKS_READ', 'WEBHOOKS_ADMIN',
      'AI_POLICY_READ', 'AI_POLICY_WRITE',
    ],
    createdAt: '2025-01-01T00:00:00Z',
  },
  {
    id: '00000000-0000-0000-0000-000000000003',
    tenantId: null,
    name: 'OPERATOR',
    description: 'Route operations',
    builtIn: true,
    permissions: ['ROUTES_READ', 'ROUTES_ACTIVATE', 'FILTERS_READ'],
    createdAt: '2025-01-01T00:00:00Z',
  },
  {
    id: '00000000-0000-0000-0000-000000000004',
    tenantId: null,
    name: 'VIEWER',
    description: 'Read-only access',
    builtIn: true,
    permissions: [
      'ROUTES_READ', 'FILTERS_READ', 'USERS_READ', 'CERTS_READ',
      'AUDIT_READ', 'GATEWAY_CONFIG_READ', 'API_KEYS_READ', 'WEBHOOKS_READ', 'AI_POLICY_READ',
    ],
    createdAt: '2025-01-01T00:00:00Z',
  },
]

const ALL_PERMISSIONS = [
  'ROUTES_READ', 'ROUTES_WRITE', 'ROUTES_ACTIVATE', 'ROUTES_DELETE', 'ROUTES_PROMOTE',
  'FILTERS_READ', 'FILTERS_WRITE', 'FILTERS_DELETE',
  'USERS_READ', 'USERS_WRITE', 'USERS_DELETE',
  'CERTS_READ', 'CERTS_WRITE', 'CERTS_ADMIN',
  'AUDIT_READ', 'AUDIT_REPLAY',
  'GATEWAY_CONFIG_READ', 'GATEWAY_CONFIG_WRITE',
  'API_KEYS_READ', 'API_KEYS_ADMIN',
  'WEBHOOKS_READ', 'WEBHOOKS_ADMIN',
  'AI_POLICY_READ', 'AI_POLICY_WRITE',
  'TENANTS_READ', 'TENANTS_WRITE', 'TENANTS_SUSPEND',
]

let customRoles: typeof BUILT_IN_ROLES = []

export const roleHandlers = [
  http.get('/api/v1/admin/roles', () => {
    const all = [...BUILT_IN_ROLES, ...customRoles]
    return HttpResponse.json({
      content: all,
      totalElements: all.length,
      totalPages: 1,
      page: 0,
      size: 50,
      first: true,
      last: true,
    })
  }),

  http.get('/api/v1/admin/roles/permissions', () => {
    return HttpResponse.json(ALL_PERMISSIONS)
  }),

  http.get('/api/v1/admin/roles/:id', ({ params }) => {
    const all = [...BUILT_IN_ROLES, ...customRoles]
    const role = all.find((r) => r.id === params.id)
    if (!role) return new HttpResponse(null, { status: 404 })
    return HttpResponse.json(role)
  }),

  http.post('/api/v1/admin/roles', async ({ request }) => {
    const body = (await request.json()) as { name: string; description?: string; permissions: string[] }
    const newRole = {
      id: crypto.randomUUID(),
      tenantId: 'mock-tenant',
      name: body.name,
      description: body.description ?? null,
      builtIn: false,
      permissions: body.permissions,
      createdAt: new Date().toISOString(),
    }
    customRoles.push(newRole)
    return HttpResponse.json(newRole, { status: 201 })
  }),

  http.put('/api/v1/admin/roles/:id', async ({ params, request }) => {
    const body = (await request.json()) as { name?: string; description?: string; permissions?: string[] }
    const all = [...BUILT_IN_ROLES, ...customRoles]
    const role = all.find((r) => r.id === params.id)
    if (!role) return new HttpResponse(null, { status: 404 })
    if (body.permissions) role.permissions = body.permissions
    if (body.description !== undefined) role.description = body.description
    return HttpResponse.json(role)
  }),

  http.delete('/api/v1/admin/roles/:id', ({ params }) => {
    customRoles = customRoles.filter((r) => r.id !== params.id)
    return new HttpResponse(null, { status: 204 })
  }),
]

