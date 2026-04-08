import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest'
import { renderHook, act } from '@testing-library/react'
import { useWebSocket } from '../../hooks/useWebSocket'
import { useAuthStore } from '../../store/authStore'

// ─── WebSocket mock ────────────────────────────────────────────────────────────

type WsListener = (event: { data?: string }) => void

class MockWebSocket {
  static OPEN = 1
  static CLOSED = 3
  static instances: MockWebSocket[] = []

  url: string
  readyState = MockWebSocket.OPEN
  onopen: (() => void) | null = null
  onmessage: WsListener | null = null
  onclose: (() => void) | null = null
  onerror: (() => void) | null = null
  sent: string[] = []

  constructor(url: string) {
    this.url = url
    MockWebSocket.instances.push(this)
    // Simulate async open
    setTimeout(() => this.onopen?.(), 0)
  }

  send(data: string) {
    this.sent.push(data)
  }

  close() {
    this.readyState = MockWebSocket.CLOSED
  }
}

// ─── Tests ─────────────────────────────────────────────────────────────────────

describe('useWebSocket', () => {
  const originalWs = globalThis.WebSocket

  beforeEach(() => {
    MockWebSocket.instances = []
    globalThis.WebSocket = MockWebSocket as unknown as typeof WebSocket
    useAuthStore.getState().logout()
  })

  afterEach(() => {
    globalThis.WebSocket = originalWs
    useAuthStore.getState().logout()
  })

  it('opens a WebSocket connection', async () => {
    renderHook(() =>
      useWebSocket({
        url: 'ws://localhost:8082/ws/websocket',
        enabled: true,
      }),
    )

    await vi.waitFor(() => {
      expect(MockWebSocket.instances.length).toBeGreaterThanOrEqual(1)
    })
  })

  it('sends STOMP CONNECT frame on open', async () => {
    renderHook(() =>
      useWebSocket({
        url: 'ws://localhost:8082/ws/websocket',
        enabled: true,
      }),
    )

    // Wait for the async onopen
    await vi.waitFor(() => {
      const ws = MockWebSocket.instances[0]
      expect(ws?.sent.some((s) => s.startsWith('CONNECT'))).toBe(true)
    })
  })

  it('includes Authorization header when token is present', async () => {
    useAuthStore.getState().setTokens('my-jwt')

    renderHook(() =>
      useWebSocket({
        url: 'ws://localhost:8082/ws/websocket',
        enabled: true,
      }),
    )

    await vi.waitFor(() => {
      const ws = MockWebSocket.instances[0]
      const connectFrame = ws?.sent.find((s) => s.startsWith('CONNECT'))
      expect(connectFrame).toContain('Authorization:Bearer my-jwt')
    })
  })

  it('does not connect when enabled=false', async () => {
    renderHook(() =>
      useWebSocket({
        url: 'ws://localhost:8082/ws/websocket',
        enabled: false,
      }),
    )

    await new Promise((r) => setTimeout(r, 50))
    expect(MockWebSocket.instances).toHaveLength(0)
  })

  it('calls onStatusChange with CONNECTING', async () => {
    const statusChanges: string[] = []

    renderHook(() =>
      useWebSocket({
        url: 'ws://localhost:8082/ws/websocket',
        onStatusChange: (s) => statusChanges.push(s),
      }),
    )

    await vi.waitFor(() => {
      expect(statusChanges).toContain('CONNECTING')
    })
  })

  it('subscribes to /topic/events on CONNECTED', async () => {
    renderHook(() =>
      useWebSocket({
        url: 'ws://localhost:8082/ws/websocket',
      }),
    )

    await vi.waitFor(() => {
      const ws = MockWebSocket.instances[0]
      expect(ws).toBeDefined()
      // Simulate CONNECTED frame
      act(() => {
        ws.onmessage?.({ data: 'CONNECTED\nversion:1.2\n\n\0' })
      })
    })

    const ws = MockWebSocket.instances[0]
    const subscribes = ws.sent.filter((s) => s.startsWith('SUBSCRIBE'))
    expect(subscribes.length).toBeGreaterThanOrEqual(1)
    expect(subscribes.some((s) => s.includes('/topic/events'))).toBe(true)
  })

  it('calls onMessage when MESSAGE frame arrives', async () => {
    const messages: unknown[] = []

    renderHook(() =>
      useWebSocket({
        url: 'ws://localhost:8082/ws/websocket',
        onMessage: (msg) => messages.push(msg),
      }),
    )

    await vi.waitFor(() => {
      expect(MockWebSocket.instances[0]).toBeDefined()
    })

    const ws = MockWebSocket.instances[0]
    // Simulate CONNECTED
    act(() => {
      ws.onmessage?.({ data: 'CONNECTED\nversion:1.2\n\n\0' })
    })

    // Simulate MESSAGE
    act(() => {
      const body = JSON.stringify({ type: 'route.created', occurredAt: '2025-01-01T00:00:00Z' })
      ws.onmessage?.({ data: `MESSAGE\ndestination:/topic/events\n\n${body}\0` })
    })

    expect(messages).toHaveLength(1)
    expect((messages[0] as Record<string, unknown>).type).toBe('route.created')
  })
})
