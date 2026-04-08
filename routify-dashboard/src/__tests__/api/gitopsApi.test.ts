import { describe, it, expect, beforeEach, afterEach } from 'vitest'
import { gitopsApi } from '../../api/gitopsApi'
import { useAuthStore } from '../../store/authStore'
import { installMockAdapter, restoreMockAdapter, mockAdapter } from '../helpers/testUtils'

describe('gitopsApi', () => {
  beforeEach(() => {
    useAuthStore.getState().logout()
    installMockAdapter()
  })
  afterEach(() => restoreMockAdapter())

  it('getStatus sends GET', async () => {
    let capturedUrl: string | undefined
    mockAdapter(async (config) => {
      capturedUrl = config.url
      return { status: 200, data: { connected: true, lastSync: '2025-01-01T00:00:00Z' } }
    })

    const result = await gitopsApi.getStatus()
    expect(capturedUrl).toBe('/api/v1/admin/gitops/status')
    expect(result.connected).toBe(true)
  })

  it('getHistory sends GET and returns array', async () => {
    let capturedUrl: string | undefined
    mockAdapter(async (config) => {
      capturedUrl = config.url
      return { status: 200, data: [{ outcome: 'SUCCESS' }] }
    })

    const result = await gitopsApi.getHistory()
    expect(capturedUrl).toBe('/api/v1/admin/gitops/history')
    expect(result).toHaveLength(1)
  })

  it('triggerSync sends POST', async () => {
    let capturedMethod: string | undefined
    let capturedUrl: string | undefined
    mockAdapter(async (config) => {
      capturedMethod = config.method
      capturedUrl = config.url
      return { status: 200, data: { status: 'ok', outcome: 'NO_CHANGES' } }
    })

    const result = await gitopsApi.triggerSync()
    expect(capturedMethod).toBe('post')
    expect(capturedUrl).toBe('/api/v1/admin/gitops/sync')
    expect(result.outcome).toBe('NO_CHANGES')
  })
})
