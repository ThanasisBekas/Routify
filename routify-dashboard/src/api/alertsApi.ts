import apiClient from './client'
import type {
  Page,
  AlertRule,
  AlertEvent,
  CreateAlertRuleRequest,
  UpdateAlertRuleRequest,
  MuteAlertRequest,
} from '../types'

const BASE = '/api/v1/admin/alerts'

export const alertsApi = {
  list: (params?: { page?: number; size?: number }) =>
    apiClient.get<Page<AlertRule>>(BASE, { params }).then((r) => r.data),

  get: (id: string) => apiClient.get<AlertRule>(`${BASE}/${id}`).then((r) => r.data),

  create: (data: CreateAlertRuleRequest) =>
    apiClient.post<AlertRule>(BASE, data).then((r) => r.data),

  update: (id: string, data: UpdateAlertRuleRequest) =>
    apiClient.put<AlertRule>(`${BASE}/${id}`, data).then((r) => r.data),

  delete: (id: string) => apiClient.delete<void>(`${BASE}/${id}`).then((r) => r.data),

  mute: (id: string, data: MuteAlertRequest) =>
    apiClient.post<AlertRule>(`${BASE}/${id}/mute`, data).then((r) => r.data),

  unmute: (id: string) =>
    apiClient.post<AlertRule>(`${BASE}/${id}/unmute`).then((r) => r.data),

  history: (id: string, params?: { page?: number; size?: number }) =>
    apiClient.get<Page<AlertEvent>>(`${BASE}/${id}/history`, { params }).then((r) => r.data),
}

