/**
 * MSW WebSocket mock handler for development mode.
 */

import { ws } from 'msw'

const BASE = 'ws://localhost:8082'

// ─── STOMP framing ───────────────────────────────────────────────────────────

function stompFrame(command: string, headers: Record<string, string> = {}, body = ''): string {
  const h = Object.entries(headers).map(([k, v]) => `${k}:${v}`).join('\n')
  return `${command}\n${h}\n\n${body}\0`
}

function stompMessage(destination: string, body: unknown): string {
  return stompFrame(
    'MESSAGE',
    { destination, 'content-type': 'application/json', 'message-id': `msg-${Date.now()}` },
    JSON.stringify(body),
  )
}

// ─── Mock data ───────────────────────────────────────────────────────────────

function makeMetricsFrame() {
  return stompMessage('/topic/metrics', {
    type: 'metrics',
    occurredAt: new Date().toISOString(),
    circuitBreakers: {
      'route-service-cb': { state: 'CLOSED', failureRate: +(Math.random() * 5).toFixed(1), slowCallRate: +(Math.random() * 2).toFixed(1), bufferedCalls: Math.floor(Math.random() * 300 + 50) },
      'admin-cb':         { state: 'CLOSED', failureRate: 0, slowCallRate: 0, bufferedCalls: Math.floor(Math.random() * 20) },
    },
    health: { status: 'UP', components: { redis: { status: 'UP' }, kafka: { status: 'UP' } } },
  })
}

const DEMO_EVENTS = [
  { type: 'route.activated',  queryKey: 'routes',         label: 'Route activated' },
  { type: 'route.updated',    queryKey: 'routes',         label: 'Route updated' },
  { type: 'filter.attached',  queryKey: 'routes',         label: 'Filter attached to route' },
  { type: 'gateway.reloaded', queryKey: 'gateway-status', label: 'Gateway hot-reloaded ⚡' },
]

function makeEventFrame() {
  const e = DEMO_EVENTS[Math.floor(Math.random() * DEMO_EVENTS.length)]
  return stompMessage('/topic/events', {
    type: e.type,
    queryKey: e.queryKey,
    occurredAt: new Date().toISOString(),
    data: { eventId: crypto.randomUUID() },
  })
}

// ─── MSW WebSocket handler ────────────────────────────────────────────────────

const wsLink = ws.link(`${BASE}/ws/websocket`)

export const wsHandlers = [
  wsLink.addEventListener('connection', ({ client }) => {

    client.addEventListener('message', (event) => {
      const raw = typeof event.data === 'string' ? event.data : ''

      if (raw.startsWith('CONNECT')) {
        // Reply STOMP CONNECTED
        client.send(stompFrame('CONNECTED', { version: '1.2', 'heart-beat': '10000,10000', server: 'Routify-Mock/1.0' }))

        // Welcome event
        setTimeout(() => {
          client.send(stompMessage('/topic/events', {
            type: 'connected',
            message: 'Connected to Routify WebSocket (mock)',
            connectedClients: 1,
            occurredAt: new Date().toISOString(),
          }))
        }, 100)

        // Initial metrics
        setTimeout(() => { try { client.send(makeMetricsFrame()) } catch { /* ignore */ } }, 600)

        // Recurring metrics every 15s
        const metricsTimer = setInterval(() => {
          try { client.send(makeMetricsFrame()) } catch { clearInterval(metricsTimer) }
        }, 15_000)

        // Random domain event every 20s (demo live feed)
        const eventTimer = setInterval(() => {
          try { client.send(makeEventFrame()) } catch { clearInterval(eventTimer) }
        }, 20_000)
      }

      if (raw.startsWith('SEND') && raw.includes('/app/ping')) {
        client.send(stompMessage('/topic/events', {
          type: 'pong',
          occurredAt: new Date().toISOString(),
          connectedClients: 1,
        }))
      }
    })
  }),
]
