import type { TenantDto } from '../../types'

export const MOCK_TENANT_ID = 'aaaaaaaa-0000-0000-0000-000000000001'
export const MOCK_TENANT_2_ID = 'aaaaaaaa-0000-0000-0000-000000000002'
export const MOCK_TENANT_3_ID = 'aaaaaaaa-0000-0000-0000-000000000003'

export const seedTenants: TenantDto[] = [
  {
    id: MOCK_TENANT_ID,
    name: 'Routify Demo',
    slug: 'routify',
    status: 'ACTIVE',
    plan: 'PRO',
    contactEmail: 'admin@routify.demo',
    createdAt: '2025-01-01T10:00:00Z',
  },
  {
    id: MOCK_TENANT_2_ID,
    name: 'Acme Corp',
    slug: 'acme',
    status: 'ACTIVE',
    plan: 'ENTERPRISE',
    contactEmail: 'ops@acme.example',
    createdAt: '2025-02-14T08:30:00Z',
  },
  {
    id: MOCK_TENANT_3_ID,
    name: 'Sandbox Org',
    slug: 'sandbox',
    status: 'SUSPENDED',
    plan: 'FREE',
    contactEmail: 'dev@sandbox.test',
    createdAt: '2025-03-20T14:00:00Z',
  },
]

