import type { UserDto } from '../../types'
import { MOCK_TENANT_ID, MOCK_TENANT_2_ID } from './tenants'

export const MOCK_USER_ADMIN_ID = 'bbbbbbbb-0000-0000-0000-000000000001'
export const MOCK_USER_VIEWER_ID = 'bbbbbbbb-0000-0000-0000-000000000002'
export const MOCK_USER_OPS_ID = 'bbbbbbbb-0000-0000-0000-000000000003'
export const MOCK_USER_ADM2_ID = 'bbbbbbbb-0000-0000-0000-000000000004'
export const MOCK_USER_OPS2_ID = 'bbbbbbbb-0000-0000-0000-000000000005'

export const seedUsers: UserDto[] = [
  {
    id: MOCK_USER_ADMIN_ID,
    tenantId: MOCK_TENANT_ID,
    username: 'admin',
    email: 'admin@routify.demo',
    role: 'SUPER_ADMIN',
    status: 'ACTIVE',
    mustChangePassword: false,
    lastLoginAt: '2026-04-04T09:00:00Z',
    createdAt: '2025-01-01T10:00:00Z',
  },
  {
    id: MOCK_USER_VIEWER_ID,
    tenantId: MOCK_TENANT_ID,
    username: 'viewer',
    email: 'viewer@routify.demo',
    role: 'VIEWER',
    status: 'ACTIVE',
    lastLoginAt: '2026-04-03T14:00:00Z',
    createdAt: '2025-02-01T08:00:00Z',
  },
  {
    id: MOCK_USER_OPS_ID,
    tenantId: MOCK_TENANT_ID,
    username: 'operator',
    email: 'ops@routify.demo',
    role: 'OPERATOR',
    status: 'ACTIVE',
    lastLoginAt: '2026-04-04T07:30:00Z',
    createdAt: '2025-03-01T09:00:00Z',
  },
  {
    id: MOCK_USER_ADM2_ID,
    tenantId: MOCK_TENANT_2_ID,
    username: 'acme_admin',
    email: 'admin@acme.example',
    role: 'TENANT_ADMIN',
    status: 'ACTIVE',
    lastLoginAt: '2026-04-01T10:00:00Z',
    createdAt: '2025-02-15T11:00:00Z',
  },
  {
    id: MOCK_USER_OPS2_ID,
    tenantId: MOCK_TENANT_ID,
    username: 'new_user',
    email: 'new@routify.demo',
    role: 'VIEWER',
    status: 'ACTIVE',
    mustChangePassword: true,
    createdAt: '2026-04-04T08:00:00Z',
  },
]
