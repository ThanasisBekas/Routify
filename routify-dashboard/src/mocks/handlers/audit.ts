import { http, HttpResponse, delay } from 'msw'
import { auditEvents, requestLogs, failedRequests, replayStats, buildPage } from '../db'
import type { AuditEntry, RequestLogDto } from '../../types'

const BASE = '/api/v1/admin/audit'

export const auditHandlers = [
  // ─── Audit events (paginated, filterable) ─────────────────────────────────────
  http.get(`${BASE}/events`, async ({ request }) => {
    await delay(200)
    const url         = new URL(request.url)
    const page        = parseInt(url.searchParams.get('page') ?? '0', 10)
    const size        = parseInt(url.searchParams.get('size') ?? '20', 10)
    const eventType   = url.searchParams.get('eventType')
    const aggType     = url.searchParams.get('aggregateType')

    let items: AuditEntry[] = [...auditEvents].sort((a, b) => b.occurredAt.localeCompare(a.occurredAt))
    if (eventType) items = items.filter(e => e.eventType === eventType)
    if (aggType)   items = items.filter(e => e.aggregateType === aggType)
    return HttpResponse.json(buildPage(items, page, size))
  }),

  // ─── Route audit history ──────────────────────────────────────────────────────
  http.get(`${BASE}/events/route/:routeId`, async ({ params }) => {
    await delay(150)
    const items = auditEvents
      .filter(e => e.aggregateId === params.routeId)
      .sort((a, b) => b.occurredAt.localeCompare(a.occurredAt))
    return HttpResponse.json(items)
  }),

  // ─── Filter audit history ─────────────────────────────────────────────────────
  http.get(`${BASE}/events/filter/:filterId`, async ({ params }) => {
    await delay(150)
    const items = auditEvents
      .filter(e => e.aggregateId === params.filterId)
      .sort((a, b) => b.occurredAt.localeCompare(a.occurredAt))
    return HttpResponse.json(items)
  }),

  // ─── Request logs (paginated) ─────────────────────────────────────────────────
  http.get(`${BASE}/requests`, async ({ request }) => {
    await delay(200)
    const url     = new URL(request.url)
    const page    = parseInt(url.searchParams.get('page') ?? '0', 10)
    const size    = parseInt(url.searchParams.get('size') ?? '20', 10)
    const routeId = url.searchParams.get('routeId')
    let items: RequestLogDto[] = [...requestLogs].sort((a, b) => b.requestedAt.localeCompare(a.requestedAt))
    if (routeId) items = items.filter(r => r.routeId === routeId)
    return HttpResponse.json(buildPage(items, page, size))
  }),

  // ─── Route request stats ──────────────────────────────────────────────────────
  http.get(`${BASE}/requests/stats/:routeId`, async ({ params }) => {
    await delay(150)
    const reqs = requestLogs.filter(r => r.routeId === params.routeId)
    return HttpResponse.json({
      routeId:        params.routeId,
      totalRequests:  reqs.length,
      avgDurationMs:  Math.round(reqs.reduce((sum, r) => sum + (r.durationMs ?? 0), 0) / (reqs.length || 1)),
      maxDurationMs:  Math.max(...reqs.map(r => r.durationMs ?? 0)),
      errorCount:     reqs.filter(r => (r.responseStatus ?? 0) >= 500).length,
    })
  }),

  // ─── Replay: failed list ──────────────────────────────────────────────────────
  http.get(`${BASE}/replay/failed`, async ({ request }) => {
    await delay(200)
    const url     = new URL(request.url)
    const page    = parseInt(url.searchParams.get('page') ?? '0', 10)
    const size    = parseInt(url.searchParams.get('size') ?? '20', 10)
    const routeId = url.searchParams.get('routeId')
    let items     = [...failedRequests]
    if (routeId) items = items.filter(r => r.routeId === routeId)
    return HttpResponse.json(buildPage(items, page, size))
  }),

  // ─── Replay: pending list ─────────────────────────────────────────────────────
  http.get(`${BASE}/replay/pending`, async ({ request }) => {
    await delay(200)
    const url     = new URL(request.url)
    const page    = parseInt(url.searchParams.get('page') ?? '0', 10)
    const size    = parseInt(url.searchParams.get('size') ?? '20', 10)
    const items   = failedRequests.filter(r => !r.replayStatus || r.replayStatus === 'PENDING' || r.replayStatus === 'FAILED')
    return HttpResponse.json(buildPage(items, page, size))
  }),

  // ─── Replay stats ─────────────────────────────────────────────────────────────
  http.get(`${BASE}/replay/stats`, async () => {
    await delay(150)
    return HttpResponse.json(replayStats)
  }),

  // ─── Replay single ────────────────────────────────────────────────────────────
  http.post(`${BASE}/replay/:id`, async ({ params }) => {
    await delay(800)
    const idx = failedRequests.findIndex(r => r.id === params.id)
    if (idx < 0) return HttpResponse.json({ status: 404, detail: 'Request not found' }, { status: 404 })
    failedRequests[idx] = { ...failedRequests[idx], replayStatus: 'SUCCEEDED', replayCount: (failedRequests[idx].replayCount ?? 0) + 1, replayedAt: new Date().toISOString(), replayResponseStatus: 200 }
    return HttpResponse.json({ requestLogId: params.id, outcome: 'SUCCEEDED', responseStatus: 200, message: 'Replay successful.' })
  }),

  // ─── Replay bulk ─────────────────────────────────────────────────────────────
  http.post(`${BASE}/replay/bulk`, async ({ request }) => {
    await delay(1200)
    const url   = new URL(request.url)
    const limit = parseInt(url.searchParams.get('limit') ?? '50', 10)
    const pending = failedRequests.filter(r => !r.replayStatus || r.replayStatus === 'PENDING')
    const toReplay = pending.slice(0, limit)
    toReplay.forEach(r => {
      const idx = failedRequests.findIndex(f => f.id === r.id)
      if (idx >= 0) {
        failedRequests[idx] = { ...failedRequests[idx], replayStatus: 'SUCCEEDED', replayCount: (failedRequests[idx].replayCount ?? 0) + 1, replayedAt: new Date().toISOString(), replayResponseStatus: 200 }
      }
    })
    return HttpResponse.json({ total: toReplay.length, succeeded: toReplay.length, failed: 0, skipped: 0 })
  }),
]

