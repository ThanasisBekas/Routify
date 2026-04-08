import { describe, it, expect, beforeEach } from 'vitest'
import { renderHook, act } from '@testing-library/react'
import { useRealtimeQuery } from '../../hooks/useRealtimeQuery'
import { useWsStore } from '../../store/wsStore'
import { createQueryWrapper } from '../helpers/testUtils'

describe('useRealtimeQuery', () => {
  beforeEach(() => {
    useWsStore.getState().reset()
  })

  it('returns query data', async () => {
    const wrapper = createQueryWrapper()
    const { result } = renderHook(
      () =>
        useRealtimeQuery({
          queryKey: ['test-data'],
          queryFn: () => Promise.resolve({ items: [1, 2, 3] }),
        }),
      { wrapper },
    )

    // Wait for the query to resolve
    await vi.waitFor(() => {
      expect(result.current.isSuccess).toBe(true)
    })
    expect(result.current.data).toEqual({ items: [1, 2, 3] })
  })

  it('invalidates on matching WS event prefix', async () => {
    let callCount = 0
    const wrapper = createQueryWrapper()
    const { result } = renderHook(
      () =>
        useRealtimeQuery({
          queryKey: ['routes'],
          queryFn: () => {
            callCount++
            return Promise.resolve({ count: callCount })
          },
          wsEvents: ['route'],
        }),
      { wrapper },
    )

    await vi.waitFor(() => {
      expect(result.current.isSuccess).toBe(true)
    })
    expect(callCount).toBe(1)

    // Push a matching event
    act(() => {
      useWsStore.getState().pushEvent({ type: 'route.created', occurredAt: '2025-01-01T00:00:00Z' })
    })

    // The query should be refetched
    await vi.waitFor(() => {
      expect(callCount).toBeGreaterThanOrEqual(2)
    })
  })

  it('does not invalidate on non-matching WS event', async () => {
    let callCount = 0
    const wrapper = createQueryWrapper()
    const { result } = renderHook(
      () =>
        useRealtimeQuery({
          queryKey: ['filters'],
          queryFn: () => {
            callCount++
            return Promise.resolve({ count: callCount })
          },
          wsEvents: ['filter'],
        }),
      { wrapper },
    )

    await vi.waitFor(() => {
      expect(result.current.isSuccess).toBe(true)
    })
    const initialCount = callCount

    // Push a non-matching event
    act(() => {
      useWsStore.getState().pushEvent({ type: 'route.created', occurredAt: '2025-01-01T00:00:00Z' })
    })

    // Wait a tick and verify no refetch
    await new Promise((r) => setTimeout(r, 50))
    expect(callCount).toBe(initialCount)
  })

  it('does not invalidate when no wsEvents configured', async () => {
    let callCount = 0
    const wrapper = createQueryWrapper()
    const { result } = renderHook(
      () =>
        useRealtimeQuery({
          queryKey: ['no-ws'],
          queryFn: () => {
            callCount++
            return Promise.resolve({ count: callCount })
          },
        }),
      { wrapper },
    )

    await vi.waitFor(() => {
      expect(result.current.isSuccess).toBe(true)
    })
    const initialCount = callCount

    act(() => {
      useWsStore.getState().pushEvent({ type: 'route.created', occurredAt: '2025-01-01T00:00:00Z' })
    })

    await new Promise((r) => setTimeout(r, 50))
    expect(callCount).toBe(initialCount)
  })
})
