/**
 * useWebSocket — production-grade WebSocket hook.
 *
 * Features:
 * - Native browser WebSocket (no library dependency)
 * - STOMP-lite framing over raw WS (compatible with Spring's simple broker)
 * - Exponential backoff reconnection (500ms → 30s cap, up to 10 attempts)
 * - Heartbeat / ping-pong to detect stale connections
 * - Typed message dispatch via subscriber callbacks
 * - Automatic token injection in CONNECT frame
 * - Singleton per URL — shared across all hook consumers in the same React tree
 *   via a module-level registry, so only ONE socket is ever opened
 */

import { useEffect, useRef, useCallback } from 'react'
import { useAuthStore } from '../store/authStore'
import type { WsMessage, WsStatus } from '../types/ws'

// ─── STOMP frame helpers ──────────────────────────────────────────────────────

function stompFrame(command: string, headers: Record<string, string> = {}, body = ''): string {
  const headerLines = Object.entries(headers).map(([k, v]) => `${k}:${v}`).join('\n')
  return `${command}\n${headerLines}\n\n${body}\0`
}

function parseStompFrame(raw: string): { command: string; headers: Record<string, string>; body: string } | null {
  try {
    const nullIdx = raw.indexOf('\0')
    const content = nullIdx >= 0 ? raw.slice(0, nullIdx) : raw
    const firstBlank = content.indexOf('\n\n')
    const headerPart = firstBlank >= 0 ? content.slice(0, firstBlank) : content
    const body       = firstBlank >= 0 ? content.slice(firstBlank + 2) : ''
    const lines = headerPart.split('\n')
    const command = lines[0].trim()
    const headers: Record<string, string> = {}
    for (let i = 1; i < lines.length; i++) {
      const colonIdx = lines[i].indexOf(':')
      if (colonIdx >= 0) {
        headers[lines[i].slice(0, colonIdx).trim()] = lines[i].slice(colonIdx + 1).trim()
      }
    }
    return { command, headers, body }
  } catch {
    return null
  }
}

// ─── Singleton connection registry ───────────────────────────────────────────

type Subscriber = (msg: WsMessage) => void
type StatusListener = (status: WsStatus) => void

interface Connection {
  ws: WebSocket
  subscribers: Set<Subscriber>
  statusListeners: Set<StatusListener>
  status: WsStatus
  reconnectAttempts: number
  reconnectTimer: ReturnType<typeof setTimeout> | null
  heartbeatTimer: ReturnType<typeof setInterval> | null
  subscriptionId: string
  /** Incremented each time closeConnection is called; openSocket captures it
   *  so stale async callbacks (onopen/onclose/etc.) become no-ops. */
  generation: number
}

const connections = new Map<string, Connection>()

function getOrCreateConnection(registryKey: string, token: string | null, wsUrl?: string): Connection {
  const existing = connections.get(registryKey)

  if (existing) {
    // If we now have a token but the connection was established without one
    // (e.g. bootstrap auth resolved after the WS was first attempted), close
    // the old connection so a new one is opened with the token in the URL.
    // Only do this when the socket is truly dead (DISCONNECTED), NOT while it
    // is still CONNECTING — that would cause the "closed before established" error.
    if (token && existing.status === 'DISCONNECTED') {
      closeConnection(registryKey)
    } else {
      return existing
    }
  }

  const conn: Connection = {
    ws: null!,
    subscribers: new Set(),
    statusListeners: new Set(),
    status: 'CONNECTING',
    reconnectAttempts: 0,
    reconnectTimer: null,
    heartbeatTimer: null,
    subscriptionId: `sub-${Math.random().toString(36).slice(2)}`,
    generation: 0,
  }

  connections.set(registryKey, conn)
  openSocket(registryKey, token, conn, wsUrl ?? registryKey)
  return conn
}

function openSocket(registryKey: string, token: string | null, conn: Connection, wsUrl?: string) {
  setStatus(conn, 'CONNECTING')

  // Capture the generation at the time this socket is opened.
  // If closeConnection() is called before the socket finishes connecting,
  // the generation will have been incremented and all callbacks become no-ops.
  const myGeneration = conn.generation

  const url = wsUrl ?? registryKey
  try {
    conn.ws = new WebSocket(url)
  } catch {
    if (conn.generation === myGeneration) {
      scheduleReconnect(registryKey, token, conn)
    }
    return
  }

  conn.ws.onopen = () => {
    if (conn.generation !== myGeneration) {
      // This socket was superseded; close it silently
      try { conn.ws.close() } catch { /* ignore */ }
      return
    }
    conn.reconnectAttempts = 0

    // Send STOMP CONNECT frame — token is already in the URL query string,
    // but also send it in the header for STOMP-level brokers that check it.
    const headers: Record<string, string> = {
      'accept-version': '1.2',
      'heart-beat': '10000,10000',
    }
    if (token) headers['Authorization'] = `Bearer ${token}`
    conn.ws.send(stompFrame('CONNECT', headers))
  }

  conn.ws.onmessage = (event: MessageEvent) => {
    if (conn.generation !== myGeneration) return
    if (typeof event.data !== 'string') return

    // Keep-alive frame
    if (event.data === '\n' || event.data.trim() === '') return

    const frame = parseStompFrame(event.data)
    if (!frame) return

    switch (frame.command) {
      case 'CONNECTED': {
        setStatus(conn, 'CONNECTED')
        // Subscribe to topics
        conn.ws.send(stompFrame('SUBSCRIBE', { id: conn.subscriptionId,       destination: '/topic/events' }))
        conn.ws.send(stompFrame('SUBSCRIBE', { id: conn.subscriptionId + '-m', destination: '/topic/metrics' }))
        conn.ws.send(stompFrame('SUBSCRIBE', { id: conn.subscriptionId + '-a', destination: '/topic/audit' }))
        startHeartbeat(registryKey, null, conn, myGeneration)
        break
      }
      case 'MESSAGE': {
        if (!frame.body) return
        try {
          const msg: WsMessage = JSON.parse(frame.body)
          conn.subscribers.forEach(cb => {
            try { cb(msg) } catch { /* subscriber error shouldn't crash the loop */ }
          })
        } catch { /* ignore malformed JSON */ }
        break
      }
      case 'ERROR': {
        console.warn('[WS] STOMP error:', frame.body)
        break
      }
    }
  }

  conn.ws.onclose = () => {
    if (conn.generation !== myGeneration) return
    stopHeartbeat(conn)
    if (conn.status === 'CONNECTED') {
      const latestToken = useAuthStore.getState().accessToken
      scheduleReconnect(registryKey, latestToken, conn)
    }
  }

  conn.ws.onerror = () => {
    if (conn.generation !== myGeneration) return
    stopHeartbeat(conn)
    setStatus(conn, 'DISCONNECTED')
    const latestToken = useAuthStore.getState().accessToken
    scheduleReconnect(registryKey, latestToken, conn)
  }
}

function setStatus(conn: Connection, status: WsStatus) {
  conn.status = status
  conn.statusListeners.forEach(cb => {
    try { cb(status) } catch { /* ignore */ }
  })
}

function scheduleReconnect(registryKey: string, _capturedToken: string | null, conn: Connection) {
  if (conn.reconnectTimer) return // already scheduled

  const MAX_ATTEMPTS = 10
  const BASE_DELAY_MS = 500
  const MAX_DELAY_MS  = 30_000

  if (conn.reconnectAttempts >= MAX_ATTEMPTS) {
    setStatus(conn, 'DISCONNECTED')
    return
  }

  const delay = Math.min(BASE_DELAY_MS * 2 ** conn.reconnectAttempts, MAX_DELAY_MS)
  conn.reconnectAttempts++
  setStatus(conn, 'RECONNECTING')

  conn.reconnectTimer = setTimeout(() => {
    conn.reconnectTimer = null
    // Always use the latest token so reconnects after login send the correct token
    const latestToken = useAuthStore.getState().accessToken
    // Rebuild the WS URL with the current token for the reconnect attempt
    const wsUrl = buildWsUrl(latestToken)
    openSocket(registryKey, latestToken, conn, wsUrl)
  }, delay)
}

function startHeartbeat(registryKey: string, _capturedToken: string | null, conn: Connection, myGeneration: number) {
  stopHeartbeat(conn)
  conn.heartbeatTimer = setInterval(() => {
    if (conn.generation !== myGeneration) {
      stopHeartbeat(conn)
      return
    }
    if (conn.ws.readyState === WebSocket.OPEN) {
      // STOMP heart-beat
      conn.ws.send('\n')
    } else {
      stopHeartbeat(conn)
      const latestToken = useAuthStore.getState().accessToken
      scheduleReconnect(registryKey, latestToken, conn)
    }
  }, 10_000)
}

function stopHeartbeat(conn: Connection) {
  if (conn.heartbeatTimer) {
    clearInterval(conn.heartbeatTimer)
    conn.heartbeatTimer = null
  }
}

function closeConnection(url: string) {
  const conn = connections.get(url)
  if (!conn) return
  // Increment generation first so any in-flight socket callbacks become no-ops
  conn.generation++
  if (conn.reconnectTimer) clearTimeout(conn.reconnectTimer)
  conn.reconnectTimer = null
  stopHeartbeat(conn)
  try { conn.ws?.close() } catch { /* ignore */ }
  connections.delete(url)
}

// ─── React hook ──────────────────────────────────────────────────────────────

interface UseWebSocketOptions {
  /** Called for every message on any subscribed topic */
  onMessage?: (msg: WsMessage) => void
  /** Called when connection status changes */
  onStatusChange?: (status: WsStatus) => void
  /** Override the WS URL (default: derived from VITE_API_BASE_URL) */
  url?: string
  /** If false, don't connect (useful for conditional use) */
  enabled?: boolean
}

export function useWebSocket({
  onMessage,
  onStatusChange,
  url: urlOverride,
  enabled = true,
}: UseWebSocketOptions = {}) {

  // Use a stable base URL (without token) as the singleton registry key so
  // the key doesn't change on every token rotation.
  const baseUrl = urlOverride ?? buildWsUrl()

  const onMessageRef    = useRef(onMessage)
  const onStatusRef     = useRef(onStatusChange)
  onMessageRef.current  = onMessage
  onStatusRef.current   = onStatusChange

  const send = useCallback((destination: string, body: unknown) => {
    const conn = connections.get(baseUrl)
    if (!conn || conn.ws.readyState !== WebSocket.OPEN) return
    conn.ws.send(stompFrame('SEND', { destination }, JSON.stringify(body)))
  }, [baseUrl])

  const ping = useCallback(() => send('/app/ping', {}), [send])

  useEffect(() => {
    if (!enabled) return

    // Always read the latest token at connection time; don't add `token` as a
    // dependency so a token rotation never tears down an active connection.
    const latestToken = useAuthStore.getState().accessToken
    const latestWsUrl = urlOverride ?? buildWsUrl(latestToken)

    const conn = getOrCreateConnection(baseUrl, latestToken, latestWsUrl)

    const msgCb: Subscriber = (msg) => onMessageRef.current?.(msg)
    const statusCb: StatusListener = (s) => onStatusRef.current?.(s)

    conn.subscribers.add(msgCb)
    conn.statusListeners.add(statusCb)

    // Fire current status immediately so component gets the right initial state
    onStatusRef.current?.(conn.status)

    return () => {
      const current = connections.get(baseUrl)
      if (current) {
        current.subscribers.delete(msgCb)
        current.statusListeners.delete(statusCb)
        // Only close the actual socket when ALL subscribers have unsubscribed
        if (current.subscribers.size === 0 && current.statusListeners.size === 0) {
          closeConnection(baseUrl)
        }
      }
    }
  }, [baseUrl, enabled, urlOverride])

  return { send, ping }
}

// ─── URL builder ─────────────────────────────────────────────────────────────

function buildWsUrl(token?: string | null): string {
  const apiBase = import.meta.env.VITE_API_BASE_URL ?? 'http://localhost:8082'
  // Convert http(s):// → ws(s)://
  const wsBase = apiBase.replace(/^http/, 'ws')
  const base = `${wsBase}/ws/websocket`
  // Pass the JWT as ?token= so JwtAuthFilter can authenticate the HTTP upgrade
  // request (browsers cannot set Authorization headers on native WebSocket).
  return token ? `${base}?token=${encodeURIComponent(token)}` : base
}

