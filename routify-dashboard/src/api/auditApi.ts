// auditApi.ts — All audit requests go through routify-admin-api (the BFF).
// admin-api communicates with audit-service via RabbitMQ (queries).
import apiClient from './client'
import type {
  Page, AuditEntry, RequestLogDto, RouteStats,
  FailedRequestDto, ReplayStats, ReplayResult, BulkReplayResult,
} from '../types'

const ADMIN_BASE = '/api/v1/admin/audit'

export const auditApi = {
  // ─── Audit events ──────────────────────────────────────────────────────────
  listEvents: (params?: { page?: number; size?: number; eventType?: string; aggregateType?: string; from?: string; to?: string }) =>
    apiClient.get<Page<AuditEntry>>(`${ADMIN_BASE}/events`, { params }).then(r => r.data),

  getRouteHistory: (routeId: string) =>
    apiClient.get<AuditEntry[]>(`${ADMIN_BASE}/events/route/${routeId}`).then(r => r.data),

  getFilterHistory: (filterId: string) =>
    apiClient.get<AuditEntry[]>(`${ADMIN_BASE}/events/filter/${filterId}`).then(r => r.data),

  // ─── Request logs ──────────────────────────────────────────────────────────
  listRequests: (params?: { page?: number; size?: number; routeId?: string; from?: string; to?: string }) =>
    apiClient.get<Page<RequestLogDto>>(`${ADMIN_BASE}/requests`, { params }).then(r => r.data),

  getRouteStats: (routeId: string) =>
    apiClient.get<RouteStats>(`${ADMIN_BASE}/requests/stats/${routeId}`).then(r => r.data),

  // ─── Replay (proxied through admin-api → audit-service) ─────────────────
  /** All failed requests (any replay status) */
  listFailed: (params?: { page?: number; size?: number; routeId?: string }) =>
    apiClient.get<Page<FailedRequestDto>>(`${ADMIN_BASE}/replay/failed`, { params }).then(r => r.data),

  /** Requests pending replay (PENDING or FAILED replay status) */
  listPendingReplay: (params?: { page?: number; size?: number; routeId?: string }) =>
    apiClient.get<Page<FailedRequestDto>>(`${ADMIN_BASE}/replay/pending`, { params }).then(r => r.data),

  /** Replay statistics */
  getReplayStats: () =>
    apiClient.get<ReplayStats>(`${ADMIN_BASE}/replay/stats`).then(r => r.data),

  /** Replay a single failed request */
  replaySingle: (id: string) =>
    apiClient.post<ReplayResult>(`${ADMIN_BASE}/replay/${id}`).then(r => r.data),

  /** Bulk replay all pending failed requests */
  replayBulk: (limit = 50) =>
    apiClient.post<BulkReplayResult>(`${ADMIN_BASE}/replay/bulk`, null, { params: { limit } }).then(r => r.data),
}
