import apiClient from './client'
import type {
  Page,
  RoleDefinitionDto,
  CreateRoleRequest,
  UpdateRoleRequest,
  Permission,
} from '../types'

const BASE = '/api/v1/admin/roles'

export const rolesApi = {
  list: (params?: { page?: number; size?: number }) =>
    apiClient.get<Page<RoleDefinitionDto>>(BASE, { params }).then((r) => r.data),

  get: (id: string) => apiClient.get<RoleDefinitionDto>(`${BASE}/${id}`).then((r) => r.data),

  create: (data: CreateRoleRequest) =>
    apiClient.post<RoleDefinitionDto>(BASE, data).then((r) => r.data),

  update: (id: string, data: UpdateRoleRequest) =>
    apiClient.put<RoleDefinitionDto>(`${BASE}/${id}`, data).then((r) => r.data),

  delete: (id: string) => apiClient.delete<void>(`${BASE}/${id}`).then((r) => r.data),

  /** Returns the full list of available permission codes. */
  listPermissions: () =>
    apiClient.get<Permission[]>(`${BASE}/permissions`).then((r) => r.data),
}

