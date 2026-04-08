import { describe, it, expect, beforeEach, afterEach } from 'vitest'
import { tenantsApi } from '../../api/tenantsApi'
import { useAuthStore } from '../../store/authStore'
import { installMockAdapter, restoreMockAdapter, mockAdapter } from '../helpers/testUtils'

describe('tenantsApi', () => {
  beforeEach(() => {
    useAuthStore.getState().logout()
    installMockAdapter()
  })
  afterEach(() => restoreMockAdapter())

  it('listWorkspaces sends GET and unwraps workspaces', async () => {
    let capturedUrl: string | undefined
    mockAdapter(async (config) => {
      capturedUrl = config.url
      return { status: 200, data: { workspaces: [{ name: 'Acme', slug: 'acme' }] } }
    })

    const result = await tenantsApi.listWorkspaces()
    expect(capturedUrl).toBe('/api/v1/admin/tenants/workspaces')
    expect(result).toEqual([{ name: 'Acme', slug: 'acme' }])
  })

  it('list sends GET with pagination defaults', async () => {
    let capturedParams: unknown
    mockAdapter(async (config) => {
      capturedParams = config.params
      return { status: 200, data: { content: [], totalElements: 0 } }
    })

    await tenantsApi.list()
    expect(capturedParams).toEqual({ page: 0, size: 20 })
  })

  it('list sends GET with custom pagination', async () => {
    let capturedParams: unknown
    mockAdapter(async (config) => {
      capturedParams = config.params
      return { status: 200, data: { content: [], totalElements: 0 } }
    })

    await tenantsApi.list(2, 50)
    expect(capturedParams).toEqual({ page: 2, size: 50 })
  })

  it('get fetches single tenant', async () => {
    let capturedUrl: string | undefined
    mockAdapter(async (config) => {
      capturedUrl = config.url
      return { status: 200, data: { id: 't1', name: 'Acme' } }
    })

    const result = await tenantsApi.get('t1')
    expect(capturedUrl).toBe('/api/v1/admin/tenants/t1')
    expect(result.name).toBe('Acme')
  })

  it('create sends POST', async () => {
    let capturedBody: unknown
    mockAdapter(async (config) => {
      capturedBody = JSON.parse(config.data as string)
      return { status: 200, data: { id: 't-new' } }
    })

    await tenantsApi.create({ name: 'New Corp', slug: 'new-corp', plan: 'PRO', contactEmail: 'a@b.com' })
    expect(capturedBody).toEqual({ name: 'New Corp', slug: 'new-corp', plan: 'PRO', contactEmail: 'a@b.com' })
  })

  it('update sends PUT', async () => {
    let capturedMethod: string | undefined
    let capturedUrl: string | undefined
    mockAdapter(async (config) => {
      capturedMethod = config.method
      capturedUrl = config.url
      return { status: 200, data: { id: 't1' } }
    })

    await tenantsApi.update('t1', { plan: 'ENTERPRISE' })
    expect(capturedMethod).toBe('put')
    expect(capturedUrl).toBe('/api/v1/admin/tenants/t1')
  })

  it('suspend sends POST with reason as query param', async () => {
    let capturedParams: unknown
    mockAdapter(async (config) => {
      capturedParams = config.params
      return { status: 200, data: { id: 't1' } }
    })

    await tenantsApi.suspend('t1', 'Non-payment')
    expect(capturedParams).toEqual({ reason: 'Non-payment' })
  })

  it('suspend uses default reason when not provided', async () => {
    let capturedParams: unknown
    mockAdapter(async (config) => {
      capturedParams = config.params
      return { status: 200, data: { id: 't1' } }
    })

    await tenantsApi.suspend('t1')
    expect(capturedParams).toEqual({ reason: 'Administrative action' })
  })

  it('reactivate sends POST', async () => {
    let capturedUrl: string | undefined
    mockAdapter(async (config) => {
      capturedUrl = config.url
      return { status: 200, data: { id: 't1' } }
    })

    await tenantsApi.reactivate('t1')
    expect(capturedUrl).toBe('/api/v1/admin/tenants/t1/reactivate')
  })

  it('getUsage fetches usage', async () => {
    let capturedUrl: string | undefined
    mockAdapter(async (config) => {
      capturedUrl = config.url
      return { status: 200, data: { routes: { used: 5, limit: 10 } } }
    })

    await tenantsApi.getUsage('t1')
    expect(capturedUrl).toBe('/api/v1/admin/tenants/t1/usage')
  })

  it('getUsageHistory uses default 30 days', async () => {
    let capturedParams: unknown
    mockAdapter(async (config) => {
      capturedParams = config.params
      return { status: 200, data: { dailyUsage: [] } }
    })

    await tenantsApi.getUsageHistory('t1')
    expect(capturedParams).toEqual({ days: 30 })
  })

  it('getUsageHistory uses custom days', async () => {
    let capturedParams: unknown
    mockAdapter(async (config) => {
      capturedParams = config.params
      return { status: 200, data: { dailyUsage: [] } }
    })

    await tenantsApi.getUsageHistory('t1', 7)
    expect(capturedParams).toEqual({ days: 7 })
  })
})

