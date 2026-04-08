import { describe, it, expect, beforeEach, afterEach } from 'vitest'
import { apiKeysApi } from '../../api/apiKeysApi'
import { useAuthStore } from '../../store/authStore'
import { installMockAdapter, restoreMockAdapter, mockAdapter } from '../helpers/testUtils'

describe('apiKeysApi', () => {
  beforeEach(() => {
    useAuthStore.getState().logout()
    installMockAdapter()
  })
  afterEach(() => restoreMockAdapter())

  it('list sends GET with pagination', async () => {
    let capturedUrl: string | undefined
    let capturedParams: unknown
    mockAdapter(async (config) => {
      capturedUrl = config.url
      capturedParams = config.params
      return { status: 200, data: { content: [], totalElements: 0 } }
    })

    await apiKeysApi.list({ page: 0, size: 20 })
    expect(capturedUrl).toBe('/api/v1/admin/api-keys')
    expect(capturedParams).toEqual({ page: 0, size: 20 })
  })

  it('get fetches a single key', async () => {
    let capturedUrl: string | undefined
    mockAdapter(async (config) => {
      capturedUrl = config.url
      return { status: 200, data: { id: 'k1', name: 'Test Key' } }
    })

    const result = await apiKeysApi.get('k1')
    expect(capturedUrl).toBe('/api/v1/admin/api-keys/k1')
    expect(result.name).toBe('Test Key')
  })

  it('create sends POST', async () => {
    let capturedMethod: string | undefined
    let capturedBody: unknown
    mockAdapter(async (config) => {
      capturedMethod = config.method
      capturedBody = JSON.parse(config.data as string)
      return { status: 200, data: { id: 'k-new', rawKey: 'rk_abc123' } }
    })

    const result = await apiKeysApi.create({ name: 'New Key' } as never)
    expect(capturedMethod).toBe('post')
    expect(capturedBody).toHaveProperty('name', 'New Key')
    expect(result.rawKey).toBe('rk_abc123')
  })

  it('revoke sends POST to revoke endpoint', async () => {
    let capturedUrl: string | undefined
    let capturedMethod: string | undefined
    mockAdapter(async (config) => {
      capturedUrl = config.url
      capturedMethod = config.method
      return { status: 200, data: { id: 'k1', status: 'REVOKED' } }
    })

    await apiKeysApi.revoke('k1')
    expect(capturedMethod).toBe('post')
    expect(capturedUrl).toBe('/api/v1/admin/api-keys/k1/revoke')
  })

  it('rotate sends POST and returns new raw key', async () => {
    let capturedUrl: string | undefined
    mockAdapter(async (config) => {
      capturedUrl = config.url
      return { status: 200, data: { id: 'k1', rawKey: 'rk_new456' } }
    })

    const result = await apiKeysApi.rotate('k1')
    expect(capturedUrl).toBe('/api/v1/admin/api-keys/k1/rotate')
    expect(result.rawKey).toBe('rk_new456')
  })
})

