import { http, HttpResponse, delay } from 'msw'
import { buildPage, MOCK_TENANT_ID } from '../db'
import type {
  AiFilterStats,
  AiFilterDecisionEntry,
  AiModifierStats,
  AiModifierDecisionEntry,
  AiModificationTestRequest,
  AiModificationTestResult,
  AiPromptVersion,
  AiPromptVersionSummary,
} from '../../types'

const AI_BASE = '/api/v1/admin/ai'
const AUDIT_BASE = '/api/v1/admin/audit'

// ─── Mock prompt versions ─────────────────────────────────────────────────────

const mockVersions: AiPromptVersionSummary[] = [
  {
    id: 'ver-4',
    filterId: 'filter-ai-1',
    version: 4,
    status: 'DRAFT',
    description: 'Added PII detection rules',
    accuracyScore: undefined,
    totalDecisions: 0,
    createdAt: new Date(Date.now() - 3600_000).toISOString(),
    activatedAt: undefined,
  },
  {
    id: 'ver-3',
    filterId: 'filter-ai-1',
    version: 3,
    status: 'ACTIVE',
    description: 'Improved SQL injection detection',
    accuracyScore: 87.5,
    totalDecisions: 142,
    createdAt: new Date(Date.now() - 7 * 86400_000).toISOString(),
    activatedAt: new Date(Date.now() - 5 * 86400_000).toISOString(),
  },
  {
    id: 'ver-2',
    filterId: 'filter-ai-1',
    version: 2,
    status: 'ARCHIVED',
    description: 'Initial security policy',
    accuracyScore: 72.3,
    totalDecisions: 89,
    createdAt: new Date(Date.now() - 30 * 86400_000).toISOString(),
    activatedAt: new Date(Date.now() - 14 * 86400_000).toISOString(),
  },
]

// ─── Static mock AI decision log entries ─────────────────────────────────────

function makeFilterDecisions(routeId: string): AiFilterDecisionEntry[] {
  const actions: ('ALLOW' | 'BLOCK' | 'FLAG')[] = ['ALLOW', 'ALLOW', 'ALLOW', 'BLOCK', 'FLAG', 'ALLOW']
  return actions.map((action, i) => ({
    evaluationId: `eval-filter-${routeId}-${i}`,
    routeId,
    routeName: 'Payments API',
    action,
    reason:
      action === 'ALLOW'
        ? 'No malicious patterns detected in request path or headers.'
        : action === 'BLOCK'
          ? 'SQL injection pattern detected in query string: SELECT * FROM users'
          : 'Unusual encoded characters in path — flagged for review.',
    confidence: 0.92 - i * 0.05,
    cached: i % 2 === 0,
    latencyMs: 280 + i * 30,
    method: ['GET', 'POST', 'PUT', 'DELETE'][i % 4],
    path: '/api/v1/payments/charge',
    evaluatedAt: new Date(Date.now() - i * 5 * 60000).toISOString(),
  }))
}

function makeModifierDecisions(routeId: string): AiModifierDecisionEntry[] {
  return [
    {
      mutationId: `mut-${routeId}-1`,
      routeId,
      routeName: 'Payments API',
      mutationApplied: true,
      mutationType: 'PII_SCRUB',
      reason: 'Scrubbed email and phone from request body.',
      headersModified: [],
      cached: false,
      latencyMs: 340,
      method: 'POST',
      path: '/api/v1/payments/charge',
      evaluatedAt: new Date(Date.now() - 60000).toISOString(),
    },
    {
      mutationId: `mut-${routeId}-2`,
      routeId,
      routeName: 'Payments API',
      mutationApplied: false,
      mutationType: 'PASSTHROUGH',
      reason: 'No PII detected in request body.',
      headersModified: [],
      cached: true,
      latencyMs: 3,
      method: 'GET',
      path: '/api/v1/payments/status',
      evaluatedAt: new Date(Date.now() - 120000).toISOString(),
    },
    {
      mutationId: `mut-${routeId}-3`,
      routeId,
      routeName: 'Payments API',
      mutationApplied: true,
      mutationType: 'HEADER_REWRITE',
      reason: 'Rewrote User-Agent header.',
      headersModified: ['User-Agent', 'X-Client-Id'],
      cached: false,
      latencyMs: 215,
      method: 'POST',
      path: '/api/v1/payments/refund',
      evaluatedAt: new Date(Date.now() - 180000).toISOString(),
    },
  ]
}

export const aiHandlers = [
  // ─── Test policy (dry-run) ────────────────────────────────────────────────────
  http.post(`${AI_BASE}-filter/test-policy`, async ({ request }) => {
    await delay(800) // simulate LLM latency
    const body = (await request.json()) as { policyDescription: string }
    const isBlock =
      body.policyDescription.toLowerCase().includes('block') ||
      body.policyDescription.toLowerCase().includes('sql') ||
      body.policyDescription.toLowerCase().includes('injection')
    return HttpResponse.json({
      action: isBlock ? 'BLOCK' : 'ALLOW',
      reason: isBlock
        ? "The sample request contains patterns matching the policy's blocking criteria."
        : "The sample request does not match any of the policy's blocking criteria.",
      confidence: 0.94,
    })
  }),

  // ─── Test modification (dry-run) ──────────────────────────────────────────────
  http.post(`${AI_BASE}-modifier/test-modification`, async ({ request }) => {
    await delay(900) // simulate LLM latency
    const body = (await request.json()) as AiModificationTestRequest
    const isPii =
      body.modificationPrompt.toLowerCase().includes('pii') || body.modificationPrompt.toLowerCase().includes('scrub')
    const result: AiModificationTestResult = {
      mutationId: `mock-mutation-${Date.now()}`,
      mutationApplied: isPii,
      mutationType: isPii ? 'PII_SCRUB' : 'PASSTHROUGH',
      mutatedHeaders: {},
      mutatedBody: isPii
        ? (body.sampleRequest.body ?? 'Sample body with PII scrubbed: email: [REDACTED], phone: [REDACTED]')
        : (body.sampleRequest.body ?? null),
      reason: isPii
        ? 'PII detected and scrubbed from request body.'
        : 'No PII patterns detected — request passed through unmodified.',
      cached: false,
      latencyMs: 850,
    }
    return HttpResponse.json(result)
  }),

  // ─── AI filter stats ──────────────────────────────────────────────────────────
  http.get(`${AUDIT_BASE}/ai-filter/stats`, async ({ request }) => {
    await delay(200)
    const url = new URL(request.url)
    const routeId = url.searchParams.get('routeId') ?? MOCK_TENANT_ID
    const stats: AiFilterStats = {
      routeId,
      totalDecisions: 142,
      allowCount: 118,
      blockCount: 18,
      flagCount: 6,
      fallbackCount: 0,
      cacheHitCount: 74,
      avgLatencyMs: 312,
      p95LatencyMs: 890,
      p99LatencyMs: 1450,
      from: new Date(Date.now() - 24 * 3600 * 1000).toISOString(),
      to: new Date().toISOString(),
    }
    return HttpResponse.json(stats)
  }),

  // ─── AI filter decisions (paginated) ─────────────────────────────────────────
  http.get(`${AUDIT_BASE}/ai-filter/decisions`, async ({ request }) => {
    await delay(200)
    const url = new URL(request.url)
    const page = parseInt(url.searchParams.get('page') ?? '0', 10)
    const size = parseInt(url.searchParams.get('size') ?? '20', 10)
    const routeId = url.searchParams.get('routeId') ?? 'dddddddd-0000-0000-0000-000000000001'
    const action = url.searchParams.get('action')
    let decisions = makeFilterDecisions(routeId)
    if (action) decisions = decisions.filter((d) => d.action === action)
    return HttpResponse.json(buildPage(decisions, page, size))
  }),

  // ─── AI modifier stats ────────────────────────────────────────────────────────
  http.get(`${AUDIT_BASE}/ai-modifier/stats`, async ({ request }) => {
    await delay(200)
    const url = new URL(request.url)
    const routeId = url.searchParams.get('routeId') ?? MOCK_TENANT_ID
    const stats: AiModifierStats = {
      routeId,
      totalDecisions: 87,
      appliedCount: 52,
      passthroughCount: 35,
      piiScrubCount: 45,
      translateCount: 0,
      headerRewriteCount: 7,
      customCount: 0,
      cacheHitCount: 30,
      avgLatencyMs: 380,
      p95LatencyMs: 950,
      p99LatencyMs: 1800,
      from: new Date(Date.now() - 24 * 3600 * 1000).toISOString(),
      to: new Date().toISOString(),
    }
    return HttpResponse.json(stats)
  }),

  // ─── AI modifier decisions (paginated) ───────────────────────────────────────
  http.get(`${AUDIT_BASE}/ai-modifier/decisions`, async ({ request }) => {
    await delay(200)
    const url = new URL(request.url)
    const page = parseInt(url.searchParams.get('page') ?? '0', 10)
    const size = parseInt(url.searchParams.get('size') ?? '20', 10)
    const routeId = url.searchParams.get('routeId') ?? 'dddddddd-0000-0000-0000-000000000001'
    const decisions = makeModifierDecisions(routeId)
    return HttpResponse.json(buildPage(decisions, page, size))
  }),

  // ─── Prompt version management ────────────────────────────────────────────────

  http.get(`${AI_BASE}-filter/:filterId/versions`, async ({ request }) => {
    await delay(150)
    const url = new URL(request.url)
    const page = parseInt(url.searchParams.get('page') ?? '0', 10)
    const size = parseInt(url.searchParams.get('size') ?? '20', 10)
    return HttpResponse.json(buildPage(mockVersions, page, size))
  }),

  http.get(`${AI_BASE}-filter/:filterId/versions/:versionId`, async ({ params }) => {
    await delay(100)
    const v = mockVersions.find((mv) => mv.id === params.versionId)
    if (!v) return HttpResponse.json({ detail: 'Not found' }, { status: 404 })
    const detail: AiPromptVersion = {
      ...v,
      tenantId: MOCK_TENANT_ID,
      promptText: `Block requests that contain SQL injection patterns, XSS payloads, or attempt to access admin endpoints without proper authentication. Version ${v.version}.`,
      correctCount: Math.floor(((v.accuracyScore ?? 0) * v.totalDecisions) / 100),
    }
    return HttpResponse.json(detail)
  }),

  http.post(`${AI_BASE}-filter/:filterId/versions`, async ({ request }) => {
    await delay(300)
    const body = (await request.json()) as { promptText: string; description?: string }
    const newVersion: AiPromptVersion = {
      id: `ver-${Date.now()}`,
      filterId: 'filter-ai-1',
      tenantId: MOCK_TENANT_ID,
      version: mockVersions.length + 1,
      promptText: body.promptText,
      description: body.description,
      status: 'DRAFT',
      totalDecisions: 0,
      correctCount: 0,
      createdAt: new Date().toISOString(),
    }
    return HttpResponse.json(newVersion, { status: 201 })
  }),

  http.post(`${AI_BASE}-filter/:filterId/versions/:versionId/activate`, async ({ params }) => {
    await delay(200)
    const v = mockVersions.find((mv) => mv.id === params.versionId)
    if (!v) return HttpResponse.json({ detail: 'Not found' }, { status: 404 })
    return HttpResponse.json({ ...v, status: 'ACTIVE', activatedAt: new Date().toISOString() })
  }),

  http.post(`${AI_BASE}-filter/:filterId/versions/:versionId/archive`, async ({ params }) => {
    await delay(200)
    const v = mockVersions.find((mv) => mv.id === params.versionId)
    if (!v) return HttpResponse.json({ detail: 'Not found' }, { status: 404 })
    return HttpResponse.json({ ...v, status: 'ARCHIVED', archivedAt: new Date().toISOString() })
  }),

  // ─── Decision labelling ──────────────────────────────────────────────────────
  http.post(`${AI_BASE}-filter/decisions/:evaluationId/label`, async () => {
    await delay(150)
    return HttpResponse.json({ success: true, promptVersionId: 'ver-3', newAccuracy: 88.2 })
  }),
]
