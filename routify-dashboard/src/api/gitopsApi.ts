import apiClient from './client'
import type { GitOpsStatus, ReconciliationResult } from '../types'

const BASE = '/api/v1/admin/gitops'

export const gitopsApi = {
  /** Get current GitOps agent status. */
  getStatus: () => apiClient.get<GitOpsStatus>(`${BASE}/status`).then((r) => r.data),

  /** Get reconciliation history (last 50 results). */
  getHistory: () => apiClient.get<ReconciliationResult[]>(`${BASE}/history`).then((r) => r.data),

  /** Trigger an immediate reconciliation (Sync Now). */
  triggerSync: () =>
    apiClient.post<{ status: string; outcome: string }>(`${BASE}/sync`).then((r) => r.data),
}

