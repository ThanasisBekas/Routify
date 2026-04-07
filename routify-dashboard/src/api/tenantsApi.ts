import apiClient from './client'
import type { TenantDto, TenantUsageCurrent, TenantUsageHistory } from '../types'

export interface WorkspaceOption {
  name: string
  slug: string
}

export interface CreateWorkspaceRequest {
  name: string
  slug: string
  plan: string
  contactEmail?: string
}

export interface UpdateWorkspaceRequest {
  name?: string
  plan?: string
  contactEmail?: string
}

export const tenantsApi = {
  /**
   * Public — lists active workspaces (name + slug) for the login dropdown.
   * No auth token required.
   */
  listWorkspaces: () =>
    apiClient.get<{ workspaces: WorkspaceOption[] }>('/api/v1/admin/tenants/workspaces').then((r) => r.data.workspaces),

  /** Paginated tenant list — requires auth. */
  list: (page = 0, size = 20) =>
    apiClient
      .get<{
        content: TenantDto[]
        totalElements: number
        totalPages: number
        page: number
        size: number
      }>('/api/v1/admin/tenants', { params: { page, size } })
      .then((r) => r.data),

  /** Single tenant — requires auth. */
  get: (id: string) => apiClient.get<TenantDto>(`/api/v1/admin/tenants/${id}`).then((r) => r.data),

  /** Create workspace — SUPER_ADMIN only. */
  create: (req: CreateWorkspaceRequest) => apiClient.post<TenantDto>('/api/v1/admin/tenants', req).then((r) => r.data),

  /** Update workspace — SUPER_ADMIN only. */
  update: (id: string, req: UpdateWorkspaceRequest) =>
    apiClient.put<TenantDto>(`/api/v1/admin/tenants/${id}`, req).then((r) => r.data),

  /** Suspend workspace. */
  suspend: (id: string, reason?: string) =>
    apiClient
      .post<TenantDto>(`/api/v1/admin/tenants/${id}/suspend`, null, {
        params: { reason: reason ?? 'Administrative action' },
      })
      .then((r) => r.data),

  /** Reactivate workspace. */
  reactivate: (id: string) => apiClient.post<TenantDto>(`/api/v1/admin/tenants/${id}/reactivate`).then((r) => r.data),

  /** Current usage vs plan limits — requires auth. */
  getUsage: (id: string) => apiClient.get<TenantUsageCurrent>(`/api/v1/admin/tenants/${id}/usage`).then((r) => r.data),

  /** Daily usage history (default 30 days) — requires auth. */
  getUsageHistory: (id: string, days = 30) =>
    apiClient
      .get<TenantUsageHistory>(`/api/v1/admin/tenants/${id}/usage/history`, { params: { days } })
      .then((r) => r.data),
}
