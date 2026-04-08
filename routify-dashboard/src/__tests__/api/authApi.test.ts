import { describe, it, expect, beforeEach, afterEach } from 'vitest'
import { authApi } from '../../api/authApi'
import { useAuthStore } from '../../store/authStore'
import { installMockAdapter, restoreMockAdapter, mockAdapter } from '../helpers/testUtils'

describe('authApi', () => {
  beforeEach(() => {
    useAuthStore.getState().logout()
    installMockAdapter()
  })
  afterEach(() => {
    restoreMockAdapter()
    useAuthStore.getState().logout()
  })

  it('login sends POST with username, password, tenantSlug', async () => {
    let capturedUrl: string | undefined
    let capturedBody: unknown
    mockAdapter(async (config) => {
      capturedUrl = config.url
      capturedBody = JSON.parse(config.data as string)
      return { status: 200, data: { accessToken: 'jwt', user: { id: 'u1' } } }
    })

    const result = await authApi.login('admin', 'pass', 'acme')
    expect(capturedUrl).toBe('/api/v1/auth/login')
    expect(capturedBody).toEqual({ username: 'admin', password: 'pass', tenantSlug: 'acme' })
    expect(result.accessToken).toBe('jwt')
  })

  it('refresh sends POST with empty body', async () => {
    let capturedBody: unknown
    mockAdapter(async (config) => {
      capturedBody = JSON.parse(config.data as string)
      return { status: 200, data: { accessToken: 'new-jwt' } }
    })

    const result = await authApi.refresh()
    expect(capturedBody).toEqual({})
    expect(result.accessToken).toBe('new-jwt')
  })

  it('logout swallows errors', async () => {
    mockAdapter(async () => {
      throw new Error('Network error')
    })

    // Should not throw
    await expect(authApi.logout()).resolves.toBeUndefined()
  })

  it('changePassword sends correct payload', async () => {
    let capturedBody: unknown
    mockAdapter(async (config) => {
      capturedBody = JSON.parse(config.data as string)
      return { status: 200, data: { success: true, message: 'Password changed' } }
    })

    const result = await authApi.changePassword('u1', 'old', 'new')
    expect(capturedBody).toEqual({ userId: 'u1', currentPassword: 'old', newPassword: 'new' })
    expect(result.success).toBe(true)
  })
})

