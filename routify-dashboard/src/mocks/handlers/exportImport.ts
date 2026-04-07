import { http, HttpResponse, delay } from 'msw'
import { routes, filters, MOCK_TENANT_ID } from '../db'
import type { RouteDto, FilterDefinitionDto, ImportPreviewResponse } from '../../types'

const BASE = '/api/v1/admin/routes'

export const exportImportHandlers = [
  // ─── Export ────────────────────────────────────────────────────────────────
  http.get(`${BASE}/export`, async ({ request }) => {
    await delay(300)
    const url = new URL(request.url)
    const format = url.searchParams.get('format') ?? 'yaml'

    // Build export document from mock data
    const allRoutes = Array.from(routes.values())
    const allFilters = Array.from(filters.values())

    const exportDoc = {
      apiVersion: 'routify/v1',
      kind: 'GatewayConfiguration',
      metadata: {
        exportedAt: new Date().toISOString(),
        exportedBy: 'admin@routify.io',
        tenantId: MOCK_TENANT_ID,
        environment: null,
      },
      filters: allFilters.map((f: FilterDefinitionDto) => ({
        name: f.name,
        filterType: f.filterType,
        description: f.description ?? null,
        enabled: f.enabled,
        config: f.config ?? {},
      })),
      routes: allRoutes.map((r: RouteDto) => ({
        name: r.name,
        pathPattern: r.pathPattern,
        methods: r.methods,
        upstreamUri: r.upstreamUri,
        stripPrefix: r.stripPrefix ?? null,
        status: r.status,
        description: r.description ?? null,
        filters: r.filters.map((f) => ({
          filterName: f.filterName,
          order: f.order,
          phase: f.phase,
          enabled: f.enabled,
        })),
        extraConfig: r.extraConfig ?? null,
      })),
      gatewayConfig: null,
    }

    if (format === 'json') {
      return HttpResponse.json(exportDoc, {
        headers: {
          'Content-Disposition': `attachment; filename="routify-export-mock.json"`,
        },
      })
    }

    // Simple YAML-like output (mock mode doesn't need real YAML)
    const yamlContent = JSON.stringify(exportDoc, null, 2)
    return new HttpResponse(yamlContent, {
      headers: {
        'Content-Type': 'application/x-yaml',
        'Content-Disposition': `attachment; filename="routify-export-mock.yaml"`,
      },
    })
  }),

  // ─── Import Preview ────────────────────────────────────────────────────────
  http.post(`${BASE}/import/preview`, async ({ request }) => {
    await delay(400)
    const body = await request.text()

    let parsed: Record<string, unknown>
    try {
      parsed = JSON.parse(body)
    } catch {
      // Treat as YAML — in mock mode we just try JSON
      try {
        parsed = JSON.parse(body)
      } catch {
        return HttpResponse.json(
          { type: 'VALIDATION_ERROR', title: 'Invalid format', status: 422, detail: 'Not valid YAML or JSON' },
          { status: 422 },
        )
      }
    }

    const currentRouteNames = new Set(Array.from(routes.values()).map((r) => r.name))
    const currentFilterNames = new Set(Array.from(filters.values()).map((f) => f.name))

    const importFilters = (parsed.filters as Array<{ name: string; filterType: string }>) ?? []
    const importRoutes = (parsed.routes as Array<{ name: string; pathPattern: string }>) ?? []

    const filterCreates = importFilters
      .filter((f) => !currentFilterNames.has(f.name))
      .map((f) => ({ name: f.name, type: f.filterType }))
    const filterUpdates = importFilters
      .filter((f) => currentFilterNames.has(f.name))
      .map((f) => ({ name: f.name, changes: ['config'] }))
    const filterUnchanged: string[] = []

    const routeCreates = importRoutes
      .filter((r) => !currentRouteNames.has(r.name))
      .map((r) => ({ name: r.name, type: r.pathPattern }))
    const routeUpdates = importRoutes
      .filter((r) => currentRouteNames.has(r.name))
      .map((r) => ({ name: r.name, changes: ['upstreamUri'] }))
    const routeUnchanged: string[] = []

    const preview: ImportPreviewResponse = {
      valid: true,
      changes: {
        filters: { create: filterCreates, update: filterUpdates, unchanged: filterUnchanged, delete: [] },
        routes: { create: routeCreates, update: routeUpdates, unchanged: routeUnchanged, delete: [] },
      },
      warnings: importFilters.length > 0 ? ['Some filters may have masked config fields'] : [],
    }

    return HttpResponse.json(preview)
  }),

  // ─── Import Apply ──────────────────────────────────────────────────────────
  http.post(`${BASE}/import`, async () => {
    await delay(600)
    return HttpResponse.json(
      {
        status: 'accepted',
        message: 'Import in progress — filters: 1 created, 0 updated; routes: 2 created, 1 updated',
      },
      { status: 202 },
    )
  }),
]

