import { describe, it, expect, beforeEach, afterEach } from 'vitest'
import { exportImportApi } from '../../api/exportImportApi'
import { useAuthStore } from '../../store/authStore'
import { installMockAdapter, restoreMockAdapter, mockAdapter } from '../helpers/testUtils'

describe('exportImportApi', () => {
  beforeEach(() => {
    useAuthStore.getState().logout()
    installMockAdapter()
  })
  afterEach(() => restoreMockAdapter())

  it('exportConfig sends GET with yaml format by default', async () => {
    let capturedParams: unknown
    let capturedHeaders: Record<string, unknown> | undefined
    mockAdapter(async (config) => {
      capturedParams = config.params
      capturedHeaders = config.headers as Record<string, unknown>
      return { status: 200, data: 'apiVersion: routify/v1', headers: {} }
    })

    const result = await exportImportApi.exportConfig()
    expect(capturedParams).toEqual({ format: 'yaml', environment: undefined })
    expect(capturedHeaders?.['Accept']).toBe('application/x-yaml')
    expect(result.filename).toBe('routify-export.yaml')
  })

  it('exportConfig sends GET with json format', async () => {
    let capturedParams: unknown
    let capturedHeaders: Record<string, unknown> | undefined
    mockAdapter(async (config) => {
      capturedParams = config.params
      capturedHeaders = config.headers as Record<string, unknown>
      return { status: 200, data: '{}', headers: {} }
    })

    const result = await exportImportApi.exportConfig({ format: 'json' })
    expect(capturedParams).toEqual({ format: 'json', environment: undefined })
    expect(capturedHeaders?.['Accept']).toBe('application/json')
    expect(result.filename).toBe('routify-export.json')
  })

  it('exportConfig extracts filename from Content-Disposition', async () => {
    mockAdapter(async () => ({
      status: 200,
      data: 'yaml-content',
      headers: { 'content-disposition': 'attachment; filename="gateway-2025-01-01.yaml"' },
    }))

    const result = await exportImportApi.exportConfig()
    expect(result.filename).toBe('gateway-2025-01-01.yaml')
  })

  it('previewImport sends POST with yaml content-type', async () => {
    let capturedHeaders: Record<string, unknown> | undefined
    let capturedData: unknown
    mockAdapter(async (config) => {
      capturedHeaders = config.headers as Record<string, unknown>
      capturedData = config.data
      return { status: 200, data: { changes: [] } }
    })

    await exportImportApi.previewImport('apiVersion: routify/v1')
    expect(capturedHeaders?.['Content-Type']).toBe('application/x-yaml')
    expect(capturedData).toBe('apiVersion: routify/v1')
  })

  it('applyImport sends POST with yaml content-type', async () => {
    let capturedHeaders: Record<string, unknown> | undefined
    let capturedUrl: string | undefined
    mockAdapter(async (config) => {
      capturedHeaders = config.headers as Record<string, unknown>
      capturedUrl = config.url
      return { status: 202, data: { status: 'ACCEPTED' } }
    })

    await exportImportApi.applyImport('apiVersion: routify/v1')
    expect(capturedHeaders?.['Content-Type']).toBe('application/x-yaml')
    expect(capturedUrl).toBe('/api/v1/admin/routes/import')
  })
})
