/**
 * WebSocketProvider — mounts at the app root and manages the single
 * shared WebSocket connection for the entire dashboard.
 *
 * All components that need real-time data read from wsStore (Zustand)
 * instead of each hooking their own connection.
 *
 * The provider also drives React Query cache invalidation globally:
 * when a WS event arrives it calls qc.invalidateQueries() for the
 * affected query key so all active queries for that resource auto-refetch.
 */
import { useQueryClient } from '@tanstack/react-query'
import { useWebSocket } from '../hooks/useWebSocket'
import { useWsStore } from '../store/wsStore'
import { useAuthStore } from '../store/authStore'
import type { WsMessage } from '../types/ws'

// Map from server-emitted queryKey to React Query cache keys to invalidate
const QUERY_KEY_MAP: Record<string, string[][]> = {
  routes:            [['routes']],
  filters:           [['filters']],
  'gateway-config':  [['gateway-config']],
  'gateway-status':  [['gateway-status']],
  misc:              [],
}

// Events that change the number of active routes in the gateway — refresh the status count
const GATEWAY_ROUTE_EVENTS = new Set([
  'route.activated',
  'route.deactivated',
  'route.deleted',
  'gateway.reloaded',
])

export function WebSocketProvider({ children }: { children: React.ReactNode }) {
  const isAuthenticated = useAuthStore(s => s.isAuthenticated)
  const qc = useQueryClient()
  const { setStatus, pushEvent, setMetrics } = useWsStore()

  const handleMessage = (msg: WsMessage) => {
    if (msg.type === 'metrics') {
      setMetrics(msg)
      return
    }

    // Push to event feed store
    pushEvent(msg)

    // Invalidate affected React Query caches
    if (msg.queryKey && QUERY_KEY_MAP[msg.queryKey]) {
      QUERY_KEY_MAP[msg.queryKey].forEach(key => {
        qc.invalidateQueries({ queryKey: key })
      })
    }

    // Route lifecycle events change the active-route count in the gateway
    if (GATEWAY_ROUTE_EVENTS.has(msg.type)) {
      qc.invalidateQueries({ queryKey: ['gateway-status'] })
      qc.invalidateQueries({ queryKey: ['active-routes-count'] })
    }

    // Audit events invalidate audit cache
    if (msg.queryKey === 'routes' || ['filter.attached','filter.detached'].includes(msg.type)) {
      qc.invalidateQueries({ queryKey: ['audit-events'] })
    }
  }

  useWebSocket({
    enabled: isAuthenticated,
    onMessage: handleMessage,
    onStatusChange: setStatus,
  })

  return <>{children}</>
}

