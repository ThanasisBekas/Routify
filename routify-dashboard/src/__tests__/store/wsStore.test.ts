import { describe, it, expect, beforeEach } from 'vitest'
import { useWsStore } from '../../store/wsStore'
import type { WsMessage } from '../../types/ws'

describe('wsStore', () => {
  beforeEach(() => {
    useWsStore.getState().reset()
  })

  describe('setStatus', () => {
    it('updates status', () => {
      useWsStore.getState().setStatus('CONNECTED')
      expect(useWsStore.getState().status).toBe('CONNECTED')
    })

    it('supports all status values', () => {
      for (const s of ['CONNECTING', 'CONNECTED', 'DISCONNECTED', 'RECONNECTING'] as const) {
        useWsStore.getState().setStatus(s)
        expect(useWsStore.getState().status).toBe(s)
      }
    })
  })

  describe('pushEvent', () => {
    it('adds an event to recentEvents', () => {
      const msg: WsMessage = { type: 'route.created', occurredAt: '2025-01-01T00:00:00Z' }
      useWsStore.getState().pushEvent(msg)
      const events = useWsStore.getState().recentEvents
      expect(events).toHaveLength(1)
      expect(events[0].type).toBe('route.created')
      expect(events[0].label).toBe('Route created')
    })

    it('maps known event types to labels', () => {
      const msg: WsMessage = { type: 'gateway.reloaded', occurredAt: '2025-01-01T00:00:00Z' }
      useWsStore.getState().pushEvent(msg)
      expect(useWsStore.getState().recentEvents[0].label).toBe('Gateway hot-reloaded ⚡')
    })

    it('falls back to event type for unknown types', () => {
      const msg: WsMessage = { type: 'custom.unknown.event', occurredAt: '2025-01-01T00:00:00Z' }
      useWsStore.getState().pushEvent(msg)
      expect(useWsStore.getState().recentEvents[0].label).toBe('custom.unknown.event')
    })

    it('caps at 50 events', () => {
      for (let i = 0; i < 60; i++) {
        useWsStore.getState().pushEvent({
          type: 'route.updated',
          occurredAt: `2025-01-01T00:00:${String(i).padStart(2, '0')}Z`,
        })
      }
      expect(useWsStore.getState().recentEvents).toHaveLength(50)
    })

    it('newest event is first', () => {
      useWsStore.getState().pushEvent({ type: 'route.created', occurredAt: '2025-01-01T00:00:00Z' })
      useWsStore.getState().pushEvent({ type: 'route.updated', occurredAt: '2025-01-01T00:00:01Z' })
      expect(useWsStore.getState().recentEvents[0].type).toBe('route.updated')
      expect(useWsStore.getState().recentEvents[1].type).toBe('route.created')
    })

    it('connected type updates connectedClients', () => {
      useWsStore
        .getState()
        .pushEvent({ type: 'connected', occurredAt: '2025-01-01T00:00:00Z', connectedClients: 5 })
      expect(useWsStore.getState().connectedClients).toBe(5)
    })
  })

  describe('setMetrics', () => {
    it('sets circuitBreakers', () => {
      const cb = { 'route-1': { state: 'CLOSED' as const, failureRate: 0, slowCallRate: 0, bufferedCalls: 0 } }
      useWsStore.getState().setMetrics({
        type: 'metrics',
        occurredAt: '2025-01-01T00:00:00Z',
        circuitBreakers: cb,
      })
      expect(useWsStore.getState().circuitBreakers).toEqual(cb)
    })

    it('sets gatewayHealth', () => {
      const health = { status: 'UP', components: { db: { status: 'UP' } } }
      useWsStore.getState().setMetrics({
        type: 'metrics',
        occurredAt: '2025-01-01T00:00:00Z',
        health,
      })
      expect(useWsStore.getState().gatewayHealth).toEqual(health)
    })

    it('sets wsLoadedRoutes', () => {
      useWsStore.getState().setMetrics({
        type: 'metrics',
        occurredAt: '2025-01-01T00:00:00Z',
        loadedRoutes: 42,
      })
      expect(useWsStore.getState().wsLoadedRoutes).toBe(42)
    })

    it('sets null when fields not provided', () => {
      useWsStore.getState().setMetrics({
        type: 'metrics',
        occurredAt: '2025-01-01T00:00:00Z',
      })
      expect(useWsStore.getState().gatewayHealth).toBeNull()
      expect(useWsStore.getState().wsLoadedRoutes).toBeNull()
    })
  })

  describe('reset', () => {
    it('resets all state', () => {
      useWsStore.getState().setStatus('CONNECTED')
      useWsStore.getState().pushEvent({ type: 'route.created', occurredAt: '2025-01-01T00:00:00Z' })
      useWsStore.getState().setMetrics({
        type: 'metrics',
        occurredAt: '2025-01-01T00:00:00Z',
        loadedRoutes: 10,
        circuitBreakers: { r1: { state: 'OPEN', failureRate: 50, slowCallRate: 0, bufferedCalls: 5 } },
        health: { status: 'UP' },
      })

      useWsStore.getState().reset()

      const state = useWsStore.getState()
      expect(state.status).toBe('DISCONNECTED')
      expect(state.recentEvents).toEqual([])
      expect(state.circuitBreakers).toEqual({})
      expect(state.gatewayHealth).toBeNull()
      expect(state.wsLoadedRoutes).toBeNull()
      expect(state.connectedClients).toBe(0)
    })
  })
})

