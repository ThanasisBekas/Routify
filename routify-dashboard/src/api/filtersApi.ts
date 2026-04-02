import apiClient from './client'
import type {
  Page, FilterSummary, FilterDefinitionDto,
  CreateFilterRequest, UpdateFilterRequest,
} from '../types'

// All dashboard requests go through routify-admin-api (the BFF).
const BASE = '/api/v1/admin/filters'

export const filtersApi = {
  list: (params?: { page?: number; size?: number }) =>
    apiClient.get<Page<FilterSummary>>(BASE, { params }).then((r) => r.data),

  get: (id: string) =>
    apiClient.get<FilterDefinitionDto>(`${BASE}/${id}`).then((r) => r.data),

  create: (req: CreateFilterRequest) =>
    apiClient.post<FilterDefinitionDto>(BASE, req).then((r) => r.data),

  update: (id: string, req: UpdateFilterRequest) =>
    apiClient.put<FilterDefinitionDto>(`${BASE}/${id}`, req).then((r) => r.data),

  delete: (id: string) =>
    apiClient.delete(`${BASE}/${id}`).then((r) => r.data),
}
