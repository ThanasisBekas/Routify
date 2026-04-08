import { describe, it, expect, beforeEach, afterEach } from 'vitest'
import { usersApi } from '../../api/usersApi'
import { useAuthStore } from '../../store/authStore'
import { installMockAdapter, restoreMockAdapter, mockAdapter } from '../helpers/testUtils'

describe('usersApi', () => {
  beforeEach(() => {
    useAuthStore.getState().logout()
    installMockAdapter()
  })
  afterEach(() => restoreMockAdapter())

  it('list sends GET with pagination', async () => {
    let capturedParams: unknown
    mockAdapter(async (config) => {
      capturedParams = config.params
      return { status: 200, data: { content: [], totalElements: 0 } }
    })

    await usersApi.list({ page: 0, size: 20 })
    expect(capturedParams).toEqual({ page: 0, size: 20 })
  })

  it('get fetches a single user', async () => {
    let capturedUrl: string | undefined
    mockAdapter(async (config) => {
      capturedUrl = config.url
      return { status: 200, data: { id: 'u1', username: 'admin' } }
    })

    const result = await usersApi.get('u1')
    expect(capturedUrl).toBe('/api/v1/admin/users/u1')
    expect(result.username).toBe('admin')
  })

  it('create sends POST without targetTenantId', async () => {
    let capturedHeaders: Record<string, unknown> | undefined
    mockAdapter(async (config) => {
      capturedHeaders = config.headers as Record<string, unknown>
      return { status: 200, data: { id: 'u-new' } }
    })

    await usersApi.create({ username: 'new-user', email: 'new@test.com', role: 'VIEWER', password: 'pw' } as never)
    // Should NOT have X-Tenant-Id override (no targetTenantId)
    expect(capturedHeaders?.['X-Tenant-Id']).toBeUndefined()
  })

  it('create with targetTenantId overrides X-Tenant-Id', async () => {
    let capturedHeaders: Record<string, unknown> | undefined
    mockAdapter(async (config) => {
      capturedHeaders = config.headers as Record<string, unknown>
      return { status: 200, data: { id: 'u-new' } }
    })

    await usersApi.create(
      { username: 'cross-tenant', email: 'x@test.com', role: 'VIEWER', password: 'pw' } as never,
      'other-tenant',
    )
    expect(capturedHeaders?.['X-Tenant-Id']).toBe('other-tenant')
  })

  it('updateRole sends PUT with role', async () => {
    let capturedBody: unknown
    mockAdapter(async (config) => {
      capturedBody = JSON.parse(config.data as string)
      return { status: 200, data: { id: 'u1', role: 'OPERATOR' } }
    })

    await usersApi.updateRole('u1', 'OPERATOR')
    expect(capturedBody).toEqual({ role: 'OPERATOR' })
  })

  it('delete sends DELETE', async () => {
    let capturedMethod: string | undefined
    mockAdapter(async (config) => {
      capturedMethod = config.method
      return { status: 200, data: {} }
    })

    await usersApi.delete('u1')
    expect(capturedMethod).toBe('delete')
  })

  it('resetPassword sends POST with newPassword', async () => {
    let capturedUrl: string | undefined
    let capturedBody: unknown
    mockAdapter(async (config) => {
      capturedUrl = config.url
      capturedBody = JSON.parse(config.data as string)
      return { status: 200, data: { success: true, message: 'Password reset' } }
    })

    const result = await usersApi.resetPassword('u1', 'new-pass')
    expect(capturedUrl).toBe('/api/v1/admin/users/u1/reset-password')
    expect(capturedBody).toEqual({ newPassword: 'new-pass' })
    expect(result.success).toBe(true)
  })
})
