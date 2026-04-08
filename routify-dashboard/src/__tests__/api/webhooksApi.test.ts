import { describe, it, expect, beforeEach, afterEach } from 'vitest'
import { webhooksApi } from '../../api/webhooksApi'
import { useAuthStore } from '../../store/authStore'
import { installMockAdapter, restoreMockAdapter, mockAdapter } from '../helpers/testUtils'

describe('webhooksApi', () => {
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

    await webhooksApi.list({ page: 0, size: 10 })
    expect(capturedParams).toEqual({ page: 0, size: 10 })
  })

  it('get fetches a single webhook', async () => {
    let capturedUrl: string | undefined
    mockAdapter(async (config) => {
      capturedUrl = config.url
      return { status: 200, data: { id: 'w1', url: 'https://hook.example.com' } }
    })

    const result = await webhooksApi.get('w1')
    expect(capturedUrl).toBe('/api/v1/admin/webhooks/w1')
    expect(result.url).toBe('https://hook.example.com')
  })

  it('create sends POST', async () => {
    let capturedMethod: string | undefined
    let capturedBody: unknown
    mockAdapter(async (config) => {
      capturedMethod = config.method
      capturedBody = JSON.parse(config.data as string)
      return { status: 202, data: { status: 'ACCEPTED' } }
    })

    await webhooksApi.create({ url: 'https://hook.example.com', events: ['ROUTE_ACTIVATED'] } as never)
    expect(capturedMethod).toBe('post')
    expect(capturedBody).toHaveProperty('url', 'https://hook.example.com')
  })

  it('update sends PUT', async () => {
    let capturedMethod: string | undefined
    let capturedUrl: string | undefined
    mockAdapter(async (config) => {
      capturedMethod = config.method
      capturedUrl = config.url
      return { status: 202, data: { status: 'ACCEPTED' } }
    })

    await webhooksApi.update('w1', { url: 'https://new-hook.example.com' } as never)
    expect(capturedMethod).toBe('put')
    expect(capturedUrl).toBe('/api/v1/admin/webhooks/w1')
  })

  it('delete sends DELETE', async () => {
    let capturedMethod: string | undefined
    mockAdapter(async (config) => {
      capturedMethod = config.method
      return { status: 202, data: { status: 'ACCEPTED' } }
    })

    await webhooksApi.delete('w1')
    expect(capturedMethod).toBe('delete')
  })

  it('testPing sends POST', async () => {
    let capturedUrl: string | undefined
    mockAdapter(async (config) => {
      capturedUrl = config.url
      return { status: 200, data: { success: true, statusCode: 200 } }
    })

    const result = await webhooksApi.testPing('w1')
    expect(capturedUrl).toBe('/api/v1/admin/webhooks/w1/test')
    expect(result.success).toBe(true)
  })

  it('deliveries sends GET with pagination', async () => {
    let capturedUrl: string | undefined
    let capturedParams: unknown
    mockAdapter(async (config) => {
      capturedUrl = config.url
      capturedParams = config.params
      return { status: 200, data: { content: [], totalElements: 0 } }
    })

    await webhooksApi.deliveries('w1', { page: 0, size: 10 })
    expect(capturedUrl).toBe('/api/v1/admin/webhooks/w1/deliveries')
    expect(capturedParams).toEqual({ page: 0, size: 10 })
  })
})

