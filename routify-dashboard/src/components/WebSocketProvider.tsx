/**
 * WebSocketProvider — mounts at the app root and manages the single
 * shared WebSocket connection for the entire dashboard.
 *
 * All components that need real-time data read from wsStore (Zustand)
 * instead of each hooking their own connection.
 *
 * The provider drives React Query cache invalidation globally:
 * when a WS event arrives it calls qc.invalidateQueries() for ALL
 * affected query keys so every active query for that resource auto-refetches.
 *
 * This replaces all polling (refetchInterval) across the dashboard —
 * data is now purely event-driven via WebSocket.
 */
import { useQueryClient } from '@tanstack/react-query'
import { useWebSocket } from '../hooks/useWebSocket'
import { useWsStore } from '../store/wsStore'
import { useAuthStore } from '../store/authStore'
import type { WsMessage } from '../types/ws'

// Map from server-emitted queryKey to React Query cache keys to invalidate
const QUERY_KEY_MAP: Record<string, string[][]> = {
  routes:            [['routes'], ['route']],
  filters:           [['filters']],
  'gateway-config':  [['gateway-config'], ['gateway-tls-config']],
  'gateway-status':  [['gateway-status']],
  audit:             [['audit-events'], ['audit-requests'], ['audit-failed'], ['replay-stats']],
  replay:            [['audit-failed'], ['replay-stats']],
  certificates:      [['cert-groups'], ['cert-group-detail'], ['cert-stats'], ['certs-active'], ['gateway-live-certs']],
  'cert-groups':     [['cert-groups'], ['cert-group-detail'], ['cert-stats'], ['certs-active'], ['gateway-live-certs']],
  users:             [['users']],
  tenants:           [['tenants'], ['tenants-for-user-create']],
  misc:              [],
}

// Events that change the number of active routes in the gateway — refresh the status count
const GATEWAY_ROUTE_EVENTS = new Set([
  'route.activated',
  'route.deactivated',
  'route.deleted',
  'gateway.reloaded',
])

// Events that affect audit trail
const AUDIT_EVENTS = new Set([
  'route.created', 'route.updated', 'route.activated',
  'route.deactivated', 'route.deleted',
  'filter.created', 'filter.updated', 'filter.deleted',
  'filter.attached', 'filter.detached',
  'gateway.reloaded', 'gateway.config.changed',
])

export function WebSocketProvider({ children }: { children: React.ReactNode }) {
  const isAuthenticated = useAuthStore(s => s.isAuthenticated)
  const qc = useQueryClient()
  const { setStatus, pushEvent, setMetrics } = useWsStore()

  const handleMessage = (msg: WsMessage) => {
    if (msg.type === 'metrics') {
      setMetrics(msg)
      // Metrics carry gateway health + CB states + loaded routes → also refresh gateway status cache
      qc.invalidateQueries({ queryKey: ['gateway-status'] })
      return
    }

    // Push to event feed store
    pushEvent(msg)

    // Invalidate affected React Query caches by server-emitted queryKey
    if (msg.queryKey && QUERY_KEY_MAP[msg.queryKey]) {
      QUERY_KEY_MAP[msg.queryKey].forEach(key => {
        qc.invalidateQueries({ queryKey: key })
      })
    }

    // ── Route lifecycle → gateway status + active-route count ──────────
    if (GATEWAY_ROUTE_EVENTS.has(msg.type)) {
      qc.invalidateQueries({ queryKey: ['gateway-status'] })
      qc.invalidateQueries({ queryKey: ['active-routes-count'] })
      qc.invalidateQueries({ queryKey: ['gateway-live-certs'] })
    }

    // ── Gateway config changed → refresh all gateway-related caches ───
    if (msg.type === 'gateway.config.changed') {
      qc.invalidateQueries({ queryKey: ['gateway-config'] })
      qc.invalidateQueries({ queryKey: ['gateway-tls-config'] })
    }

    // ── Audit events → refresh all audit caches ───────────────────────
    if (AUDIT_EVENTS.has(msg.type)) {
      qc.invalidateQueries({ queryKey: ['audit-events'] })
      qc.invalidateQueries({ queryKey: ['audit-requests'] })
    }

    // ── Replay events → refresh replay caches ─────────────────────────
    if (msg.type.startsWith('replay.') || msg.queryKey === 'audit') {
      qc.invalidateQueries({ queryKey: ['audit-failed'] })
      qc.invalidateQueries({ queryKey: ['replay-stats'] })
    }

    // ── Certificate events → refresh cert caches ──────────────────────
    if (msg.type.startsWith('certificate.') || msg.queryKey === 'certificates') {
      qc.invalidateQueries({ queryKey: ['cert-groups'] })
      qc.invalidateQueries({ queryKey: ['cert-group-detail'] })
      qc.invalidateQueries({ queryKey: ['cert-stats'] })
      qc.invalidateQueries({ queryKey: ['certs-active'] })
      qc.invalidateQueries({ queryKey: ['gateway-live-certs'] })
      qc.invalidateQueries({ queryKey: ['cert-groups-details-tls'] })
    }

    // ── User events → refresh user caches ─────────────────────────────
    if (msg.type.startsWith('user.') || msg.queryKey === 'users') {
      qc.invalidateQueries({ queryKey: ['users'] })
    }

    // ── Tenant events → refresh tenant caches ─────────────────────────
    if (msg.type.startsWith('tenant.') || msg.queryKey === 'tenants') {
      qc.invalidateQueries({ queryKey: ['tenants'] })
      qc.invalidateQueries({ queryKey: ['tenants-for-user-create'] })
    }
  }

  useWebSocket({
    enabled: isAuthenticated,
    onMessage: handleMessage,
    onStatusChange: setStatus,
  })

  return <>{children}</>
}

