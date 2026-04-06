import { http, HttpResponse, delay } from 'msw'
import type {
  WebhookSubscriptionDto,
  WebhookDetailDto,
  CreateWebhookRequest,
  WebhookDeliveryDto,
  WebhookTestResult,
} from '../../types'

function genId() {
  return `aaaaaaaa-hook-${Date.now()}-${Math.random().toString(36).slice(2, 7)}`
}

const MOCK_TENANT = '00000000-0000-0000-0000-000000000001'

const webhooks = new Map<string, WebhookSubscriptionDto>()

// Seed some mock subscriptions
const seedWebhooks: WebhookSubscriptionDto[] = [
  {
    id: genId(),
    tenantId: MOCK_TENANT,
    name: 'Slack Integration',
    url: 'https://hooks.slack.com/services/T00/B00/xxxxx',
    eventTypes: ['ROUTE_ACTIVATED', 'ROUTE_DEACTIVATED', 'CERT_EXPIRING'],
    status: 'ACTIVE',
    failureCount: 0,
    lastDeliveredAt: new Date(Date.now() - 2 * 3600_000).toISOString(),
    createdAt: new Date(Date.now() - 7 * 24 * 3600_000).toISOString(),
    updatedAt: new Date(Date.now() - 2 * 3600_000).toISOString(),
  },
  {
    id: genId(),
    tenantId: MOCK_TENANT,
    name: 'PagerDuty Alerts',
    url: 'https://events.pagerduty.com/integration/xxxxx/enqueue',
    eventTypes: ['AI_FILTER_BLOCKED', 'DLQ_OVERFLOW', 'GATEWAY_RELOAD_FAILED'],
    status: 'ACTIVE',
    failureCount: 0,
    createdAt: new Date(Date.now() - 14 * 24 * 3600_000).toISOString(),
    updatedAt: new Date(Date.now() - 14 * 24 * 3600_000).toISOString(),
  },
  {
    id: genId(),
    tenantId: MOCK_TENANT,
    name: 'CI Pipeline (broken)',
    url: 'https://ci.internal.example.com/webhook/routify',
    eventTypes: ['ROUTE_CREATED', 'FILTER_CREATED'],
    status: 'SUSPENDED',
    failureCount: 12,
    createdAt: new Date(Date.now() - 30 * 24 * 3600_000).toISOString(),
    updatedAt: new Date(Date.now() - 3 * 24 * 3600_000).toISOString(),
  },
]
seedWebhooks.forEach((w) => webhooks.set(w.id, w))

const mockDeliveries: WebhookDeliveryDto[] = [
  {
    id: genId(),
    subscriptionId: seedWebhooks[0].id,
    eventType: 'ROUTE_ACTIVATED',
    payload: '{"eventType":"ROUTE_ACTIVATED","timestamp":"2026-04-06T10:00:00Z","data":{}}',
    responseStatus: 200,
    responseBody: 'ok',
    attempt: 1,
    status: 'DELIVERED',
    deliveredAt: new Date(Date.now() - 2 * 3600_000).toISOString(),
    createdAt: new Date(Date.now() - 2 * 3600_000).toISOString(),
  },
  {
    id: genId(),
    subscriptionId: seedWebhooks[0].id,
    eventType: 'CERT_EXPIRING',
    payload: '{"eventType":"CERT_EXPIRING","timestamp":"2026-04-06T08:00:00Z","data":{}}',
    responseStatus: 500,
    responseBody: 'Internal Server Error',
    attempt: 3,
    status: 'FAILED',
    errorMessage: 'Non-2xx response: 500',
    createdAt: new Date(Date.now() - 5 * 3600_000).toISOString(),
  },
]

const BASE = '/api/v1/admin/webhooks'

export const webhookHandlers = [
  // ─── List ──────────────────────────────────────────────────────────────────
  http.get(BASE, async ({ request }) => {
    await delay(200)
    const url = new URL(request.url)
    const page = parseInt(url.searchParams.get('page') ?? '0', 10)
    const size = parseInt(url.searchParams.get('size') ?? '20', 10)
    const items = Array.from(webhooks.values()).sort((a, b) => b.createdAt.localeCompare(a.createdAt))
    const start = page * size
    const content = items.slice(start, start + size)
    return HttpResponse.json({
      content,
      totalElements: items.length,
      totalPages: Math.ceil(items.length / size),
      page,
      size,
      first: page === 0,
      last: start + size >= items.length,
    })
  }),

  // ─── Get ───────────────────────────────────────────────────────────────────
  http.get(`${BASE}/:id`, async ({ params }) => {
    await delay(150)
    const sub = webhooks.get(params.id as string)
    if (!sub) return HttpResponse.json({ status: 404, detail: 'Webhook not found' }, { status: 404 })
    const detail: WebhookDetailDto = {
      ...sub,
      secret: 'whsec_' + 'x'.repeat(40),
      createdBy: '00000000-0000-0000-0000-000000000099',
    }
    return HttpResponse.json(detail)
  }),

  // ─── Create ────────────────────────────────────────────────────────────────
  http.post(BASE, async ({ request }) => {
    await delay(300)
    const body = (await request.json()) as CreateWebhookRequest
    const id = genId()
    const newSub: WebhookSubscriptionDto = {
      id,
      tenantId: MOCK_TENANT,
      name: body.name,
      url: body.url,
      eventTypes: body.eventTypes,
      status: 'ACTIVE',
      failureCount: 0,
      createdAt: new Date().toISOString(),
      updatedAt: new Date().toISOString(),
    }
    webhooks.set(id, newSub)
    return HttpResponse.json({ status: 'accepted', message: 'Webhook subscription creation in progress' }, { status: 202 })
  }),

  // ─── Update ────────────────────────────────────────────────────────────────
  http.put(`${BASE}/:id`, async ({ params, request }) => {
    await delay(200)
    const sub = webhooks.get(params.id as string)
    if (!sub) return HttpResponse.json({ status: 404, detail: 'Webhook not found' }, { status: 404 })
    const body = (await request.json()) as Partial<CreateWebhookRequest>
    if (body.name) sub.name = body.name
    if (body.url) sub.url = body.url
    if (body.eventTypes) sub.eventTypes = body.eventTypes
    sub.updatedAt = new Date().toISOString()
    return HttpResponse.json({ status: 'accepted', message: 'Webhook subscription update in progress' }, { status: 202 })
  }),

  // ─── Delete ────────────────────────────────────────────────────────────────
  http.delete(`${BASE}/:id`, async ({ params }) => {
    await delay(200)
    const sub = webhooks.get(params.id as string)
    if (!sub) return HttpResponse.json({ status: 404, detail: 'Webhook not found' }, { status: 404 })
    sub.status = 'DELETED'
    webhooks.delete(params.id as string)
    return HttpResponse.json({ status: 'accepted', message: 'Webhook subscription deletion in progress' }, { status: 202 })
  }),

  // ─── Test Ping ─────────────────────────────────────────────────────────────
  http.post(`${BASE}/:id/test`, async ({ params }) => {
    await delay(500)
    const sub = webhooks.get(params.id as string)
    if (!sub) return HttpResponse.json({ status: 404, detail: 'Webhook not found' }, { status: 404 })
    const result: WebhookTestResult = {
      success: sub.status === 'ACTIVE',
      responseStatus: sub.status === 'ACTIVE' ? 200 : undefined,
      message: sub.status === 'ACTIVE' ? 'Test ping delivered successfully' : 'Subscription is suspended',
    }
    return HttpResponse.json(result)
  }),

  // ─── Delivery Log ──────────────────────────────────────────────────────────
  http.get(`${BASE}/:id/deliveries`, async ({ params, request }) => {
    await delay(200)
    const url = new URL(request.url)
    const page = parseInt(url.searchParams.get('page') ?? '0', 10)
    const size = parseInt(url.searchParams.get('size') ?? '20', 10)
    const subId = params.id as string
    const items = mockDeliveries.filter((d) => d.subscriptionId === subId)
    const start = page * size
    const content = items.slice(start, start + size)
    return HttpResponse.json({
      content,
      totalElements: items.length,
      totalPages: Math.ceil(items.length / size),
      page,
      size,
      first: page === 0,
      last: start + size >= items.length,
    })
  }),
]

