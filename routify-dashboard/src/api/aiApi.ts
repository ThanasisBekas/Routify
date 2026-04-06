// aiApi.ts — AI filter and modifier endpoints, proxied through routify-admin-api.
import apiClient from './client'
import type {
  AiModificationTestRequest,
  AiModificationTestResult,
  AiModifierStats,
  AiFilterStats,
  AiFilterDecisionEntry,
  AiModifierDecisionEntry,
  Page,
} from '../types'

const AI_BASE = '/api/v1/admin/ai'

export const aiApi = {
  // ─── AI Filter (evaluation/test) ──────────────────────────────────────────

  /**
   * Tests a natural-language policy against a synthetic sample request (dry-run).
   * No filter is activated on any live route — for dashboard "Test Policy" use only.
   */
  testPolicy: (req: { policyDescription: string; sampleRequest: AiModificationTestRequest['sampleRequest'] }) =>
    apiClient
      .post<{ action: string; reason: string; confidence: number }>(`${AI_BASE}-filter/test-policy`, req)
      .then((r) => r.data),

  // ─── AI Modifier (mutation/test) ──────────────────────────────────────────

  /**
   * Tests a modification prompt against a synthetic sample request (dry-run).
   * Returns the original and mutated payload for visual diff in the dashboard.
   */
  testModification: (req: AiModificationTestRequest) =>
    apiClient.post<AiModificationTestResult>(`${AI_BASE}-modifier/test-modification`, req).then((r) => r.data),

  // ─── Audit stats ──────────────────────────────────────────────────────────

  /** AI filter decision stats for a specific route (last 24h by default). */
  getAiFilterStats: (routeId: string, from?: string, to?: string) =>
    apiClient
      .get<AiFilterStats>('/api/v1/admin/audit/ai-filter/stats', { params: { routeId, from, to } })
      .then((r) => r.data),

  /** Paginated AI filter decision log for a specific route. */
  listAiFilterDecisions: (routeId: string, params?: { page?: number; size?: number; action?: string }) =>
    apiClient
      .get<Page<AiFilterDecisionEntry>>('/api/v1/admin/audit/ai-filter/decisions', {
        params: { routeId, ...params },
      })
      .then((r) => r.data),

  /** AI modifier decision stats for a specific route (last 24h by default). */
  getAiModifierStats: (routeId: string, from?: string, to?: string) =>
    apiClient
      .get<AiModifierStats>('/api/v1/admin/audit/ai-modifier/stats', { params: { routeId, from, to } })
      .then((r) => r.data),

  /** Paginated AI modifier decision log for a specific route. */
  listAiModifierDecisions: (routeId: string, params?: { page?: number; size?: number }) =>
    apiClient
      .get<Page<AiModifierDecisionEntry>>('/api/v1/admin/audit/ai-modifier/decisions', {
        params: { routeId, ...params },
      })
      .then((r) => r.data),
}
