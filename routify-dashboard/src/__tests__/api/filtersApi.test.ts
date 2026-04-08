import { describe, it, expect, beforeEach, afterEach } from 'vitest'
import { filtersApi } from '../../api/filtersApi'
import { useAuthStore } from '../../store/authStore'
import { installMockAdapter, restoreMockAdapter, mockAdapter } from '../helpers/testUtils'

describe('filtersApi', () => {
  beforeEach(() => {
    useAuthStore.getState().logout()
    installMockAdapter()
  })
  afterEach(() => restoreMockAdapter())

  it('list sends GET with pagination params', async () => {
    let capturedParams: unknown
    mockAdapter(async (config) => {
      capturedParams = config.params
      return { status: 200, data: { content: [], totalElements: 0 } }
    })

    await filtersApi.list({ page: 1, size: 5 })
    expect(capturedParams).toEqual({ page: 1, size: 5 })
  })

  it('get fetches a single filter', async () => {
    let capturedUrl: string | undefined
    mockAdapter(async (config) => {
      capturedUrl = config.url
      return { status: 200, data: { id: 'f1', name: 'Auth Filter', type: 'AUTH_JWT' } }
    })

    const result = await filtersApi.get('f1')
    expect(capturedUrl).toBe('/api/v1/admin/filters/f1')
    expect(result.name).toBe('Auth Filter')
  })

  it('create sends POST', async () => {
    let capturedMethod: string | undefined
    let capturedBody: unknown
    mockAdapter(async (config) => {
      capturedMethod = config.method
      capturedBody = JSON.parse(config.data as string)
      return { status: 200, data: { id: 'f-new' } }
    })

    await filtersApi.create({ name: 'New Filter', type: 'RATE_LIMIT_FIXED_WINDOW', config: {} } as never)
    expect(capturedMethod).toBe('post')
    expect(capturedBody).toHaveProperty('name', 'New Filter')
  })

  it('update sends PUT', async () => {
    let capturedMethod: string | undefined
    let capturedUrl: string | undefined
    mockAdapter(async (config) => {
      capturedMethod = config.method
      capturedUrl = config.url
      return { status: 200, data: { id: 'f1' } }
    })

    await filtersApi.update('f1', { name: 'Updated' } as never)
    expect(capturedMethod).toBe('put')
    expect(capturedUrl).toBe('/api/v1/admin/filters/f1')
  })

  it('delete sends DELETE', async () => {
    let capturedMethod: string | undefined
    let capturedUrl: string | undefined
    mockAdapter(async (config) => {
      capturedMethod = config.method
      capturedUrl = config.url
      return { status: 200, data: {} }
    })

    await filtersApi.delete('f1')
    expect(capturedMethod).toBe('delete')
    expect(capturedUrl).toBe('/api/v1/admin/filters/f1')
  })
})
