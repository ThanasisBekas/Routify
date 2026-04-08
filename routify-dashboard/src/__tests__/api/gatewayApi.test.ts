import { describe, it, expect, beforeEach, afterEach } from 'vitest'
import { gatewayApi } from '../../api/gatewayApi'
import { useAuthStore } from '../../store/authStore'
import { installMockAdapter, restoreMockAdapter, mockAdapter } from '../helpers/testUtils'

describe('gatewayApi', () => {
  beforeEach(() => {
    useAuthStore.getState().logout()
    installMockAdapter()
  })
  afterEach(() => restoreMockAdapter())

  it('getConfig sends GET', async () => {
    let capturedUrl: string | undefined
    mockAdapter(async (config) => {
      capturedUrl = config.url
      return { status: 200, data: {} }
    })

    await gatewayApi.getConfig()
    expect(capturedUrl).toBe('/api/v1/admin/gateway/config')
  })

  it('saveConfig sends PUT', async () => {
    let capturedMethod: string | undefined
    mockAdapter(async (config) => {
      capturedMethod = config.method
      return { status: 200, data: {} }
    })

    await gatewayApi.saveConfig({} as never)
    expect(capturedMethod).toBe('put')
  })

  it('getStatus sends GET', async () => {
    let capturedUrl: string | undefined
    mockAdapter(async (config) => {
      capturedUrl = config.url
      return { status: 200, data: { status: 'UP' } }
    })

    await gatewayApi.getStatus()
    expect(capturedUrl).toBe('/api/v1/admin/gateway/status')
  })

  it('triggerReload sends POST', async () => {
    let capturedMethod: string | undefined
    let capturedUrl: string | undefined
    mockAdapter(async (config) => {
      capturedMethod = config.method
      capturedUrl = config.url
      return { status: 200, data: {} }
    })

    await gatewayApi.triggerReload()
    expect(capturedMethod).toBe('post')
    expect(capturedUrl).toBe('/api/v1/admin/gateway/reload')
  })

  it('getCors sends GET', async () => {
    let capturedUrl: string | undefined
    mockAdapter(async (config) => {
      capturedUrl = config.url
      return { status: 200, data: { allowedOrigins: ['*'] } }
    })

    await gatewayApi.getCors()
    expect(capturedUrl).toBe('/api/v1/admin/gateway/cors')
  })

  it('updateCors sends PUT', async () => {
    let capturedMethod: string | undefined
    mockAdapter(async (config) => {
      capturedMethod = config.method
      return { status: 200, data: {} }
    })

    await gatewayApi.updateCors({ allowedOrigins: ['http://localhost'] } as never)
    expect(capturedMethod).toBe('put')
  })

  it('getRateLimitPolicies sends GET', async () => {
    let capturedUrl: string | undefined
    mockAdapter(async (config) => {
      capturedUrl = config.url
      return { status: 200, data: [] }
    })

    await gatewayApi.getRateLimitPolicies()
    expect(capturedUrl).toBe('/api/v1/admin/gateway/rate-limit-policies')
  })

  it('upsertRateLimitPolicy sends PUT with policy id', async () => {
    let capturedUrl: string | undefined
    mockAdapter(async (config) => {
      capturedUrl = config.url
      return { status: 200, data: {} }
    })

    await gatewayApi.upsertRateLimitPolicy({ id: 'p1' } as never)
    expect(capturedUrl).toBe('/api/v1/admin/gateway/rate-limit-policies/p1')
  })

  it('deleteRateLimitPolicy sends DELETE', async () => {
    let capturedMethod: string | undefined
    let capturedUrl: string | undefined
    mockAdapter(async (config) => {
      capturedMethod = config.method
      capturedUrl = config.url
      return { status: 200, data: {} }
    })

    await gatewayApi.deleteRateLimitPolicy('p1')
    expect(capturedMethod).toBe('delete')
    expect(capturedUrl).toBe('/api/v1/admin/gateway/rate-limit-policies/p1')
  })

  it('getAuthProviders sends GET', async () => {
    let capturedUrl: string | undefined
    mockAdapter(async (config) => {
      capturedUrl = config.url
      return { status: 200, data: [] }
    })

    await gatewayApi.getAuthProviders()
    expect(capturedUrl).toBe('/api/v1/admin/gateway/auth-providers')
  })

  it('upsertAuthProvider sends PUT', async () => {
    let capturedUrl: string | undefined
    mockAdapter(async (config) => {
      capturedUrl = config.url
      return { status: 200, data: {} }
    })

    await gatewayApi.upsertAuthProvider({ id: 'ap1' } as never)
    expect(capturedUrl).toBe('/api/v1/admin/gateway/auth-providers/ap1')
  })

  it('getDownstreamCredentials sends GET', async () => {
    let capturedUrl: string | undefined
    mockAdapter(async (config) => {
      capturedUrl = config.url
      return { status: 200, data: [] }
    })

    await gatewayApi.getDownstreamCredentials()
    expect(capturedUrl).toBe('/api/v1/admin/gateway/downstream-credentials')
  })

  it('getRouteHealth sends GET with window param', async () => {
    let capturedParams: unknown
    mockAdapter(async (config) => {
      capturedParams = config.params
      return { status: 200, data: { routes: [] } }
    })

    await gatewayApi.getRouteHealth('1h')
    expect(capturedParams).toEqual({ window: '1h' })
  })

  it('getRouteHealth defaults to 24h window', async () => {
    let capturedParams: unknown
    mockAdapter(async (config) => {
      capturedParams = config.params
      return { status: 200, data: { routes: [] } }
    })

    await gatewayApi.getRouteHealth()
    expect(capturedParams).toEqual({ window: '24h' })
  })

  it('saveRouteSlo sends PUT', async () => {
    let capturedUrl: string | undefined
    let capturedMethod: string | undefined
    mockAdapter(async (config) => {
      capturedUrl = config.url
      capturedMethod = config.method
      return { status: 200, data: {} }
    })

    await gatewayApi.saveRouteSlo('r1', { availabilityTarget: 99.9 } as never)
    expect(capturedMethod).toBe('put')
    expect(capturedUrl).toBe('/api/v1/admin/routes/r1/slo')
  })

  it('getFleetStatus sends GET', async () => {
    let capturedUrl: string | undefined
    mockAdapter(async (config) => {
      capturedUrl = config.url
      return { status: 200, data: { instances: [] } }
    })

    await gatewayApi.getFleetStatus()
    expect(capturedUrl).toBe('/api/v1/admin/gateway/fleet')
  })
})
