import { describe, it, expect, beforeEach, afterEach, vi } from 'vitest'
import { renderHook, waitFor } from '@testing-library/react'
import { useBootstrapAuth } from '../../hooks/useBootstrapAuth'
import { useAuthStore } from '../../store/authStore'
import { installMockAdapter, restoreMockAdapter, mockAdapter } from '../helpers/testUtils'

describe('useBootstrapAuth', () => {
  beforeEach(() => {
    useAuthStore.getState().logout()
    installMockAdapter()
  })
  afterEach(() => {
    restoreMockAdapter()
    useAuthStore.getState().logout()
  })

  it('short-circuits when access token already present', async () => {
    useAuthStore.getState().setTokens('existing-token')
    const refreshSpy = vi.fn()
    mockAdapter(async () => {
      refreshSpy()
      return { status: 200, data: { accessToken: 'should-not-be-used' } }
    })

    const { result } = renderHook(() => useBootstrapAuth())

    await waitFor(() => {
      expect(result.current).toBe(true)
    })
    // refresh should not have been called
    expect(refreshSpy).not.toHaveBeenCalled()
  })

  it('calls refresh and sets tokens when no access token', async () => {
    mockAdapter(async (config) => {
      if (config.url?.includes('/auth/refresh')) {
        return {
          status: 200,
          data: {
            accessToken: 'new-token',
            user: { id: 'u1', tenantId: 't1', username: 'admin', email: 'a@b.com', role: 'SUPER_ADMIN' },
          },
        }
      }
      return { status: 200, data: {} }
    })

    const { result } = renderHook(() => useBootstrapAuth())

    await waitFor(() => {
      expect(result.current).toBe(true)
    })
    expect(useAuthStore.getState().accessToken).toBe('new-token')
    expect(useAuthStore.getState().user?.username).toBe('admin')
  })

  it('sets bootstrapped to true even when refresh fails', async () => {
    mockAdapter(async () => {
      throw new Error('No valid session')
    })

    const { result } = renderHook(() => useBootstrapAuth())

    await waitFor(() => {
      expect(result.current).toBe(true)
    })
    expect(useAuthStore.getState().accessToken).toBeNull()
    expect(useAuthStore.getState().isAuthenticated).toBe(false)
  })

  it('sets user when refresh response includes user', async () => {
    mockAdapter(async (config) => {
      if (config.url?.includes('/auth/refresh')) {
        return {
          status: 200,
          data: {
            accessToken: 'jwt',
            user: { id: 'u2', tenantId: 't2', username: 'viewer', email: 'v@b.com', role: 'VIEWER' },
          },
        }
      }
      return { status: 200, data: {} }
    })

    const { result } = renderHook(() => useBootstrapAuth())

    await waitFor(() => {
      expect(result.current).toBe(true)
    })
    expect(useAuthStore.getState().user?.role).toBe('VIEWER')
  })
})

