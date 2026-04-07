import apiClient from './client'
import type {
  Page,
  WebhookSubscriptionDto,
  WebhookDetailDto,
  CreateWebhookRequest,
  UpdateWebhookRequest,
  WebhookDeliveryDto,
  WebhookTestResult,
  AsyncAcknowledgement,
} from '../types'

const BASE = '/api/v1/admin/webhooks'

export const webhooksApi = {
  list: (params?: { page?: number; size?: number }) =>
    apiClient.get<Page<WebhookSubscriptionDto>>(BASE, { params }).then((r) => r.data),

  get: (id: string) => apiClient.get<WebhookDetailDto>(`${BASE}/${id}`).then((r) => r.data),

  create: (data: CreateWebhookRequest) => apiClient.post<AsyncAcknowledgement>(BASE, data).then((r) => r.data),

  update: (id: string, data: UpdateWebhookRequest) =>
    apiClient.put<AsyncAcknowledgement>(`${BASE}/${id}`, data).then((r) => r.data),

  delete: (id: string) => apiClient.delete<AsyncAcknowledgement>(`${BASE}/${id}`).then((r) => r.data),

  /** Send a test ping to the webhook — returns result inline. */
  testPing: (id: string) => apiClient.post<WebhookTestResult>(`${BASE}/${id}/test`).then((r) => r.data),

  /** Delivery log for a specific subscription. */
  deliveries: (id: string, params?: { page?: number; size?: number }) =>
    apiClient.get<Page<WebhookDeliveryDto>>(`${BASE}/${id}/deliveries`, { params }).then((r) => r.data),
}
