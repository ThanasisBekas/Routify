import { describe, it, expect, beforeEach, afterEach } from 'vitest'
import { routesApi } from '../../api/routesApi'
import { useAuthStore } from '../../store/authStore'
import { installMockAdapter, restoreMockAdapter, mockAdapter } from '../helpers/testUtils'

describe('routesApi', () => {
  beforeEach(() => {
    useAuthStore.getState().logout()
    installMockAdapter()
  })
  afterEach(() => {
    restoreMockAdapter()
  })

  it('list sends GET with query params', async () => {
    let capturedUrl: string | undefined
    let capturedParams: unknown
    mockAdapter(async (config) => {
      capturedUrl = config.url
      capturedParams = config.params
      return { status: 200, data: { content: [], totalElements: 0 } }
    })

    await routesApi.list({ status: 'ACTIVE', page: 0, size: 10 })
    expect(capturedUrl).toBe('/api/v1/admin/routes')
    expect(capturedParams).toEqual({ status: 'ACTIVE', page: 0, size: 10 })
  })

  it('get sends GET with route id', async () => {
    let capturedUrl: string | undefined
    mockAdapter(async (config) => {
      capturedUrl = config.url
      return { status: 200, data: { id: 'r1', name: 'Test Route' } }
    })

    const result = await routesApi.get('r1')
    expect(capturedUrl).toBe('/api/v1/admin/routes/r1')
    expect(result.id).toBe('r1')
  })

  it('create sends POST with request body', async () => {
    let capturedMethod: string | undefined
    let capturedBody: unknown
    mockAdapter(async (config) => {
      capturedMethod = config.method
      capturedBody = JSON.parse(config.data as string)
      return { status: 202, data: { status: 'ACCEPTED', message: 'ok' } }
    })

    await routesApi.create({ name: 'new-route', path: '/api/test', upstreamUrl: 'http://backend:8080' } as never)
    expect(capturedMethod).toBe('post')
    expect(capturedBody).toHaveProperty('name', 'new-route')
  })

  it('update sends PUT', async () => {
    let capturedMethod: string | undefined
    let capturedUrl: string | undefined
    mockAdapter(async (config) => {
      capturedMethod = config.method
      capturedUrl = config.url
      return { status: 200, data: { id: 'r1' } }
    })

    await routesApi.update('r1', { name: 'updated' } as never)
    expect(capturedMethod).toBe('put')
    expect(capturedUrl).toBe('/api/v1/admin/routes/r1')
  })

  it('activate sends POST to correct endpoint', async () => {
    let capturedUrl: string | undefined
    mockAdapter(async (config) => {
      capturedUrl = config.url
      return { status: 200, data: { id: 'r1', status: 'ACTIVE' } }
    })

    await routesApi.activate('r1')
    expect(capturedUrl).toBe('/api/v1/admin/routes/r1/activate')
  })

  it('deactivate sends POST to correct endpoint', async () => {
    let capturedUrl: string | undefined
    mockAdapter(async (config) => {
      capturedUrl = config.url
      return { status: 200, data: { id: 'r1', status: 'DISABLED' } }
    })

    await routesApi.deactivate('r1')
    expect(capturedUrl).toBe('/api/v1/admin/routes/r1/deactivate')
  })

  it('delete sends DELETE', async () => {
    let capturedMethod: string | undefined
    mockAdapter(async (config) => {
      capturedMethod = config.method
      return { status: 200, data: {} }
    })

    await routesApi.delete('r1')
    expect(capturedMethod).toBe('delete')
  })

  it('clone sends POST to clone endpoint', async () => {
    let capturedUrl: string | undefined
    mockAdapter(async (config) => {
      capturedUrl = config.url
      return { status: 200, data: { id: 'r1-clone' } }
    })

    await routesApi.clone('r1')
    expect(capturedUrl).toBe('/api/v1/admin/routes/r1/clone')
  })

  it('deployCanary sends correct body', async () => {
    let capturedBody: unknown
    mockAdapter(async (config) => {
      capturedBody = JSON.parse(config.data as string)
      return { status: 202, data: { status: 'ACCEPTED' } }
    })

    await routesApi.deployCanary('r1', { canaryUpstreamUrl: 'http://canary:8080', weight: 10 })
    expect(capturedBody).toEqual({ canaryUpstreamUrl: 'http://canary:8080', weight: 10 })
  })

  it('getCanaryStatus sends GET', async () => {
    let capturedUrl: string | undefined
    mockAdapter(async (config) => {
      capturedUrl = config.url
      return { status: 200, data: { primaryErrorRate: 0.5, canaryErrorRate: 0.1 } }
    })

    await routesApi.getCanaryStatus('r1')
    expect(capturedUrl).toBe('/api/v1/admin/routes/r1/canary/status')
  })

  it('promoteCanary sends POST', async () => {
    let capturedUrl: string | undefined
    mockAdapter(async (config) => {
      capturedUrl = config.url
      return { status: 202, data: { status: 'ACCEPTED' } }
    })

    await routesApi.promoteCanary('r1')
    expect(capturedUrl).toBe('/api/v1/admin/routes/r1/canary/promote')
  })

  it('rollbackCanary sends reason', async () => {
    let capturedBody: unknown
    mockAdapter(async (config) => {
      capturedBody = JSON.parse(config.data as string)
      return { status: 202, data: { status: 'ACCEPTED' } }
    })

    await routesApi.rollbackCanary('r1', 'too many errors')
    expect(capturedBody).toEqual({ reason: 'too many errors' })
  })

  it('adjustCanaryWeight sends PUT', async () => {
    let capturedMethod: string | undefined
    let capturedBody: unknown
    mockAdapter(async (config) => {
      capturedMethod = config.method
      capturedBody = JSON.parse(config.data as string)
      return { status: 202, data: { status: 'ACCEPTED' } }
    })

    await routesApi.adjustCanaryWeight('r1', { weight: 30 })
    expect(capturedMethod).toBe('put')
    expect(capturedBody).toEqual({ weight: 30 })
  })

  it('purgeCache sends POST', async () => {
    let capturedUrl: string | undefined
    mockAdapter(async (config) => {
      capturedUrl = config.url
      return { status: 202, data: { status: 'ACCEPTED' } }
    })

    await routesApi.purgeCache('r1')
    expect(capturedUrl).toBe('/api/v1/admin/routes/r1/cache/purge')
  })

  it('forceCircuitBreakerOpen sends POST', async () => {
    let capturedUrl: string | undefined
    mockAdapter(async (config) => {
      capturedUrl = config.url
      return { status: 202, data: { status: 'ACCEPTED' } }
    })

    await routesApi.forceCircuitBreakerOpen('r1')
    expect(capturedUrl).toBe('/api/v1/admin/routes/r1/circuit-breaker/force-open')
  })

  it('resetCircuitBreaker sends POST', async () => {
    let capturedUrl: string | undefined
    mockAdapter(async (config) => {
      capturedUrl = config.url
      return { status: 202, data: { status: 'ACCEPTED' } }
    })

    await routesApi.resetCircuitBreaker('r1')
    expect(capturedUrl).toBe('/api/v1/admin/routes/r1/circuit-breaker/reset')
  })
})

