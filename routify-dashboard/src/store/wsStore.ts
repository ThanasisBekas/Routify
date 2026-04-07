/**
 * wsStore — global Zustand store for WebSocket connection state and the live event feed.
 *
 * All components that need real-time data subscribe here instead of each
 * managing their own WebSocket connection. One connection, many consumers.
 */
import { create } from 'zustand'
import type { WsMessage, WsStatus, CircuitBreakerState } from '../types/ws'

interface RecentEvent {
  id: string
  type: string
  label: string
  occurredAt: string
}

interface WsState {
  status: WsStatus
  recentEvents: RecentEvent[]
  circuitBreakers: Record<string, CircuitBreakerState>
  gatewayHealth: { status: string; components?: Record<string, { status: string }> } | null
  /** Live loaded-route count pushed by the metrics WS message, null when not yet received */
  wsLoadedRoutes: number | null
  connectedClients: number

  // Actions
  setStatus: (s: WsStatus) => void
  pushEvent: (msg: WsMessage) => void
  setMetrics: (msg: WsMessage) => void
  reset: () => void
}

const EVENT_LABELS: Record<string, string> = {
  'route.created': 'Route created',
  'route.updated': 'Route updated',
  'route.activated': 'Route activated ✓',
  'route.deactivated': 'Route deactivated',
  'route.deleted': 'Route deleted',
  'filter.created': 'Filter created',
  'filter.updated': 'Filter updated',
  'filter.deleted': 'Filter deleted',
  'filter.attached': 'Filter attached to route',
  'filter.detached': 'Filter detached from route',
  'gateway.reloaded': 'Gateway hot-reloaded ⚡',
  'gateway.config.changed': 'Gateway config saved to DB 💾',
  'certificate.uploaded': 'Certificate uploaded 🔐',
  'certificate.revoked': 'Certificate revoked',
  'certificate.deleted': 'Certificate deleted',
  'certificate.mapped': 'Certificate mapped to gateway',
  'certificate.unmapped': 'Certificate unmapped from gateway',
  'certificate.rotated': 'Certificate rotated 🔄',
  'certificate.group.created': 'Certificate group created',
  'certificate.group.updated': 'Certificate group updated',
  'certificate.group.archived': 'Certificate group archived',
  'certificate.group.deleted': 'Certificate group deleted',
  'certificate.group.member.added': 'Member added to group',
  'certificate.group.member.removed': 'Member removed from group',
  'user.created': 'User created',
  'user.updated': 'User updated',
  'user.deleted': 'User deleted',
  'tenant.created': 'Workspace created',
  'tenant.updated': 'Workspace updated',
  'tenant.suspended': 'Workspace suspended',
  'tenant.reactivated': 'Workspace reactivated',
  'replay.completed': 'Replay completed',
  'replay.bulk.completed': 'Bulk replay completed',
  'audit.request.logged': 'Request logged',
  'CIRCUIT_BREAKER_STATE_CHANGE': 'Circuit breaker state changed ⚡',
}

export const useWsStore = create<WsState>((set) => ({
  status: 'DISCONNECTED',
  recentEvents: [],
  circuitBreakers: {},
  gatewayHealth: null,
  wsLoadedRoutes: null,
  connectedClients: 0,

  setStatus: (status) => set({ status }),

  pushEvent: (msg) =>
    set((state) => {
      if (msg.type === 'connected') {
        return { connectedClients: msg.connectedClients ?? state.connectedClients }
      }
      const label = EVENT_LABELS[msg.type] ?? msg.type
      const event: RecentEvent = {
        id: `${Date.now()}-${Math.random()}`,
        type: msg.type,
        label,
        occurredAt: msg.occurredAt,
      }
      return {
        recentEvents: [event, ...state.recentEvents].slice(0, 50),
      }
    }),

  setMetrics: (msg) =>
    set({
      circuitBreakers: (msg.circuitBreakers ?? {}) as Record<string, CircuitBreakerState>,
      gatewayHealth: msg.health ?? null,
      wsLoadedRoutes: msg.loadedRoutes ?? null,
    }),

  reset: () =>
    set({
      status: 'DISCONNECTED',
      recentEvents: [],
      circuitBreakers: {},
      gatewayHealth: null,
      wsLoadedRoutes: null,
      connectedClients: 0,
    }),
}))
