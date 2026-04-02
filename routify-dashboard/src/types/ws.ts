/**
 * WebSocket message types broadcast by routify-admin-api.
 */

export type WsEventType =
  | 'connected' | 'pong'
  | 'route.created'   | 'route.updated'    | 'route.activated'
  | 'route.deactivated' | 'route.deleted'
  | 'filter.created'  | 'filter.updated'   | 'filter.deleted'
  | 'filter.attached' | 'filter.detached'
  | 'gateway.reloaded' | 'gateway.config.changed'
  | 'metrics'

/**
 * Every message from the server has this shape.
 * `queryKey` is the React Query cache key the dashboard should invalidate.
 * `data` is the raw domain event payload (optional).
 */
export interface WsMessage {
  type: WsEventType | string
  queryKey?: string
  occurredAt: string
  message?: string
  connectedClients?: number
  // metrics-specific
  circuitBreakers?: Record<string, CircuitBreakerState>
  health?: { status: string; components?: Record<string, { status: string }> }
  /** Number of routes currently loaded in the live gateway routing table */
  loadedRoutes?: number
  // raw domain event payload
  data?: Record<string, unknown>
}

export interface CircuitBreakerState {
  state: 'CLOSED' | 'OPEN' | 'HALF_OPEN'
  failureRate: number
  slowCallRate: number
  bufferedCalls: number
}

export type WsStatus = 'CONNECTING' | 'CONNECTED' | 'DISCONNECTED' | 'RECONNECTING'

