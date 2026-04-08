import { describe, it, expect, beforeEach, afterEach } from 'vitest'
import { aiApi } from '../../api/aiApi'
import { useAuthStore } from '../../store/authStore'
import { installMockAdapter, restoreMockAdapter, mockAdapter } from '../helpers/testUtils'

describe('aiApi', () => {
  beforeEach(() => {
    useAuthStore.getState().logout()
    installMockAdapter()
  })
  afterEach(() => restoreMockAdapter())

  it('testPolicy sends POST to ai-filter/test-policy', async () => {
    let capturedUrl: string | undefined
    let capturedBody: unknown
    mockAdapter(async (config) => {
      capturedUrl = config.url
      capturedBody = JSON.parse(config.data as string)
      return { status: 200, data: { action: 'ALLOW', reason: 'OK', confidence: 0.95 } }
    })

    const result = await aiApi.testPolicy({
      policyDescription: 'Block PII',
      sampleRequest: { method: 'POST', path: '/api/data', body: '{}' } as never,
    })
    expect(capturedUrl).toBe('/api/v1/admin/ai-filter/test-policy')
    expect(capturedBody).toHaveProperty('policyDescription', 'Block PII')
    expect(result.action).toBe('ALLOW')
  })

  it('testModification sends POST to ai-modifier/test-modification', async () => {
    let capturedUrl: string | undefined
    mockAdapter(async (config) => {
      capturedUrl = config.url
      return { status: 200, data: { original: '{}', modified: '{}' } }
    })

    await aiApi.testModification({ modificationPrompt: 'mask PII', sampleRequest: {} } as never)
    expect(capturedUrl).toBe('/api/v1/admin/ai-modifier/test-modification')
  })

  it('getAiFilterStats sends GET with params', async () => {
    let capturedParams: unknown
    mockAdapter(async (config) => {
      capturedParams = config.params
      return { status: 200, data: { totalDecisions: 100 } }
    })

    await aiApi.getAiFilterStats('r1', '2025-01-01', '2025-01-31')
    expect(capturedParams).toEqual({ routeId: 'r1', from: '2025-01-01', to: '2025-01-31' })
  })

  it('listAiFilterDecisions sends GET with routeId and pagination', async () => {
    let capturedParams: unknown
    mockAdapter(async (config) => {
      capturedParams = config.params
      return { status: 200, data: { content: [], totalElements: 0 } }
    })

    await aiApi.listAiFilterDecisions('r1', { page: 0, size: 20, action: 'BLOCK' })
    expect(capturedParams).toEqual({ routeId: 'r1', page: 0, size: 20, action: 'BLOCK' })
  })

  it('listPromptVersions sends GET with pagination', async () => {
    let capturedUrl: string | undefined
    let capturedParams: unknown
    mockAdapter(async (config) => {
      capturedUrl = config.url
      capturedParams = config.params
      return { status: 200, data: { content: [], totalElements: 0 } }
    })

    await aiApi.listPromptVersions('f1', 0, 10)
    expect(capturedUrl).toBe('/api/v1/admin/ai-filter/f1/versions')
    expect(capturedParams).toEqual({ page: 0, size: 10 })
  })

  it('getPromptVersion sends GET', async () => {
    let capturedUrl: string | undefined
    mockAdapter(async (config) => {
      capturedUrl = config.url
      return { status: 200, data: { id: 'v1', promptText: 'Block PII' } }
    })

    await aiApi.getPromptVersion('f1', 'v1')
    expect(capturedUrl).toBe('/api/v1/admin/ai-filter/f1/versions/v1')
  })

  it('createDraftVersion sends POST', async () => {
    let capturedMethod: string | undefined
    let capturedBody: unknown
    mockAdapter(async (config) => {
      capturedMethod = config.method
      capturedBody = JSON.parse(config.data as string)
      return { status: 200, data: { id: 'v-new' } }
    })

    await aiApi.createDraftVersion('f1', { promptText: 'New prompt', description: 'Test' })
    expect(capturedMethod).toBe('post')
    expect(capturedBody).toEqual({ promptText: 'New prompt', description: 'Test' })
  })

  it('activateVersion sends POST', async () => {
    let capturedUrl: string | undefined
    mockAdapter(async (config) => {
      capturedUrl = config.url
      return { status: 200, data: { id: 'v1', status: 'ACTIVE' } }
    })

    await aiApi.activateVersion('f1', 'v1')
    expect(capturedUrl).toBe('/api/v1/admin/ai-filter/f1/versions/v1/activate')
  })

  it('archiveVersion sends POST', async () => {
    let capturedUrl: string | undefined
    mockAdapter(async (config) => {
      capturedUrl = config.url
      return { status: 200, data: { id: 'v1', status: 'ARCHIVED' } }
    })

    await aiApi.archiveVersion('f1', 'v1')
    expect(capturedUrl).toBe('/api/v1/admin/ai-filter/f1/versions/v1/archive')
  })

  it('labelDecision sends POST with label', async () => {
    let capturedUrl: string | undefined
    let capturedBody: unknown
    mockAdapter(async (config) => {
      capturedUrl = config.url
      capturedBody = JSON.parse(config.data as string)
      return { status: 200, data: { evaluationId: 'e1', label: 'CORRECT' } }
    })

    await aiApi.labelDecision('e1', 'CORRECT')
    expect(capturedUrl).toBe('/api/v1/admin/ai-filter/decisions/e1/label')
    expect(capturedBody).toEqual({ label: 'CORRECT' })
  })
})

