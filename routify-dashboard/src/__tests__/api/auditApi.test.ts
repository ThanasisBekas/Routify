import { describe, it, expect, beforeEach, afterEach } from 'vitest'
import { auditApi } from '../../api/auditApi'
import { useAuthStore } from '../../store/authStore'
import { installMockAdapter, restoreMockAdapter, mockAdapter } from '../helpers/testUtils'

describe('auditApi', () => {
  beforeEach(() => {
    useAuthStore.getState().logout()
    installMockAdapter()
  })
  afterEach(() => restoreMockAdapter())

  it('listEvents sends GET with params', async () => {
    let capturedUrl: string | undefined
    let capturedParams: unknown
    mockAdapter(async (config) => {
      capturedUrl = config.url
      capturedParams = config.params
      return { status: 200, data: { content: [], totalElements: 0 } }
    })

    await auditApi.listEvents({ page: 0, size: 20, eventType: 'ROUTE_CREATED' })
    expect(capturedUrl).toBe('/api/v1/admin/audit/events')
    expect(capturedParams).toEqual({ page: 0, size: 20, eventType: 'ROUTE_CREATED' })
  })

  it('getRouteHistory sends GET', async () => {
    let capturedUrl: string | undefined
    mockAdapter(async (config) => {
      capturedUrl = config.url
      return { status: 200, data: [] }
    })

    await auditApi.getRouteHistory('r1')
    expect(capturedUrl).toBe('/api/v1/admin/audit/events/route/r1')
  })

  it('getFilterHistory sends GET', async () => {
    let capturedUrl: string | undefined
    mockAdapter(async (config) => {
      capturedUrl = config.url
      return { status: 200, data: [] }
    })

    await auditApi.getFilterHistory('f1')
    expect(capturedUrl).toBe('/api/v1/admin/audit/events/filter/f1')
  })

  it('listRequests sends GET with params', async () => {
    let capturedParams: unknown
    mockAdapter(async (config) => {
      capturedParams = config.params
      return { status: 200, data: { content: [], totalElements: 0 } }
    })

    await auditApi.listRequests({ routeId: 'r1', page: 0, size: 10 })
    expect(capturedParams).toEqual({ routeId: 'r1', page: 0, size: 10 })
  })

  it('getRouteStats sends GET', async () => {
    let capturedUrl: string | undefined
    mockAdapter(async (config) => {
      capturedUrl = config.url
      return { status: 200, data: { totalRequests: 100 } }
    })

    await auditApi.getRouteStats('r1')
    expect(capturedUrl).toBe('/api/v1/admin/audit/requests/stats/r1')
  })

  it('replaySingle sends POST', async () => {
    let capturedUrl: string | undefined
    let capturedMethod: string | undefined
    mockAdapter(async (config) => {
      capturedUrl = config.url
      capturedMethod = config.method
      return { status: 200, data: { status: 'SUCCESS' } }
    })

    await auditApi.replaySingle('req-1')
    expect(capturedMethod).toBe('post')
    expect(capturedUrl).toBe('/api/v1/admin/audit/replay/req-1')
  })

  it('replayBulk sends POST with default limit', async () => {
    let capturedParams: unknown
    mockAdapter(async (config) => {
      capturedParams = config.params
      return { status: 200, data: { totalReplayed: 10 } }
    })

    await auditApi.replayBulk()
    expect(capturedParams).toEqual({ limit: 50 })
  })

  it('replayBulk sends POST with custom limit', async () => {
    let capturedParams: unknown
    mockAdapter(async (config) => {
      capturedParams = config.params
      return { status: 200, data: { totalReplayed: 5 } }
    })

    await auditApi.replayBulk(10)
    expect(capturedParams).toEqual({ limit: 10 })
  })

  it('getReplayStats sends GET', async () => {
    let capturedUrl: string | undefined
    mockAdapter(async (config) => {
      capturedUrl = config.url
      return { status: 200, data: { pending: 5, completed: 100 } }
    })

    await auditApi.getReplayStats()
    expect(capturedUrl).toBe('/api/v1/admin/audit/replay/stats')
  })
})

