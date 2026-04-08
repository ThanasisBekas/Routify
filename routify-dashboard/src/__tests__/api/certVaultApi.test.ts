import { describe, it, expect, beforeEach, afterEach } from 'vitest'
import { certVaultApi } from '../../api/certVaultApi'
import { useAuthStore } from '../../store/authStore'
import { installMockAdapter, restoreMockAdapter, mockAdapter } from '../helpers/testUtils'

describe('certVaultApi', () => {
  beforeEach(() => {
    useAuthStore.getState().logout()
    installMockAdapter()
  })
  afterEach(() => restoreMockAdapter())

  it('listLogicalIds sends GET', async () => {
    let capturedUrl: string | undefined
    mockAdapter(async (config) => {
      capturedUrl = config.url
      return { status: 200, data: [{ logicalId: 'cert-1', alias: 'Primary' }] }
    })

    const result = await certVaultApi.listLogicalIds()
    expect(capturedUrl).toBe('/api/v1/admin/certificates/logical-ids')
    expect(result).toHaveLength(1)
  })

  it('listCertificates sends X-Tenant-Id', async () => {
    let capturedHeaders: Record<string, unknown> | undefined
    mockAdapter(async (config) => {
      capturedHeaders = config.headers as Record<string, unknown>
      return { status: 200, data: { content: [], totalElements: 0 } }
    })

    await certVaultApi.listCertificates({ tenantId: 't1' })
    expect(capturedHeaders?.['X-Tenant-Id']).toBe('t1')
  })

  it('getCertificate sends correct URL and tenant header', async () => {
    let capturedUrl: string | undefined
    let capturedHeaders: Record<string, unknown> | undefined
    mockAdapter(async (config) => {
      capturedUrl = config.url
      capturedHeaders = config.headers as Record<string, unknown>
      return { status: 200, data: { id: 'c1' } }
    })

    await certVaultApi.getCertificate('c1', 't1')
    expect(capturedUrl).toBe('/api/v1/admin/certificates/c1')
    expect(capturedHeaders?.['X-Tenant-Id']).toBe('t1')
  })

  it('getStats sends optional tenant header', async () => {
    let capturedHeaders: Record<string, unknown> | undefined
    mockAdapter(async (config) => {
      capturedHeaders = config.headers as Record<string, unknown>
      return { status: 200, data: { total: 10 } }
    })

    await certVaultApi.getStats('t1')
    expect(capturedHeaders?.['X-Tenant-Id']).toBe('t1')
  })

  it('uploadCertificate sends POST', async () => {
    let capturedMethod: string | undefined
    let capturedHeaders: Record<string, unknown> | undefined
    mockAdapter(async (config) => {
      capturedMethod = config.method
      capturedHeaders = config.headers as Record<string, unknown>
      return { status: 200, data: { status: 'ok' } }
    })

    await certVaultApi.uploadCertificate('t1', { alias: 'test', certificate: 'PEM...' } as never)
    expect(capturedMethod).toBe('post')
    expect(capturedHeaders?.['X-Tenant-Id']).toBe('t1')
  })

  it('revokeCertificate sends POST', async () => {
    let capturedUrl: string | undefined
    mockAdapter(async (config) => {
      capturedUrl = config.url
      return { status: 200, data: { status: 'ok' } }
    })

    await certVaultApi.revokeCertificate('c1', 't1')
    expect(capturedUrl).toBe('/api/v1/admin/certificates/c1/revoke')
  })

  it('deleteCertificate sends DELETE', async () => {
    let capturedMethod: string | undefined
    mockAdapter(async (config) => {
      capturedMethod = config.method
      return { status: 200, data: { status: 'ok' } }
    })

    await certVaultApi.deleteCertificate('c1', 't1')
    expect(capturedMethod).toBe('delete')
  })

  it('listGroups sends GET with tenant header', async () => {
    let capturedHeaders: Record<string, unknown> | undefined
    mockAdapter(async (config) => {
      capturedHeaders = config.headers as Record<string, unknown>
      return { status: 200, data: { content: [], totalElements: 0 } }
    })

    await certVaultApi.listGroups({ tenantId: 't1' })
    expect(capturedHeaders?.['X-Tenant-Id']).toBe('t1')
  })

  it('createGroup sends POST', async () => {
    let capturedMethod: string | undefined
    mockAdapter(async (config) => {
      capturedMethod = config.method
      return { status: 200, data: { status: 'ok' } }
    })

    await certVaultApi.createGroup('t1', { logicalId: 'grp-1', alias: 'Test Group' } as never)
    expect(capturedMethod).toBe('post')
  })

  it('archiveGroup sends POST', async () => {
    let capturedUrl: string | undefined
    mockAdapter(async (config) => {
      capturedUrl = config.url
      return { status: 200, data: { status: 'ok' } }
    })

    await certVaultApi.archiveGroup('g1', 't1')
    expect(capturedUrl).toBe('/api/v1/admin/cert-groups/g1/archive')
  })

  it('registerAcmeAccount sends POST', async () => {
    let capturedUrl: string | undefined
    mockAdapter(async (config) => {
      capturedUrl = config.url
      return { status: 200, data: { id: 'acme-1' } }
    })

    await certVaultApi.registerAcmeAccount('t1', { email: 'admin@test.com' } as never)
    expect(capturedUrl).toBe('/api/v1/admin/certs/acme/register')
  })

  it('issueAcmeCertificate sends POST', async () => {
    let capturedUrl: string | undefined
    mockAdapter(async (config) => {
      capturedUrl = config.url
      return { status: 200, data: { id: 'order-1' } }
    })

    await certVaultApi.issueAcmeCertificate('t1', { domain: 'example.com' } as never)
    expect(capturedUrl).toBe('/api/v1/admin/certs/acme/issue')
  })

  it('listAcmeOrders sends GET with pagination', async () => {
    let capturedParams: unknown
    mockAdapter(async (config) => {
      capturedParams = config.params
      return { status: 200, data: { content: [] } }
    })

    await certVaultApi.listAcmeOrders({ tenantId: 't1', page: 1, size: 10 })
    expect(capturedParams).toEqual({ page: 1, size: 10 })
  })

  it('renewAcmeCertificate sends POST', async () => {
    let capturedUrl: string | undefined
    mockAdapter(async (config) => {
      capturedUrl = config.url
      return { status: 200, data: { id: 'order-1' } }
    })

    await certVaultApi.renewAcmeCertificate('order-1', 't1')
    expect(capturedUrl).toBe('/api/v1/admin/certs/acme/orders/order-1/renew')
  })
})

