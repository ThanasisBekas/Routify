import apiClient from './client'
import type { Page, ApiKeyDto, ApiKeyDetailDto, CreateApiKeyRequest, ApiKeyCreatedResponse } from '../types'

const BASE = '/api/v1/admin/api-keys'

export const apiKeysApi = {
  list: (params?: { page?: number; size?: number }) =>
    apiClient.get<Page<ApiKeyDto>>(BASE, { params }).then((r) => r.data),

  get: (id: string) => apiClient.get<ApiKeyDetailDto>(`${BASE}/${id}`).then((r) => r.data),

  /** Create a new API key — response includes the raw key shown once. */
  create: (data: CreateApiKeyRequest) =>
    apiClient.post<ApiKeyCreatedResponse>(BASE, data).then((r) => r.data),

  revoke: (id: string) =>
    apiClient.post<ApiKeyDetailDto>(`${BASE}/${id}/revoke`).then((r) => r.data),

  /** Rotate a key — revokes the old key and returns a new raw key. */
  rotate: (id: string) =>
    apiClient.post<ApiKeyCreatedResponse>(`${BASE}/${id}/rotate`).then((r) => r.data),
}

