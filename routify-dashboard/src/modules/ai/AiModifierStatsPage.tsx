/**
 * AiModifierStatsPage — per-route AI Modifier monitoring dashboard.
 *
 * Displays:
 *  - Mutation applied vs passthrough breakdown (pie + counters)
 *  - Mutation type breakdown (PII_SCRUB, TRANSLATE, HEADER_REWRITE, CUSTOM)
 *  - LLM latency percentiles (p50, p95, p99)
 *  - Cache hit rate
 *  - Recent modification decision log (type, reason, latency, path, timestamp)
 *
 * All data is fetched from routify-audit-service via routify-admin-api using TanStack Query.
 */
import { useState } from 'react'
import { PieChart, Pie, Cell, Tooltip, ResponsiveContainer, BarChart, Bar, XAxis, YAxis, CartesianGrid } from 'recharts'
import { cn } from '../../lib/utils'
import { useAiModifierStats, useAiModifierDecisions } from './useAiModifierStats'
import type { AiMutationType } from '../../types'

const MUTATION_COLORS: Record<AiMutationType, string> = {
  PII_SCRUB: '#fb7185',
  TRANSLATE: '#60a5fa',
  HEADER_REWRITE: '#fbbf24',
  CUSTOM: '#c084fc',
  PASSTHROUGH: '#6b7280',
}

interface Props {
  routeId: string
  routeName?: string
}

export default function AiModifierStatsPage({ routeId, routeName }: Props) {
  const [page, setPage] = useState(0)

  const { data: stats, isLoading: statsLoading, error: statsError } = useAiModifierStats(routeId)
  const { data: decisions, isLoading: decisionsLoading } = useAiModifierDecisions(routeId, { page, size: 20 })

  if (statsLoading) return <LoadingState />
  if (statsError || !stats) return <ErrorState message="Failed to load AI modifier stats" />

  const appliedRate = stats.totalDecisions > 0 ? ((stats.appliedCount / stats.totalDecisions) * 100).toFixed(1) : '0.0'

  const cacheHitRate =
    stats.totalDecisions > 0 ? ((stats.cacheHitCount / stats.totalDecisions) * 100).toFixed(1) : '0.0'

  const typeData = [
    {
      name: 'PII Scrub',
      value: stats.piiScrubCount,
      type: 'PII_SCRUB' as AiMutationType,
      color: MUTATION_COLORS.PII_SCRUB,
    },
    {
      name: 'Translate',
      value: stats.translateCount,
      type: 'TRANSLATE' as AiMutationType,
      color: MUTATION_COLORS.TRANSLATE,
    },
    {
      name: 'Header Rewrite',
      value: stats.headerRewriteCount,
      type: 'HEADER_REWRITE' as AiMutationType,
      color: MUTATION_COLORS.HEADER_REWRITE,
    },
    { name: 'Custom', value: stats.customCount, type: 'CUSTOM' as AiMutationType, color: MUTATION_COLORS.CUSTOM },
    {
      name: 'Passthrough',
      value: stats.passthroughCount,
      type: 'PASSTHROUGH' as AiMutationType,
      color: MUTATION_COLORS.PASSTHROUGH,
    },
  ].filter((d) => d.value > 0)

  const latencyData = [
    { name: 'avg', ms: Math.round(stats.avgLatencyMs) },
    { name: 'p95', ms: Math.round(stats.p95LatencyMs) },
    { name: 'p99', ms: Math.round(stats.p99LatencyMs) },
  ]

  return (
    <div className="space-y-6">
      {/* Header */}
      <div>
        <h2 className="text-lg font-bold text-white">AI Modifier Analytics</h2>
        {routeName && <p className="text-sm text-gray-500">{routeName}</p>}
        <p className="text-xs text-gray-600 mt-0.5">
          {stats.totalDecisions.toLocaleString()} evaluations · {appliedRate}% mutation applied rate · {stats.from} →{' '}
          {stats.to}
        </p>
      </div>

      {/* Summary cards */}
      <div className="grid grid-cols-2 lg:grid-cols-4 gap-4">
        {[
          { label: 'Total', value: stats.totalDecisions, color: 'text-white' },
          { label: 'Applied', value: stats.appliedCount, color: 'text-fuchsia-400' },
          { label: 'Passthrough', value: stats.passthroughCount, color: 'text-gray-400' },
          { label: 'Cache Hits', value: stats.cacheHitCount, color: 'text-indigo-400' },
        ].map(({ label, value, color }) => (
          <div key={label} className="rounded-xl bg-white/[0.03] border border-white/[0.06] p-4">
            <p className="text-[11px] text-gray-500 uppercase tracking-wider">{label}</p>
            <p className={cn('text-2xl font-bold mt-1', color)}>{value.toLocaleString()}</p>
          </div>
        ))}
      </div>

      {/* Charts row */}
      <div className="grid grid-cols-1 lg:grid-cols-3 gap-6">
        {/* Type breakdown pie */}
        <div className="rounded-xl bg-white/[0.03] border border-white/[0.06] p-4">
          <p className="text-xs font-semibold text-gray-400 uppercase tracking-wider mb-3">
            Mutation Type Distribution
          </p>
          <ResponsiveContainer width="100%" height={160}>
            <PieChart>
              <Pie data={typeData} dataKey="value" nameKey="name" innerRadius={40} outerRadius={70}>
                {typeData.map((d) => (
                  <Cell key={d.type} fill={d.color} />
                ))}
              </Pie>
              <Tooltip formatter={(v: unknown) => (typeof v === 'number' ? v.toLocaleString() : String(v ?? ''))} />
            </PieChart>
          </ResponsiveContainer>
          <div className="flex flex-wrap justify-center gap-x-3 gap-y-1 mt-2">
            {typeData.map((d) => (
              <span key={d.type} className="flex items-center gap-1 text-[10px] text-gray-500">
                <span className="w-2 h-2 rounded-full" style={{ background: d.color }} />
                {d.name}
              </span>
            ))}
          </div>
        </div>

        {/* Latency chart */}
        <div className="rounded-xl bg-white/[0.03] border border-white/[0.06] p-4">
          <p className="text-xs font-semibold text-gray-400 uppercase tracking-wider mb-3">LLM Latency (ms)</p>
          <ResponsiveContainer width="100%" height={160}>
            <BarChart data={latencyData} margin={{ top: 4, right: 8, bottom: 4, left: 0 }}>
              <CartesianGrid strokeDasharray="3 3" stroke="rgba(255,255,255,0.05)" />
              <XAxis dataKey="name" tick={{ fontSize: 11, fill: '#9ca3af' }} />
              <YAxis tick={{ fontSize: 11, fill: '#9ca3af' }} />
              <Tooltip formatter={(v: unknown) => (typeof v === 'number' ? `${v}ms` : String(v ?? ''))} />
              <Bar dataKey="ms" fill="#c084fc" radius={[4, 4, 0, 0]} />
            </BarChart>
          </ResponsiveContainer>
        </div>

        {/* Reliability stats */}
        <div className="rounded-xl bg-white/[0.03] border border-white/[0.06] p-4 space-y-4">
          <p className="text-xs font-semibold text-gray-400 uppercase tracking-wider">Performance</p>
          <Stat label="Applied Rate" value={`${appliedRate}%`} color="text-fuchsia-400" />
          <Stat label="Cache Hit Rate" value={`${cacheHitRate}%`} color="text-indigo-400" />
          <Stat label="Avg Latency" value={`${Math.round(stats.avgLatencyMs)}ms`} />
          <Stat label="p95 Latency" value={`${Math.round(stats.p95LatencyMs)}ms`} />
          <Stat label="p99 Latency" value={`${Math.round(stats.p99LatencyMs)}ms`} />
        </div>
      </div>

      {/* Decision log */}
      <div className="rounded-xl bg-white/[0.03] border border-white/[0.06] overflow-hidden">
        <div className="px-4 py-3 border-b border-white/[0.06]">
          <p className="text-sm font-semibold text-white">Recent Modifications</p>
          <p className="text-xs text-gray-600 mt-0.5">
            PII-safe audit log — raw body content is never stored, only hashes.
          </p>
        </div>

        {decisionsLoading ? (
          <div className="flex items-center justify-center py-12 gap-2 text-gray-500 text-sm">
            <div className="w-4 h-4 border-2 border-indigo-500/30 border-t-indigo-500 rounded-full animate-spin" />
            Loading decisions…
          </div>
        ) : decisions?.content.length === 0 ? (
          <div className="py-12 text-center text-gray-500 text-sm">No modification records found</div>
        ) : (
          <div className="divide-y divide-white/[0.04]">
            {decisions?.content.map((d) => (
              <div
                key={d.mutationId}
                className="flex items-start gap-3 px-4 py-3 hover:bg-white/[0.02] transition-colors"
              >
                <span
                  className={cn(
                    'mt-0.5 shrink-0 text-[10px] font-bold px-1.5 py-0.5 rounded border',
                    d.mutationApplied
                      ? 'text-fuchsia-300 bg-fuchsia-500/10 border-fuchsia-500/20'
                      : 'text-gray-400 bg-gray-500/10 border-gray-500/20',
                  )}
                >
                  {d.mutationApplied ? d.mutationType : 'PASS'}
                </span>
                <div className="min-w-0 flex-1">
                  <div className="flex items-center gap-2">
                    <span className="font-mono text-xs text-gray-300">
                      {d.method} {d.path}
                    </span>
                    <span className="text-[10px] text-gray-600">{d.latencyMs}ms</span>
                    {d.cached && <span className="text-[10px] text-indigo-400">cached</span>}
                  </div>
                  {d.headersModified?.length > 0 && (
                    <p className="text-[10px] text-gray-600 mt-0.5">Headers modified: {d.headersModified.join(', ')}</p>
                  )}
                  <p className="text-xs text-gray-500 mt-0.5 leading-snug truncate">{d.reason}</p>
                </div>
                <span className="text-[10px] text-gray-600 shrink-0">
                  {new Date(d.evaluatedAt).toLocaleTimeString()}
                </span>
              </div>
            ))}
          </div>
        )}

        {(decisions?.totalPages ?? 0) > 1 && (
          <div className="flex items-center justify-between px-4 py-3 border-t border-white/[0.06]">
            <button
              disabled={page === 0}
              onClick={() => setPage((p) => p - 1)}
              className="text-xs text-indigo-400 disabled:opacity-40 hover:text-indigo-300"
            >
              ← Prev
            </button>
            <span className="text-xs text-gray-500">
              Page {page + 1} / {decisions?.totalPages}
            </span>
            <button
              disabled={page + 1 >= (decisions?.totalPages ?? 0)}
              onClick={() => setPage((p) => p + 1)}
              className="text-xs text-indigo-400 disabled:opacity-40 hover:text-indigo-300"
            >
              Next →
            </button>
          </div>
        )}
      </div>
    </div>
  )
}

function Stat({ label, value, color }: { label: string; value: string; color?: string }) {
  return (
    <div className="flex items-center justify-between">
      <span className="text-xs text-gray-500">{label}</span>
      <span className={cn('text-sm font-semibold text-white', color)}>{value}</span>
    </div>
  )
}

function LoadingState() {
  return (
    <div className="flex items-center justify-center py-20 gap-3 text-gray-500 text-sm">
      <div className="w-5 h-5 border-2 border-fuchsia-500/30 border-t-fuchsia-500 rounded-full animate-spin" />
      Loading AI modifier stats…
    </div>
  )
}

function ErrorState({ message }: { message: string }) {
  return (
    <div className="flex items-center justify-center py-20">
      <p className="text-sm text-red-400">{message}</p>
    </div>
  )
}
