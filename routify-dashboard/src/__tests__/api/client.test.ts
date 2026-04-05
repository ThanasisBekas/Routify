/**
 * Tests for the apiClient Axios interceptors:
 *  - Request interceptor: JWT injection + X-Tenant-Id header
 *  - Response interceptor: 401 → refresh lock → retry queued requests
 *
 * Strategy: We install a custom adapter on the real apiClient to intercept
 * requests at the transport layer (after all interceptors have run) and
 * simulate responses/errors without any network I/O.
 */
import { describe, it, expect, beforeEach, afterEach } from 'vitest'
import axios, { type AxiosRequestConfig } from 'axios'
import { useAuthStore } from '../../store/authStore'
import { apiClient } from '../../api/client'

// ─── Adapter helpers ──────────────────────────────────────────────────────────

type MockResponse = {
  status: number
  data?: unknown
  headers?: Record<string, string>
}

type AdapterHandler = (config: AxiosRequestConfig) => Promise<MockResponse>

let adapterHandler: AdapterHandler

function mockAdapter(handler: AdapterHandler) {
  adapterHandler = handler
}

// Save and restore the original adapter
const originalAdapter = apiClient.defaults.adapter

// ─── Test suite ───────────────────────────────────────────────────────────────

describe('apiClient interceptors', () => {
  beforeEach(() => {
    // Reset the auth store to a clean state
    useAuthStore.getState().logout()

    // Install mock adapter — this sits below all interceptors, so we see
    // the final merged headers that the request interceptor produced.
    apiClient.defaults.adapter = async (config) => {
      const response = await adapterHandler(config as AxiosRequestConfig)
      return {
        data: response.data ?? {},
        status: response.status,
        statusText: response.status === 200 ? 'OK' : 'Error',
        headers: response.headers ?? {},
        config,
      } as never
    }
  })

  afterEach(() => {
    apiClient.defaults.adapter = originalAdapter
    useAuthStore.getState().logout()
  })

  // ── Request interceptor ──────────────────────────────────────────────────

  describe('request interceptor', () => {
    it('injects Authorization header when access token is present', async () => {
      useAuthStore.getState().setTokens('my-jwt-token')

      let authHeader: string | undefined
      mockAdapter(async (config) => {
        authHeader = config.headers?.['Authorization'] as string | undefined
        return { status: 200, data: { ok: true } }
      })

      await apiClient.get('/api/v1/admin/routes')
      expect(authHeader).toBe('Bearer my-jwt-token')
    })

    it('does not inject Authorization header when access token is null', async () => {
      let authHeader: unknown
      mockAdapter(async (config) => {
        authHeader = config.headers?.['Authorization']
        return { status: 200, data: {} }
      })

      await apiClient.get('/api/v1/admin/routes')
      expect(authHeader).toBeUndefined()
    })

    it('injects X-Tenant-Id from the logged-in user', async () => {
      useAuthStore.getState().setTokens('token')
      useAuthStore.getState().setUser({
        id: 'user-1',
        tenantId: 'tenant-abc',
        username: 'admin',
        email: 'admin@test.com',
        role: 'SUPER_ADMIN',
      })

      let tenantHeader: string | undefined
      mockAdapter(async (config) => {
        tenantHeader = config.headers?.['X-Tenant-Id'] as string | undefined
        return { status: 200, data: {} }
      })

      await apiClient.get('/api/v1/admin/routes')
      expect(tenantHeader).toBe('tenant-abc')
    })

    it('does not overwrite X-Tenant-Id if caller set it explicitly', async () => {
      useAuthStore.getState().setTokens('token')
      useAuthStore.getState().setUser({
        id: 'user-1',
        tenantId: 'tenant-abc',
        username: 'admin',
        email: 'admin@test.com',
        role: 'SUPER_ADMIN',
      })

      let tenantHeader: string | undefined
      mockAdapter(async (config) => {
        tenantHeader = config.headers?.['X-Tenant-Id'] as string | undefined
        return { status: 200, data: {} }
      })

      await apiClient.get('/api/v1/admin/certs', {
        headers: { 'X-Tenant-Id': 'other-tenant' },
      })
      expect(tenantHeader).toBe('other-tenant')
    })

    it('does not inject X-Tenant-Id when user is null', async () => {
      let tenantHeader: unknown
      mockAdapter(async (config) => {
        tenantHeader = config.headers?.['X-Tenant-Id']
        return { status: 200, data: {} }
      })

      await apiClient.get('/api/v1/admin/routes')
      expect(tenantHeader).toBeUndefined()
    })
  })

  // ── Response interceptor — 401 refresh lock ──────────────────────────────

  describe('response interceptor — 401 refresh lock', () => {
    it('refreshes the token and retries the original request on 401', async () => {
      useAuthStore.getState().setTokens('expired-token')

      let callCount = 0
      mockAdapter(async (config) => {
        const url = config.url ?? ''
        callCount++

        // Refresh call → success
        if (url.includes('/auth/refresh')) {
          return {
            status: 200,
            data: { accessToken: 'new-token', user: null },
          }
        }

        // First call to /routes → 401
        if (url.includes('/routes') && callCount === 1) {
          throw createAxiosError(401, config)
        }

        // Retry of /routes with new token → 200
        return { status: 200, data: { routes: [] } }
      })

      const res = await apiClient.get('/api/v1/admin/routes')
      expect(res.data).toEqual({ routes: [] })
      expect(useAuthStore.getState().accessToken).toBe('new-token')
    })

    it('queues concurrent 401s and replays all after a single refresh', async () => {
      useAuthStore.getState().setTokens('expired-token')

      let refreshCallCount = 0
      let routeCallCount = 0
      let filterCallCount = 0

      mockAdapter(async (config) => {
        const url = config.url ?? ''

        if (url.includes('/auth/refresh')) {
          refreshCallCount++
          await new Promise((r) => setTimeout(r, 10))
          return {
            status: 200,
            data: { accessToken: 'refreshed-token' },
          }
        }

        if (url.includes('/routes')) {
          routeCallCount++
          if (routeCallCount === 1) throw createAxiosError(401, config)
          return { status: 200, data: { routes: [] } }
        }

        if (url.includes('/filters')) {
          filterCallCount++
          if (filterCallCount === 1) throw createAxiosError(401, config)
          return { status: 200, data: { filters: [] } }
        }

        return { status: 200, data: {} }
      })

      const [routeRes, filterRes] = await Promise.all([
        apiClient.get('/api/v1/admin/routes'),
        apiClient.get('/api/v1/admin/filters'),
      ])

      expect(routeRes.data).toEqual({ routes: [] })
      expect(filterRes.data).toEqual({ filters: [] })
      // Only ONE refresh call should have been made
      expect(refreshCallCount).toBe(1)
    })

    it('does not attempt refresh for the refresh endpoint itself (avoids infinite loop)', async () => {
      let refreshAttempts = 0

      mockAdapter(async (config) => {
        const url = config.url ?? ''

        if (url.includes('/auth/refresh')) {
          refreshAttempts++
          throw createAxiosError(401, config)
        }

        return { status: 200, data: {} }
      })

      await expect(apiClient.post('/api/v1/auth/refresh', {})).rejects.toMatchObject({
        response: { status: 401 },
      })

      expect(refreshAttempts).toBe(1)
    })

    it('logs out the user when refresh fails', async () => {
      useAuthStore.getState().setTokens('expired-token')
      useAuthStore.getState().setUser({
        id: 'u1',
        tenantId: 't1',
        username: 'x',
        email: 'x@x.com',
        role: 'VIEWER',
      })

      mockAdapter(async (config) => {
        const url = config.url ?? ''

        if (url.includes('/auth/refresh')) {
          throw createAxiosError(403, config)
        }

        // Original request → 401
        throw createAxiosError(401, config)
      })

      await expect(apiClient.get('/api/v1/admin/routes')).rejects.toBeDefined()

      // User should be logged out
      expect(useAuthStore.getState().isAuthenticated).toBe(false)
      expect(useAuthStore.getState().accessToken).toBeNull()
    })

    it('does not retry for non-401 errors', async () => {
      let callCount = 0
      mockAdapter(async (config) => {
        callCount++
        throw createAxiosError(500, config)
      })

      await expect(apiClient.get('/api/v1/admin/routes')).rejects.toMatchObject({
        response: { status: 500 },
      })

      expect(callCount).toBe(1)
    })

    it('does not retry an already-retried request (prevents double retry)', async () => {
      useAuthStore.getState().setTokens('expired-token')

      let routeCallCount = 0
      mockAdapter(async (config) => {
        const url = config.url ?? ''

        if (url.includes('/auth/refresh')) {
          return {
            status: 200,
            data: { accessToken: 'new-token' },
          }
        }

        routeCallCount++
        throw createAxiosError(401, config)
      })

      await expect(apiClient.get('/api/v1/admin/routes')).rejects.toMatchObject({
        response: { status: 401 },
      })

      // First call (401) + retry after refresh (401 again) = 2 calls
      expect(routeCallCount).toBe(2)
    })
  })
})

// ─── Helpers ──────────────────────────────────────────────────────────────────

function createAxiosError(status: number, config: AxiosRequestConfig) {
  return new axios.AxiosError(
    `Request failed with status code ${status}`,
    axios.AxiosError.ERR_BAD_REQUEST,
    config as never,
    null,
    {
      data: {},
      status,
      statusText: status === 401 ? 'Unauthorized' : 'Error',
      headers: {},
      config: config as never,
    } as never,
  )
}
