import { describe, it, expect, beforeEach, afterEach } from 'vitest'
import { rolesApi } from '../../api/rolesApi'
import { useAuthStore } from '../../store/authStore'
import { installMockAdapter, restoreMockAdapter, mockAdapter } from '../helpers/testUtils'

describe('rolesApi', () => {
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

    await rolesApi.list({ page: 0, size: 10 })
    expect(capturedParams).toEqual({ page: 0, size: 10 })
  })

  it('get fetches a single role', async () => {
    let capturedUrl: string | undefined
    mockAdapter(async (config) => {
      capturedUrl = config.url
      return { status: 200, data: { id: 'role-1', name: 'Operator' } }
    })

    const result = await rolesApi.get('role-1')
    expect(capturedUrl).toBe('/api/v1/admin/roles/role-1')
    expect(result.name).toBe('Operator')
  })

  it('create sends POST', async () => {
    let capturedMethod: string | undefined
    let capturedBody: unknown
    mockAdapter(async (config) => {
      capturedMethod = config.method
      capturedBody = JSON.parse(config.data as string)
      return { status: 200, data: { id: 'role-new' } }
    })

    await rolesApi.create({ name: 'Custom Role', permissions: ['ROUTES_READ'] } as never)
    expect(capturedMethod).toBe('post')
    expect(capturedBody).toHaveProperty('name', 'Custom Role')
  })

  it('update sends PUT', async () => {
    let capturedMethod: string | undefined
    let capturedUrl: string | undefined
    mockAdapter(async (config) => {
      capturedMethod = config.method
      capturedUrl = config.url
      return { status: 200, data: { id: 'role-1' } }
    })

    await rolesApi.update('role-1', { name: 'Updated Role' } as never)
    expect(capturedMethod).toBe('put')
    expect(capturedUrl).toBe('/api/v1/admin/roles/role-1')
  })

  it('delete sends DELETE', async () => {
    let capturedMethod: string | undefined
    mockAdapter(async (config) => {
      capturedMethod = config.method
      return { status: 200, data: undefined }
    })

    await rolesApi.delete('role-1')
    expect(capturedMethod).toBe('delete')
  })

  it('listPermissions sends GET', async () => {
    let capturedUrl: string | undefined
    mockAdapter(async (config) => {
      capturedUrl = config.url
      return { status: 200, data: ['ROUTES_READ', 'ROUTES_WRITE'] }
    })

    const result = await rolesApi.listPermissions()
    expect(capturedUrl).toBe('/api/v1/admin/roles/permissions')
    expect(result).toEqual(['ROUTES_READ', 'ROUTES_WRITE'])
  })
})

