import apiClient from './client'
import type {
  Page,
  RouteSummary,
  RouteDto,
  CreateRouteRequest,
  UpdateRouteRequest,
  AttachFilterRequest,
  AsyncAcknowledgement,
} from '../types'

// All dashboard requests go through routify-admin-api (the BFF).
// admin-api communicates with route-service via Kafka (commands) and RabbitMQ (queries).
const BASE = '/api/v1/admin/routes'

export const routesApi = {
  list: (params?: { status?: string; page?: number; size?: number; sortBy?: string; sortDir?: string }) =>
    apiClient.get<Page<RouteSummary>>(BASE, { params }).then((r) => r.data),

  get: (id: string) => apiClient.get<RouteDto>(`${BASE}/${id}`).then((r) => r.data),

  create: (req: CreateRouteRequest) => apiClient.post<AsyncAcknowledgement>(BASE, req).then((r) => r.data),

  update: (id: string, req: UpdateRouteRequest) => apiClient.put<RouteDto>(`${BASE}/${id}`, req).then((r) => r.data),

  activate: (id: string) => apiClient.post<RouteDto>(`${BASE}/${id}/activate`).then((r) => r.data),

  deactivate: (id: string) => apiClient.post<RouteDto>(`${BASE}/${id}/deactivate`).then((r) => r.data),

  delete: (id: string) => apiClient.delete(`${BASE}/${id}`).then((r) => r.data),

  clone: (id: string) => apiClient.post<RouteDto>(`${BASE}/${id}/clone`).then((r) => r.data),

  attachFilter: (routeId: string, req: AttachFilterRequest) =>
    apiClient.post<RouteDto>(`${BASE}/${routeId}/filters`, req).then((r) => r.data),

  detachFilter: (routeId: string, filterId: string) =>
    apiClient.delete<RouteDto>(`${BASE}/${routeId}/filters/${filterId}`).then((r) => r.data),
}
