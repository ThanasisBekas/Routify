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
  routes: [['routes'], ['route']],
  filters: [['filters']],
  'gateway-config': [['gateway-config']],
  'gateway-status': [['gateway-status']],
  audit: [['audit-events'], ['audit-requests'], ['audit-failed'], ['replay-stats']],
  replay: [['audit-failed'], ['replay-stats']],
  certificates: [['cert-groups'], ['cert-group-detail'], ['cert-stats'], ['certs-active']],
  'cert-groups': [['cert-groups'], ['cert-group-detail'], ['cert-stats'], ['certs-active']],
  users: [['users']],
  tenants: [['tenants'], ['tenants-for-user-create']],
  misc: [],
}

// Events that change the number of active routes in the gateway — refresh the status count
const GATEWAY_ROUTE_EVENTS = new Set(['route.activated', 'route.deactivated', 'route.deleted', 'gateway.reloaded'])

// Events that affect audit trail
const AUDIT_EVENTS = new Set([
  'route.created',
  'route.updated',
  'route.activated',
  'route.deactivated',
  'route.deleted',
  'filter.created',
  'filter.updated',
  'filter.deleted',
  'filter.attached',
  'filter.detached',
  'gateway.reloaded',
  'gateway.config.changed',
])

const IS_MOCK = import.meta.env.VITE_MOCK === 'true'

// ─── Inner component: owns all real WebSocket hooks ───────────────────────────
// Extracted so we can conditionally mount it (IS_MOCK=false) without calling
// hooks conditionally — which would violate the Rules of Hooks.
function RealWebSocketProvider({ children }: { children: React.ReactNode }) {
  const isAuthenticated = useAuthStore((s) => s.isAuthenticated)
  const qc = useQueryClient()
  const { setStatus, pushEvent, setMetrics } = useWsStore()

  const handleMessage = (msg: WsMessage) => {
    if (msg.type === 'metrics') {
      setMetrics(msg)
      qc.invalidateQueries({ queryKey: ['gateway-status'] })
      return
    }
    pushEvent(msg)
    if (msg.queryKey && QUERY_KEY_MAP[msg.queryKey]) {
      QUERY_KEY_MAP[msg.queryKey].forEach((key) => {
        qc.invalidateQueries({ queryKey: key })
      })
    }
    if (GATEWAY_ROUTE_EVENTS.has(msg.type)) {
      qc.invalidateQueries({ queryKey: ['gateway-status'] })
      qc.invalidateQueries({ queryKey: ['active-routes-count'] })
    }
    if (msg.type === 'gateway.config.changed') {
      qc.invalidateQueries({ queryKey: ['gateway-config'] })
    }
    if (AUDIT_EVENTS.has(msg.type)) {
      qc.invalidateQueries({ queryKey: ['audit-events'] })
      qc.invalidateQueries({ queryKey: ['audit-requests'] })
    }
    if (msg.type.startsWith('replay.') || msg.queryKey === 'audit') {
      qc.invalidateQueries({ queryKey: ['audit-failed'] })
      qc.invalidateQueries({ queryKey: ['replay-stats'] })
    }
    if (msg.type.startsWith('certificate.') || msg.queryKey === 'certificates') {
      qc.invalidateQueries({ queryKey: ['cert-groups'] })
      qc.invalidateQueries({ queryKey: ['cert-group-detail'] })
      qc.invalidateQueries({ queryKey: ['cert-stats'] })
      qc.invalidateQueries({ queryKey: ['certs-active'] })
    }
    if (msg.type.startsWith('user.') || msg.queryKey === 'users') {
      qc.invalidateQueries({ queryKey: ['users'] })
    }
    if (msg.type.startsWith('tenant.') || msg.queryKey === 'tenants') {
      qc.invalidateQueries({ queryKey: ['tenants'] })
      qc.invalidateQueries({ queryKey: ['tenants-for-user-create'] })
    }
  }

  useWebSocket({ enabled: isAuthenticated, onMessage: handleMessage, onStatusChange: setStatus })
  return <>{children}</>
}

// ─── Public export ────────────────────────────────────────────────────────────

export function WebSocketProvider({ children }: { children: React.ReactNode }) {
  // In mock mode, wsStore is driven by mockWs.ts (started in main.tsx).
  // Skip the real socket by mounting only the mock pass-through.
  if (IS_MOCK) return <>{children}</>
  return <RealWebSocketProvider>{children}</RealWebSocketProvider>
}
