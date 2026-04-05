import apiClient from './client'
import type { Page, UserDto, CreateUserRequest, UserRole } from '../types'

// All dashboard requests go through routify-admin-api (the BFF).
const BASE = '/api/v1/admin/users'

export const usersApi = {
  list: (params?: { page?: number; size?: number }) =>
    apiClient.get<Page<UserDto>>(BASE, { params }).then((r) => r.data),

  get: (id: string) => apiClient.get<UserDto>(`${BASE}/${id}`).then((r) => r.data),

  /**
   * Create a user. When `targetTenantId` is provided (SUPER_ADMIN cross-workspace
   * creation) the X-Tenant-Id header is overridden so the user lands in the
   * correct workspace instead of the admin's own workspace.
   */
  create: (data: CreateUserRequest, targetTenantId?: string) =>
    apiClient
      .post<UserDto>(BASE, data, targetTenantId ? { headers: { 'X-Tenant-Id': targetTenantId } } : undefined)
      .then((r) => r.data),

  updateRole: (id: string, role: UserRole) => apiClient.put<UserDto>(`${BASE}/${id}`, { role }).then((r) => r.data),

  delete: (id: string) => apiClient.delete(`${BASE}/${id}`),

  /**
   * Admin-initiated password reset.
   * Sets a temporary password and forces `mustChangePassword=true` on the target user.
   * Only admins (TENANT_ADMIN / SUPER_ADMIN) should call this.
   */
  resetPassword: (id: string, newPassword: string) =>
    apiClient
      .post<{ success: boolean; message: string }>(`${BASE}/${id}/reset-password`, { newPassword })
      .then((r) => r.data),
}
