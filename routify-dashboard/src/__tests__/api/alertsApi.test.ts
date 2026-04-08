import { describe, it, expect, beforeEach, afterEach } from 'vitest'
import { alertsApi } from '../../api/alertsApi'
import { useAuthStore } from '../../store/authStore'
import { installMockAdapter, restoreMockAdapter, mockAdapter } from '../helpers/testUtils'

describe('alertsApi', () => {
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

    await alertsApi.list({ page: 0, size: 20 })
    expect(capturedParams).toEqual({ page: 0, size: 20 })
  })

  it('get fetches a single alert rule', async () => {
    let capturedUrl: string | undefined
    mockAdapter(async (config) => {
      capturedUrl = config.url
      return { status: 200, data: { id: 'a1', name: 'High Error Rate' } }
    })

    const result = await alertsApi.get('a1')
    expect(capturedUrl).toBe('/api/v1/admin/alerts/a1')
    expect(result.name).toBe('High Error Rate')
  })

  it('create sends POST', async () => {
    let capturedMethod: string | undefined
    let capturedBody: unknown
    mockAdapter(async (config) => {
      capturedMethod = config.method
      capturedBody = JSON.parse(config.data as string)
      return { status: 200, data: { id: 'a-new' } }
    })

    await alertsApi.create({ name: 'New Alert', metric: 'ERROR_RATE', threshold: 5 } as never)
    expect(capturedMethod).toBe('post')
    expect(capturedBody).toHaveProperty('name', 'New Alert')
  })

  it('update sends PUT', async () => {
    let capturedMethod: string | undefined
    let capturedUrl: string | undefined
    mockAdapter(async (config) => {
      capturedMethod = config.method
      capturedUrl = config.url
      return { status: 200, data: { id: 'a1' } }
    })

    await alertsApi.update('a1', { name: 'Updated Alert' } as never)
    expect(capturedMethod).toBe('put')
    expect(capturedUrl).toBe('/api/v1/admin/alerts/a1')
  })

  it('delete sends DELETE', async () => {
    let capturedMethod: string | undefined
    mockAdapter(async (config) => {
      capturedMethod = config.method
      return { status: 200, data: undefined }
    })

    await alertsApi.delete('a1')
    expect(capturedMethod).toBe('delete')
  })

  it('mute sends POST with mute data', async () => {
    let capturedUrl: string | undefined
    let capturedBody: unknown
    mockAdapter(async (config) => {
      capturedUrl = config.url
      capturedBody = JSON.parse(config.data as string)
      return { status: 200, data: { id: 'a1', muted: true } }
    })

    await alertsApi.mute('a1', { reason: 'Maintenance', durationMinutes: 60 } as never)
    expect(capturedUrl).toBe('/api/v1/admin/alerts/a1/mute')
    expect(capturedBody).toEqual({ reason: 'Maintenance', durationMinutes: 60 })
  })

  it('unmute sends POST', async () => {
    let capturedUrl: string | undefined
    mockAdapter(async (config) => {
      capturedUrl = config.url
      return { status: 200, data: { id: 'a1', muted: false } }
    })

    await alertsApi.unmute('a1')
    expect(capturedUrl).toBe('/api/v1/admin/alerts/a1/unmute')
  })

  it('history sends GET with pagination', async () => {
    let capturedUrl: string | undefined
    let capturedParams: unknown
    mockAdapter(async (config) => {
      capturedUrl = config.url
      capturedParams = config.params
      return { status: 200, data: { content: [], totalElements: 0 } }
    })

    await alertsApi.history('a1', { page: 0, size: 20 })
    expect(capturedUrl).toBe('/api/v1/admin/alerts/a1/history')
    expect(capturedParams).toEqual({ page: 0, size: 20 })
  })
})
