/**
 * RoutesHealthTab — Per-route latency heatmap and health breakdown.
 *
 * Displays a time-window selector (1h/24h/7d), a heatmap grid showing
 * p99 latency colour-coded per route, and individual route health rows
 * with sparklines, error rate bars, and status code distribution.
 */
import { useState } from 'react'
import { Activity } from 'lucide-react'
import { gatewayApi } from '../../../api/gatewayApi'
import { useRealtimeQuery } from '../../../hooks/useRealtimeQuery'
import { cn } from '../../../lib/utils'
import type { HealthTimeWindow, RouteHealthEntry } from '../../../types'
import { Card } from '../components/GatewayPrimitives'

// ─── Time window selector ──────────────────────────────────────────────────

const WINDOWS: { value: HealthTimeWindow; label: string }[] = [
  { value: '1h', label: 'Last hour' },
  { value: '24h', label: 'Last 24h' },
  { value: '7d', label: 'Last 7 days' },
]

// ─── Heatmap colour helper ──────────────────────────────────────────────────

function latencyColor(p99: number): string {
  if (p99 < 100) return 'bg-emerald-500/40'
  if (p99 < 300) return 'bg-emerald-500/25'
  if (p99 < 500) return 'bg-amber-500/25'
  if (p99 < 1000) return 'bg-amber-500/40'
  return 'bg-red-500/40'
}

function errorRateColor(rate: number): string {
  if (rate < 0.01) return 'text-emerald-400'
  if (rate < 0.05) return 'text-amber-400'
  return 'text-red-400'
}

// ─── Latency Heatmap ────────────────────────────────────────────────────────

function LatencyHeatmap({ routes }: { routes: RouteHealthEntry[] }) {
  if (routes.length === 0) {
    return (
      <Card>
        <div className="flex items-center gap-3 text-sm text-gray-500 py-4">
          <Activity className="w-4 h-4 text-gray-600" />
          No route health data available for this time window
        </div>
      </Card>
    )
  }

  return (
    <div className="space-y-2">
      <div className="text-xs font-semibold text-gray-500 uppercase tracking-widest mb-3">P99 Latency Heatmap</div>
      <div className="grid gap-2" style={{ gridTemplateColumns: `180px repeat(4, 1fr)` }}>
        {/* Header row */}
        <div className="text-[10px] text-gray-600 font-medium">Route</div>
        <div className="text-[10px] text-gray-600 font-medium text-center">p50</div>
        <div className="text-[10px] text-gray-600 font-medium text-center">p95</div>
        <div className="text-[10px] text-gray-600 font-medium text-center">p99</div>
        <div className="text-[10px] text-gray-600 font-medium text-center">Avg</div>

        {routes.map((route) => (
          <>
            <div key={`name-${route.routeId}`} className="text-xs text-gray-300 font-mono truncate py-1.5">
              {route.routeName}
            </div>
            <div
              key={`p50-${route.routeId}`}
              className={cn(
                'rounded-md text-center text-xs font-semibold text-white py-1.5',
                latencyColor(route.p50LatencyMs),
              )}
            >
              {route.p50LatencyMs.toFixed(0)}ms
            </div>
            <div
              key={`p95-${route.routeId}`}
              className={cn(
                'rounded-md text-center text-xs font-semibold text-white py-1.5',
                latencyColor(route.p95LatencyMs),
              )}
            >
              {route.p95LatencyMs.toFixed(0)}ms
            </div>
            <div
              key={`p99-${route.routeId}`}
              className={cn(
                'rounded-md text-center text-xs font-semibold text-white py-1.5',
                latencyColor(route.p99LatencyMs),
              )}
            >
              {route.p99LatencyMs.toFixed(0)}ms
            </div>
            <div
              key={`avg-${route.routeId}`}
              className={cn(
                'rounded-md text-center text-xs font-semibold text-white py-1.5',
                latencyColor(route.avgLatencyMs),
              )}
            >
              {route.avgLatencyMs.toFixed(0)}ms
            </div>
          </>
        ))}
      </div>

      {/* Legend */}
      <div className="flex items-center gap-4 pt-2">
        <span className="text-[10px] text-gray-600">Latency:</span>
        {[
          { color: 'bg-emerald-500/40', label: '< 100ms' },
          { color: 'bg-emerald-500/25', label: '< 300ms' },
          { color: 'bg-amber-500/25', label: '< 500ms' },
          { color: 'bg-amber-500/40', label: '< 1s' },
          { color: 'bg-red-500/40', label: '≥ 1s' },
        ].map((l) => (
          <div key={l.label} className="flex items-center gap-1.5">
            <div className={cn('w-3 h-3 rounded-sm', l.color)} />
            <span className="text-[10px] text-gray-500">{l.label}</span>
          </div>
        ))}
      </div>
    </div>
  )
}

// ─── Per-route health row ───────────────────────────────────────────────────

function RouteHealthRow({ route }: { route: RouteHealthEntry }) {
  const statusCodes = Object.entries(route.statusCodeDistribution).sort(([a], [b]) => Number(a) - Number(b))

  return (
    <div className="rounded-xl border border-white/[0.07] bg-white/[0.02] p-4 space-y-3">
      <div className="flex items-center justify-between">
        <div className="flex items-center gap-3 min-w-0">
          <div className="text-sm font-medium text-white font-mono truncate">{route.routeName}</div>
          <span className="text-[10px] text-gray-600 font-mono">{route.routeId.slice(0, 8)}…</span>
        </div>
        <div className="flex items-center gap-3">
          <div className="text-xs text-gray-400">
            <span className="font-semibold text-white">{route.totalRequests.toLocaleString()}</span> requests
          </div>
          <div className={cn('text-xs font-semibold', errorRateColor(route.errorRate))}>
            {(route.errorRate * 100).toFixed(2)}% errors
          </div>
        </div>
      </div>

      {/* Error rate bar */}
      <div className="w-full h-1.5 bg-white/[0.06] rounded-full overflow-hidden">
        <div
          className={cn(
            'h-full rounded-full transition-all',
            route.errorRate < 0.01 ? 'bg-emerald-500' : route.errorRate < 0.05 ? 'bg-amber-500' : 'bg-red-500',
          )}
          style={{ width: `${Math.min(route.errorRate * 100, 100)}%` }}
        />
      </div>

      {/* Latency and status codes */}
      <div className="grid grid-cols-2 gap-4">
        <div className="space-y-1.5">
          <div className="text-[10px] text-gray-600 font-medium uppercase tracking-wider">Latency</div>
          <div className="grid grid-cols-4 gap-2 text-xs">
            {[
              { label: 'p50', value: route.p50LatencyMs },
              { label: 'p95', value: route.p95LatencyMs },
              { label: 'p99', value: route.p99LatencyMs },
              { label: 'avg', value: route.avgLatencyMs },
            ].map((m) => (
              <div key={m.label} className="bg-white/[0.04] rounded-lg px-2 py-1.5 text-center">
                <div className="text-[9px] text-gray-500 mb-0.5">{m.label}</div>
                <div className="font-semibold text-white">
                  {m.value.toFixed(0)}
                  <span className="text-gray-500 text-[9px]">ms</span>
                </div>
              </div>
            ))}
          </div>
        </div>

        <div className="space-y-1.5">
          <div className="text-[10px] text-gray-600 font-medium uppercase tracking-wider">Status Codes</div>
          <div className="flex flex-wrap gap-1.5">
            {statusCodes.map(([code, count]) => {
              const c = Number(code)
              const cls =
                c >= 500
                  ? 'text-red-400 bg-red-500/10 border-red-500/20'
                  : c >= 400
                    ? 'text-amber-400 bg-amber-500/10 border-amber-500/20'
                    : 'text-emerald-400 bg-emerald-500/10 border-emerald-500/20'
              return (
                <span
                  key={code}
                  className={cn('text-[10px] font-mono font-semibold px-2 py-0.5 rounded-full border', cls)}
                >
                  {code}: {Number(count).toLocaleString()}
                </span>
              )
            })}
          </div>
        </div>
      </div>
    </div>
  )
}

// ─── Main component ─────────────────────────────────────────────────────────

export default function RoutesHealthTab() {
  const [window, setWindow] = useState<HealthTimeWindow>('24h')

  const { data, isLoading } = useRealtimeQuery({
    queryKey: ['route-health', window],
    queryFn: () => gatewayApi.getRouteHealth(window),
    wsEvents: ['route', 'gateway'],
    staleTime: 30_000,
  })

  const routes = data?.routes ?? []

  if (isLoading) {
    return (
      <div className="flex items-center justify-center py-16">
        <div className="w-6 h-6 border-2 border-indigo-500/30 border-t-indigo-500 rounded-full animate-spin" />
      </div>
    )
  }

  return (
    <div className="space-y-6 max-w-5xl">
      {/* Header with window selector */}
      <div className="flex items-center justify-between">
        <div>
          <h2 className="text-base font-semibold text-white">Routes Health</h2>
          <p className="text-sm text-gray-400 mt-0.5">Per-route latency, error rates, and status code breakdown</p>
        </div>
        <div className="flex items-center gap-1 bg-white/[0.04] rounded-lg p-0.5 border border-white/[0.06]">
          {WINDOWS.map((w) => (
            <button
              key={w.value}
              onClick={() => setWindow(w.value)}
              className={cn(
                'px-3 py-1.5 text-xs font-medium rounded-md transition-all',
                window === w.value
                  ? 'bg-indigo-600 text-white shadow-sm'
                  : 'text-gray-400 hover:text-white hover:bg-white/[0.06]',
              )}
            >
              {w.label}
            </button>
          ))}
        </div>
      </div>

      {/* Latency heatmap */}
      <LatencyHeatmap routes={routes} />

      {/* Per-route health rows */}
      <div className="space-y-3">
        <div className="text-xs font-semibold text-gray-500 uppercase tracking-widest">
          Route Details ({routes.length})
        </div>
        {routes.map((route) => (
          <RouteHealthRow key={route.routeId} route={route} />
        ))}
      </div>
    </div>
  )
}
