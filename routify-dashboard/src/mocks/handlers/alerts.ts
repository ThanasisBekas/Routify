import { http, HttpResponse } from 'msw'

interface MockAlertRule {
  id: string
  tenantId: string
  name: string
  description: string | null
  metric: string
  routeId: string | null
  operator: string
  threshold: number
  windowMinutes: number
  cooldownMinutes: number
  severity: string
  enabled: boolean
  currentState: string
  stateChangedAt: string | null
  consecutiveBreaches: number
  lastEvaluatedAt: string | null
  lastFiredAt: string | null
  mutedUntil: string | null
  createdBy: string | null
  createdAt: string
  updatedAt: string
}

interface MockAlertEvent {
  id: string
  ruleId: string
  tenantId: string
  transition: string
  metricValue: number | null
  threshold: number | null
  message: string | null
  occurredAt: string
}

const SEED_RULES: MockAlertRule[] = [
  {
    id: 'a0000000-0000-0000-0000-000000000001',
    tenantId: 'mock-tenant',
    name: 'Orders API high error rate',
    description: 'Fires when error rate exceeds 5% for 5 minutes',
    metric: 'ERROR_RATE',
    routeId: null,
    operator: 'GT',
    threshold: 5.0,
    windowMinutes: 5,
    cooldownMinutes: 30,
    severity: 'CRITICAL',
    enabled: true,
    currentState: 'FIRING',
    stateChangedAt: '2026-04-07T10:15:00Z',
    consecutiveBreaches: 5,
    lastEvaluatedAt: '2026-04-07T10:20:00Z',
    lastFiredAt: '2026-04-07T10:15:00Z',
    mutedUntil: null,
    createdBy: 'admin@routify.io',
    createdAt: '2026-04-01T09:00:00Z',
    updatedAt: '2026-04-07T10:20:00Z',
  },
  {
    id: 'a0000000-0000-0000-0000-000000000002',
    tenantId: 'mock-tenant',
    name: 'Certificate expiry warning',
    description: 'Alert when any cert expires within 30 days',
    metric: 'CERT_EXPIRY_DAYS',
    routeId: null,
    operator: 'LT',
    threshold: 30,
    windowMinutes: 60,
    cooldownMinutes: 1440,
    severity: 'WARNING',
    enabled: true,
    currentState: 'OK',
    stateChangedAt: null,
    consecutiveBreaches: 0,
    lastEvaluatedAt: '2026-04-07T10:20:00Z',
    lastFiredAt: null,
    mutedUntil: null,
    createdBy: 'admin@routify.io',
    createdAt: '2026-04-02T14:00:00Z',
    updatedAt: '2026-04-07T10:20:00Z',
  },
  {
    id: 'a0000000-0000-0000-0000-000000000003',
    tenantId: 'mock-tenant',
    name: 'DLQ overflow alert',
    description: 'Alert when DLQ depth exceeds 10 events',
    metric: 'DLQ_DEPTH',
    routeId: null,
    operator: 'GT',
    threshold: 10,
    windowMinutes: 5,
    cooldownMinutes: 15,
    severity: 'CRITICAL',
    enabled: true,
    currentState: 'PENDING',
    stateChangedAt: '2026-04-07T10:19:00Z',
    consecutiveBreaches: 2,
    lastEvaluatedAt: '2026-04-07T10:20:00Z',
    lastFiredAt: null,
    mutedUntil: null,
    createdBy: 'admin@routify.io',
    createdAt: '2026-04-03T08:00:00Z',
    updatedAt: '2026-04-07T10:20:00Z',
  },
]

const SEED_EVENTS: MockAlertEvent[] = [
  {
    id: 'e0000000-0000-0000-0000-000000000001',
    ruleId: 'a0000000-0000-0000-0000-000000000001',
    tenantId: 'mock-tenant',
    transition: 'OK_TO_PENDING',
    metricValue: 5.2,
    threshold: 5.0,
    message: "Rule 'Orders API high error rate': OK_TO_PENDING (value=5.2000, threshold=5.0000)",
    occurredAt: '2026-04-07T10:10:00Z',
  },
  {
    id: 'e0000000-0000-0000-0000-000000000002',
    ruleId: 'a0000000-0000-0000-0000-000000000001',
    tenantId: 'mock-tenant',
    transition: 'PENDING_TO_FIRING',
    metricValue: 7.3,
    threshold: 5.0,
    message: "Rule 'Orders API high error rate': PENDING_TO_FIRING (value=7.3000, threshold=5.0000)",
    occurredAt: '2026-04-07T10:15:00Z',
  },
]

let rules: MockAlertRule[] = [...SEED_RULES]

export const alertHandlers = [
  http.get('/api/v1/admin/alerts', () => {
    return HttpResponse.json({
      content: rules,
      totalElements: rules.length,
      totalPages: 1,
      page: 0,
      size: 100,
      first: true,
      last: true,
    })
  }),

  http.get('/api/v1/admin/alerts/:id', ({ params }) => {
    const rule = rules.find((r) => r.id === params.id)
    if (!rule) return new HttpResponse(null, { status: 404 })
    return HttpResponse.json(rule)
  }),

  http.post('/api/v1/admin/alerts', async ({ request }) => {
    const body = (await request.json()) as Record<string, unknown>
    const newRule: MockAlertRule = {
      id: crypto.randomUUID(),
      tenantId: 'mock-tenant',
      name: (body.name as string) ?? 'New Alert',
      description: (body.description as string) ?? null,
      metric: (body.metric as string) ?? 'ERROR_RATE',
      routeId: (body.routeId as string) ?? null,
      operator: (body.operator as string) ?? 'GT',
      threshold: (body.threshold as number) ?? 5.0,
      windowMinutes: (body.windowMinutes as number) ?? 5,
      cooldownMinutes: (body.cooldownMinutes as number) ?? 30,
      severity: (body.severity as string) ?? 'WARNING',
      enabled: body.enabled !== false,
      currentState: 'OK',
      stateChangedAt: null,
      consecutiveBreaches: 0,
      lastEvaluatedAt: null,
      lastFiredAt: null,
      mutedUntil: null,
      createdBy: 'admin@routify.io',
      createdAt: new Date().toISOString(),
      updatedAt: new Date().toISOString(),
    }
    rules.push(newRule)
    return HttpResponse.json(newRule, { status: 201 })
  }),

  http.put('/api/v1/admin/alerts/:id', async ({ params, request }) => {
    const body = (await request.json()) as Record<string, unknown>
    const rule = rules.find((r) => r.id === params.id)
    if (!rule) return new HttpResponse(null, { status: 404 })
    if (body.name) rule.name = body.name as string
    if (body.description !== undefined) rule.description = body.description as string
    if (body.metric) rule.metric = body.metric as string
    if (body.operator) rule.operator = body.operator as string
    if (body.threshold !== undefined) rule.threshold = body.threshold as number
    if (body.windowMinutes !== undefined) rule.windowMinutes = body.windowMinutes as number
    if (body.cooldownMinutes !== undefined) rule.cooldownMinutes = body.cooldownMinutes as number
    if (body.severity) rule.severity = body.severity as string
    if (body.enabled !== undefined) rule.enabled = body.enabled as boolean
    rule.updatedAt = new Date().toISOString()
    return HttpResponse.json(rule)
  }),

  http.delete('/api/v1/admin/alerts/:id', ({ params }) => {
    rules = rules.filter((r) => r.id !== params.id)
    return new HttpResponse(null, { status: 204 })
  }),

  http.post('/api/v1/admin/alerts/:id/mute', async ({ params, request }) => {
    const body = (await request.json()) as { durationMinutes?: number }
    const rule = rules.find((r) => r.id === params.id)
    if (!rule) return new HttpResponse(null, { status: 404 })
    const minutes = body.durationMinutes ?? 60
    rule.mutedUntil = new Date(Date.now() + minutes * 60_000).toISOString()
    return HttpResponse.json(rule)
  }),

  http.post('/api/v1/admin/alerts/:id/unmute', ({ params }) => {
    const rule = rules.find((r) => r.id === params.id)
    if (!rule) return new HttpResponse(null, { status: 404 })
    rule.mutedUntil = null
    return HttpResponse.json(rule)
  }),

  http.get('/api/v1/admin/alerts/:id/history', ({ params }) => {
    const events = SEED_EVENTS.filter((e) => e.ruleId === params.id)
    return HttpResponse.json({
      content: events,
      totalElements: events.length,
      totalPages: 1,
      page: 0,
      size: 20,
      first: true,
      last: true,
    })
  }),
]

