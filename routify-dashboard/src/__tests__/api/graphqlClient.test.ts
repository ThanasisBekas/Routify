import { describe, it, expect, beforeEach, afterEach } from 'vitest'
import { graphqlQuery } from '../../api/graphqlClient'
import { useAuthStore } from '../../store/authStore'
import { installMockAdapter, restoreMockAdapter, mockAdapter } from '../helpers/testUtils'

describe('graphqlClient', () => {
  beforeEach(() => {
    useAuthStore.getState().logout()
    installMockAdapter()
  })
  afterEach(() => restoreMockAdapter())

  it('sends POST to /api/v1/admin/graphql', async () => {
    let capturedUrl: string | undefined
    let capturedBody: unknown
    mockAdapter(async (config) => {
      capturedUrl = config.url
      capturedBody = JSON.parse(config.data as string)
      return { status: 200, data: { data: { routes: [] } } }
    })

    await graphqlQuery('{ routes { id } }', { limit: 10 })
    expect(capturedUrl).toBe('/api/v1/admin/graphql')
    expect(capturedBody).toEqual({ query: '{ routes { id } }', variables: { limit: 10 } })
  })

  it('returns typed data on success', async () => {
    mockAdapter(async () => ({
      status: 200,
      data: { data: { tenantUsage: { plan: 'PRO', routes: { used: 5 } } } },
    }))

    const result = await graphqlQuery<{ tenantUsage: { plan: string } }>('{ tenantUsage { plan } }')
    expect(result.tenantUsage.plan).toBe('PRO')
  })

  it('throws on GraphQL errors', async () => {
    mockAdapter(async () => ({
      status: 200,
      data: {
        data: null,
        errors: [{ message: 'Field "x" not found' }, { message: 'Unauthorized' }],
      },
    }))

    await expect(graphqlQuery('{ x }')).rejects.toThrow('GraphQL error: Field "x" not found; Unauthorized')
  })

  it('throws when data is null', async () => {
    mockAdapter(async () => ({
      status: 200,
      data: { data: null },
    }))

    await expect(graphqlQuery('{ routes { id } }')).rejects.toThrow('GraphQL response contained no data')
  })

  it('sends variables as undefined when not provided', async () => {
    let capturedBody: unknown
    mockAdapter(async (config) => {
      capturedBody = JSON.parse(config.data as string)
      return { status: 200, data: { data: { result: true } } }
    })

    await graphqlQuery('{ result }')
    expect(capturedBody).toEqual({ query: '{ result }', variables: undefined })
  })
})
