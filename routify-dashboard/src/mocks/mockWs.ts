/**
 * mockWs.ts — Simulates WebSocket events in mock mode.
 *
 * Since MSW v2 does not intercept native WebSocket connections (it only intercepts
 * HTTP/fetch), we skip the real WebSocket entirely in mock mode.  Instead this
 * module directly drives the Zustand wsStore with periodic synthetic events —
 * the same store that WebSocketProvider normally populates from the real socket.
 *
 * Call startMockWs() once from main.tsx after the MSW worker starts.
 * The returned cleanup function can be called to stop the simulation.
 */
import { useWsStore } from '../store/wsStore'
import type { WsMessage } from '../types/ws'

// Synthetic event catalogue — rotated in sequence
const EVENT_CYCLE: WsMessage[] = [
  {
    type: 'route.activated',
    queryKey: 'routes',
    occurredAt: '',
    message: 'Payments API activated',
    data: { routeId: 'dddddddd-0000-0000-0000-000000000001' },
  },
  { type: 'gateway.reloaded', queryKey: 'gateway-status', occurredAt: '', message: 'Gateway hot-reloaded' },
  { type: 'filter.updated', queryKey: 'filters', occurredAt: '', message: 'Rate limit filter updated' },
  { type: 'route.updated', queryKey: 'routes', occurredAt: '', message: 'Users Service route updated' },
  { type: 'certificate.uploaded', queryKey: 'certificates', occurredAt: '', message: 'New certificate uploaded' },
  { type: 'audit.request.logged', queryKey: 'audit', occurredAt: '', message: 'Request logged' },
  { type: 'user.created', queryKey: 'users', occurredAt: '', message: 'New user created' },
  { type: 'route.deactivated', queryKey: 'routes', occurredAt: '', message: 'Legacy route deactivated' },
  { type: 'gateway.config.changed', queryKey: 'gateway-config', occurredAt: '', message: 'CORS config updated' },
]

let eventIndex = 0

/** Mock metrics payload — simulates what the real gateway pushes every 10s */
function buildMetricsMessage(): WsMessage {
  const cbStates = ['CLOSED', 'CLOSED', 'HALF_OPEN'] as const
  return {
    type: 'metrics',
    occurredAt: new Date().toISOString(),
    loadedRoutes: 6,
    health: {
      status: 'UP',
      components: { redis: { status: 'UP' }, kafka: { status: 'UP' }, rabbitmq: { status: 'UP' } },
    },
    circuitBreakers: {
      'payments-cb': {
        state: cbStates[Math.floor(Math.random() * 2)],
        failureRate: parseFloat((Math.random() * 5).toFixed(1)),
        slowCallRate: 0,
        bufferedCalls: 10,
      },
      'users-cb': {
        state: 'CLOSED',
        failureRate: 0,
        slowCallRate: parseFloat((Math.random() * 10).toFixed(1)),
        bufferedCalls: 8,
      },
      'orders-cb': {
        state: cbStates[Math.floor(Math.random() * 3)],
        failureRate: parseFloat((Math.random() * 60).toFixed(1)),
        slowCallRate: 20,
        bufferedCalls: 3,
      },
    },
  }
}

export function startMockWs(): () => void {
  const store = useWsStore.getState()

  // Immediately mark as connected so the UI shows the green indicator
  store.setStatus('CONNECTED')

  // Push a "connected" event so connectedClients count shows
  store.pushEvent({ type: 'connected', occurredAt: new Date().toISOString(), connectedClients: 1 })

  // Push initial metrics so gateway status panel populates right away
  store.setMetrics(buildMetricsMessage())

  // Rotate domain events every 8 seconds
  const eventTimer = setInterval(() => {
    const event: WsMessage = {
      ...EVENT_CYCLE[eventIndex % EVENT_CYCLE.length],
      occurredAt: new Date().toISOString(),
    }
    store.pushEvent(event)
    eventIndex++
  }, 8_000)

  // Push metrics every 12 seconds
  const metricsTimer = setInterval(() => {
    store.setMetrics(buildMetricsMessage())
  }, 12_000)

  // Return a cleanup function so callers can stop the simulation
  return () => {
    clearInterval(eventTimer)
    clearInterval(metricsTimer)
    store.setStatus('DISCONNECTED')
  }
}
