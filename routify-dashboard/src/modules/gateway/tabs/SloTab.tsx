/**
 * SloTab — SLO status, error budget bars, and SLO config per route.
 *
 * Lists routes with their SLO configuration, actual availability,
 * error budget consumption bar, and latency SLO status indicator.
 * Click to edit SLO targets via a modal (React Hook Form + Zod).
 */
import { useState } from 'react'
import { useForm } from 'react-hook-form'
import { zodResolver } from '@hookform/resolvers/zod'
import { z } from 'zod'
import { useMutation, useQueryClient } from '@tanstack/react-query'
import { Target, CheckCircle, XCircle, Settings2, X } from 'lucide-react'
import { gatewayApi } from '../../../api/gatewayApi'
import { useRealtimeQuery } from '../../../hooks/useRealtimeQuery'
import { cn } from '../../../lib/utils'
import type { RouteHealthEntry, SloStatus, RouteSloConfig } from '../../../types'
import { Card } from '../components/GatewayPrimitives'
import { toast } from 'sonner'

// ─── Error Budget Bar ───────────────────────────────────────────────────────

function ErrorBudgetBar({ budget }: { budget: SloStatus['errorBudget'] }) {
  const pct = Math.min(budget.percentConsumed, 200)
  const exceeded = budget.remaining < 0

  return (
    <div className="space-y-1.5">
      <div className="flex items-center justify-between text-[10px]">
        <span className="text-gray-500">Error Budget</span>
        <span className={cn('font-semibold', exceeded ? 'text-red-400' : pct > 80 ? 'text-amber-400' : 'text-emerald-400')}>
          {exceeded ? `${Math.abs(budget.remaining).toFixed(0)} over budget` : `${budget.remaining.toFixed(0)} remaining`}
        </span>
      </div>
      <div className="w-full h-2 bg-white/[0.06] rounded-full overflow-hidden">
        <div
          className={cn(
            'h-full rounded-full transition-all duration-500',
            exceeded ? 'bg-red-500' : pct > 80 ? 'bg-amber-500' : 'bg-emerald-500',
          )}
          style={{ width: `${Math.min(pct, 100)}%` }}
        />
      </div>
      <div className="flex items-center justify-between text-[9px] text-gray-600">
        <span>0%</span>
        <span>{budget.percentConsumed.toFixed(1)}% consumed</span>
        <span>100%</span>
      </div>
    </div>
  )
}

// ─── SLO Config Modal ───────────────────────────────────────────────────────

const sloSchema = z.object({
  availabilityTarget: z.number().min(90).max(100),
  latencyP99TargetMs: z.number().int().min(10).max(60000),
  evaluationWindowHours: z.number().int().min(1).max(720),
})

type SloFormData = z.infer<typeof sloSchema>

function SloConfigModal({
  routeId,
  routeName,
  initial,
  onClose,
}: {
  routeId: string
  routeName: string
  initial: RouteSloConfig
  onClose: () => void
}) {
  const qc = useQueryClient()

  const {
    register,
    handleSubmit,
    formState: { errors },
  } = useForm<SloFormData>({
    resolver: zodResolver(sloSchema),
    defaultValues: {
      availabilityTarget: initial.availabilityTarget,
      latencyP99TargetMs: initial.latencyP99TargetMs,
      evaluationWindowHours: initial.evaluationWindowHours,
    },
  })

  const saveMutation = useMutation({
    mutationFn: (data: SloFormData) => gatewayApi.saveRouteSlo(routeId, data),
    onSuccess: () => {
      toast.success('SLO configuration saved')
      qc.invalidateQueries({ queryKey: ['slo-status'] })
      qc.invalidateQueries({ queryKey: ['route-health'] })
      onClose()
    },
    onError: () => toast.error('Failed to save SLO configuration'),
  })

  return (
    // eslint-disable-next-line jsx-a11y/click-events-have-key-events, jsx-a11y/no-static-element-interactions
    <div
      className="fixed inset-0 z-50 flex items-center justify-center p-4"
      style={{ background: 'rgba(0,0,0,0.7)', backdropFilter: 'blur(4px)' }}
      onClick={onClose}
    >
      {/* eslint-disable-next-line jsx-a11y/click-events-have-key-events, jsx-a11y/no-noninteractive-element-interactions */}
      <div
        role="dialog"
        aria-modal="true"
        aria-label={`Edit SLO for ${routeName}`}
        className="relative w-full max-w-md rounded-2xl border border-white/[0.09] bg-[#0e1018] shadow-2xl"
        onClick={(e) => e.stopPropagation()}
      >
        <div className="flex items-center justify-between px-6 py-4 border-b border-white/[0.06]">
          <div>
            <h3 className="text-sm font-bold text-white">Edit SLO Targets</h3>
            <p className="text-xs text-gray-500 mt-0.5 font-mono">{routeName}</p>
          </div>
          <button onClick={onClose} className="p-1.5 rounded-lg text-gray-500 hover:text-white hover:bg-white/[0.06] transition-all">
            <X className="w-4 h-4" />
          </button>
        </div>

        <form onSubmit={handleSubmit((data) => saveMutation.mutate(data))} className="px-6 py-5 space-y-5">
          <div className="space-y-1.5">
            <label className="text-xs font-medium text-gray-400">Availability Target (%)</label>
            <input
              type="number"
              step="0.01"
              {...register('availabilityTarget', { valueAsNumber: true })}
              className="w-full px-3 py-2 text-sm bg-white/[0.04] border border-white/[0.08] rounded-lg text-white focus:outline-none focus:ring-2 focus:ring-indigo-500/50"
            />
            {errors.availabilityTarget && <p className="text-[10px] text-red-400">{errors.availabilityTarget.message}</p>}
          </div>

          <div className="space-y-1.5">
            <label className="text-xs font-medium text-gray-400">Latency P99 Target (ms)</label>
            <input
              type="number"
              {...register('latencyP99TargetMs', { valueAsNumber: true })}
              className="w-full px-3 py-2 text-sm bg-white/[0.04] border border-white/[0.08] rounded-lg text-white focus:outline-none focus:ring-2 focus:ring-indigo-500/50"
            />
            {errors.latencyP99TargetMs && <p className="text-[10px] text-red-400">{errors.latencyP99TargetMs.message}</p>}
          </div>

          <div className="space-y-1.5">
            <label className="text-xs font-medium text-gray-400">Evaluation Window (hours)</label>
            <input
              type="number"
              {...register('evaluationWindowHours', { valueAsNumber: true })}
              className="w-full px-3 py-2 text-sm bg-white/[0.04] border border-white/[0.08] rounded-lg text-white focus:outline-none focus:ring-2 focus:ring-indigo-500/50"
            />
            {errors.evaluationWindowHours && <p className="text-[10px] text-red-400">{errors.evaluationWindowHours.message}</p>}
          </div>

          <div className="flex items-center justify-end gap-3 pt-2">
            <button type="button" onClick={onClose} className="px-4 py-2 text-xs text-gray-400 hover:text-white transition-colors">
              Cancel
            </button>
            <button
              type="submit"
              disabled={saveMutation.isPending}
              className="px-4 py-2 bg-indigo-600 hover:bg-indigo-500 disabled:opacity-50 text-white text-xs font-medium rounded-lg transition-colors"
            >
              {saveMutation.isPending ? 'Saving…' : 'Save SLO'}
            </button>
          </div>
        </form>
      </div>
    </div>
  )
}

// ─── SLO Route Row ──────────────────────────────────────────────────────────

function SloRouteRow({
  route,
  onEdit,
}: {
  route: RouteHealthEntry
  onEdit: () => void
}) {
  const { data: sloStatus } = useRealtimeQuery({
    queryKey: ['slo-status', route.routeId],
    queryFn: () => gatewayApi.getRouteSloStatus(route.routeId),
    wsEvents: ['route'],
    staleTime: 60_000,
  })

  const availability = route.totalRequests > 0
    ? (1 - route.errorRate) * 100
    : 100

  const hasData = !!sloStatus
  const exceeded = hasData && !sloStatus.availabilitySloMet

  return (
    <div className={cn(
      'rounded-xl border p-4 space-y-3',
      exceeded ? 'border-red-500/20 bg-red-500/[0.03]' : 'border-white/[0.07] bg-white/[0.02]',
    )}>
      <div className="flex items-center justify-between">
        <div className="flex items-center gap-3 min-w-0">
          <div className="text-sm font-medium text-white font-mono truncate">{route.routeName}</div>
        </div>
        <div className="flex items-center gap-2">
          {hasData && (
            <>
              {sloStatus.availabilitySloMet ? (
                <span className="flex items-center gap-1 text-xs text-emerald-400">
                  <CheckCircle className="w-3.5 h-3.5" />
                  Avail ✓
                </span>
              ) : (
                <span className="flex items-center gap-1 text-xs text-red-400">
                  <XCircle className="w-3.5 h-3.5" />
                  Avail ✗
                </span>
              )}
              {sloStatus.latencySloMet ? (
                <span className="flex items-center gap-1 text-xs text-emerald-400">
                  <CheckCircle className="w-3.5 h-3.5" />
                  Latency ✓
                </span>
              ) : (
                <span className="flex items-center gap-1 text-xs text-red-400">
                  <XCircle className="w-3.5 h-3.5" />
                  Latency ✗
                </span>
              )}
            </>
          )}
          <button
            onClick={onEdit}
            className="p-1.5 rounded-lg text-gray-500 hover:text-white hover:bg-white/[0.06] transition-all"
            title="Edit SLO targets"
          >
            <Settings2 className="w-3.5 h-3.5" />
          </button>
        </div>
      </div>

      <div className="grid grid-cols-4 gap-3 text-xs">
        <div className="bg-white/[0.04] rounded-lg px-3 py-2">
          <div className="text-gray-500 mb-0.5">Target</div>
          <div className="font-semibold text-white">{hasData ? `${sloStatus.slo.availabilityTarget}%` : '99.9%'}</div>
        </div>
        <div className="bg-white/[0.04] rounded-lg px-3 py-2">
          <div className="text-gray-500 mb-0.5">Actual</div>
          <div className={cn('font-semibold', availability >= (sloStatus?.slo.availabilityTarget ?? 99.9) ? 'text-emerald-400' : 'text-red-400')}>
            {availability.toFixed(2)}%
          </div>
        </div>
        <div className="bg-white/[0.04] rounded-lg px-3 py-2">
          <div className="text-gray-500 mb-0.5">P99 Target</div>
          <div className="font-semibold text-white">{hasData ? `${sloStatus.slo.latencyP99TargetMs}ms` : '1000ms'}</div>
        </div>
        <div className="bg-white/[0.04] rounded-lg px-3 py-2">
          <div className="text-gray-500 mb-0.5">P99 Actual</div>
          <div className={cn('font-semibold', route.p99LatencyMs <= (sloStatus?.slo.latencyP99TargetMs ?? 1000) ? 'text-emerald-400' : 'text-red-400')}>
            {route.p99LatencyMs.toFixed(0)}ms
          </div>
        </div>
      </div>

      {hasData && <ErrorBudgetBar budget={sloStatus.errorBudget} />}
    </div>
  )
}

// ─── Main component ─────────────────────────────────────────────────────────

export default function SloTab() {
  const [editingRoute, setEditingRoute] = useState<{ id: string; name: string; slo: RouteSloConfig } | null>(null)

  const { data, isLoading } = useRealtimeQuery({
    queryKey: ['route-health', '7d'],
    queryFn: () => gatewayApi.getRouteHealth('7d'),
    wsEvents: ['route', 'gateway'],
    staleTime: 60_000,
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
      <div>
        <h2 className="text-base font-semibold text-white">SLO Tracking</h2>
        <p className="text-sm text-gray-400 mt-0.5">
          Set availability and latency targets per route. Error budgets are computed over the configured evaluation window.
        </p>
      </div>

      {routes.length === 0 ? (
        <Card>
          <div className="flex items-center gap-3 text-sm text-gray-500 py-4">
            <Target className="w-4 h-4 text-gray-600" />
            No route traffic data — SLOs will appear once routes receive requests
          </div>
        </Card>
      ) : (
        <div className="space-y-3">
          {routes.map((route) => (
            <SloRouteRow
              key={route.routeId}
              route={route}
              onEdit={() =>
                setEditingRoute({
                  id: route.routeId,
                  name: route.routeName,
                  slo: { availabilityTarget: 99.9, latencyP99TargetMs: 1000, evaluationWindowHours: 168 },
                })
              }
            />
          ))}
        </div>
      )}

      {editingRoute && (
        <SloConfigModal
          routeId={editingRoute.id}
          routeName={editingRoute.name}
          initial={editingRoute.slo}
          onClose={() => setEditingRoute(null)}
        />
      )}
    </div>
  )
}

