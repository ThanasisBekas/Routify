import { useQuery, useMutation, useQueryClient } from '@tanstack/react-query'
import { routesApi } from '../../api/routesApi'
import { extractApiError } from '../../lib/utils'
import { toast } from 'sonner'
import { TrafficWeightSlider } from './components/TrafficWeightSlider'
import { useState } from 'react'
import { ArrowUpCircle, ArrowDownCircle, AlertTriangle, CheckCircle2 } from 'lucide-react'

interface CanaryStatusPanelProps {
  routeId: string
}

export function CanaryStatusPanel({ routeId }: CanaryStatusPanelProps) {
  const queryClient = useQueryClient()
  const [adjustWeight, setAdjustWeight] = useState<number | null>(null)

  const { data: status, isLoading } = useQuery({
    queryKey: ['canary-status', routeId],
    queryFn: () => routesApi.getCanaryStatus(routeId),
    refetchInterval: 15_000,
  })

  const promoteMutation = useMutation({
    mutationFn: () => routesApi.promoteCanary(routeId),
    onSuccess: () => {
      toast.success('Canary promotion initiated')
      queryClient.invalidateQueries({ queryKey: ['routes'] })
      queryClient.invalidateQueries({ queryKey: ['route', routeId] })
      queryClient.invalidateQueries({ queryKey: ['canary-status', routeId] })
    },
    onError: (err) => toast.error(extractApiError(err, 'Failed to promote canary')),
  })

  const rollbackMutation = useMutation({
    mutationFn: () => routesApi.rollbackCanary(routeId, 'Manual rollback from dashboard'),
    onSuccess: () => {
      toast.success('Canary rollback initiated')
      queryClient.invalidateQueries({ queryKey: ['routes'] })
      queryClient.invalidateQueries({ queryKey: ['route', routeId] })
      queryClient.invalidateQueries({ queryKey: ['canary-status', routeId] })
    },
    onError: (err) => toast.error(extractApiError(err, 'Failed to rollback canary')),
  })

  const adjustMutation = useMutation({
    mutationFn: (weight: number) => routesApi.adjustCanaryWeight(routeId, { weight }),
    onSuccess: () => {
      toast.success('Weight adjusted')
      setAdjustWeight(null)
      queryClient.invalidateQueries({ queryKey: ['canary-status', routeId] })
    },
    onError: (err) => toast.error(extractApiError(err, 'Failed to adjust weight')),
  })

  if (isLoading) {
    return (
      <div className="animate-pulse rounded-xl border border-amber-500/20 bg-amber-500/5 p-4">
        <div className="h-4 w-40 rounded bg-white/10" />
      </div>
    )
  }

  if (!status) return null

  const isBreach = status.breachCount > 0

  return (
    <div className="rounded-xl border border-amber-500/20 bg-amber-500/[0.04] p-4">
      <div className="mb-3 flex items-center justify-between">
        <div className="flex items-center gap-2">
          <span className="inline-flex items-center rounded-full bg-amber-500/15 px-2.5 py-0.5 text-xs font-semibold text-amber-400 border border-amber-500/20">
            🐤 Canary Active
          </span>
          {isBreach && (
            <span className="inline-flex items-center gap-1 rounded-full bg-red-500/15 px-2.5 py-0.5 text-xs font-semibold text-red-400 border border-red-500/20">
              <AlertTriangle className="h-3 w-3" />
              Breach {status.breachCount}/3
            </span>
          )}
        </div>
        {status.deployedAt && (
          <span className="text-xs text-gray-500">Deployed {new Date(status.deployedAt).toLocaleString()}</span>
        )}
      </div>

      {/* Traffic split visualization */}
      <div className="mb-4">
        <div className="mb-1 flex justify-between text-sm">
          <span className="text-gray-400">Primary ({status.primaryWeight}%)</span>
          <span className="text-amber-400">Canary ({status.canaryWeight}%)</span>
        </div>
        <div className="flex h-3 overflow-hidden rounded-full bg-white/[0.04]">
          <div className="bg-blue-500 transition-all duration-500" style={{ width: `${status.primaryWeight}%` }} />
          <div className="bg-amber-500 transition-all duration-500" style={{ width: `${status.canaryWeight}%` }} />
        </div>
      </div>

      {/* Error rate comparison */}
      <div className="mb-4 grid grid-cols-2 gap-3">
        <div className="rounded-lg border border-white/[0.06] bg-white/[0.03] p-3">
          <div className="text-[10px] text-gray-500 uppercase tracking-wider mb-1">Primary Error Rate</div>
          <div className="flex items-center gap-1.5">
            <CheckCircle2 className="h-4 w-4 text-emerald-400" />
            <span className="text-lg font-bold text-white">{status.primaryErrorRate.toFixed(2)}%</span>
          </div>
        </div>
        <div className="rounded-lg border border-white/[0.06] bg-white/[0.03] p-3">
          <div className="text-[10px] text-gray-500 uppercase tracking-wider mb-1">Canary Error Rate</div>
          <div className="flex items-center gap-1.5">
            {status.canaryErrorRate > status.autoRollbackThreshold ? (
              <AlertTriangle className="h-4 w-4 text-red-400" />
            ) : (
              <CheckCircle2 className="h-4 w-4 text-emerald-400" />
            )}
            <span className="text-lg font-bold text-white">{status.canaryErrorRate.toFixed(2)}%</span>
          </div>
          <div className="mt-0.5 text-[10px] text-gray-500">Threshold: {status.autoRollbackThreshold}%</div>
        </div>
      </div>

      <div className="mb-3 text-xs text-gray-500">
        Canary upstream: <code className="rounded bg-white/[0.06] px-1.5 py-0.5 text-amber-300 font-mono text-[10px]">{status.canaryUpstreamUri}</code>
      </div>

      {/* Weight adjustment */}
      {adjustWeight !== null ? (
        <div className="mb-4 rounded-lg border border-white/[0.06] bg-white/[0.03] p-3">
          <TrafficWeightSlider value={adjustWeight} onChange={setAdjustWeight} />
          <div className="mt-2 flex justify-end gap-2">
            <button
              onClick={() => setAdjustWeight(null)}
              className="rounded-lg px-3 py-1.5 text-xs text-gray-400 hover:text-white hover:bg-white/5 transition-all"
            >
              Cancel
            </button>
            <button
              onClick={() => adjustMutation.mutate(adjustWeight)}
              disabled={adjustMutation.isPending}
              className="rounded-lg bg-amber-600 px-3 py-1.5 text-xs font-medium text-white hover:bg-amber-500 disabled:opacity-50 transition-all"
            >
              Apply
            </button>
          </div>
        </div>
      ) : (
        <button
          onClick={() => setAdjustWeight(status.canaryWeight)}
          className="mb-4 text-xs text-amber-400 underline hover:text-amber-300 transition-colors"
        >
          Adjust traffic weight
        </button>
      )}

      {/* Actions */}
      <div className="flex gap-2">
        <button
          onClick={() => promoteMutation.mutate()}
          disabled={promoteMutation.isPending}
          className="inline-flex items-center gap-1.5 rounded-lg bg-emerald-600 px-3 py-1.5 text-sm font-semibold text-white hover:bg-emerald-500 disabled:opacity-50 transition-all"
        >
          <ArrowUpCircle className="h-4 w-4" />
          {promoteMutation.isPending ? 'Promoting…' : 'Promote Canary'}
        </button>
        <button
          onClick={() => rollbackMutation.mutate()}
          disabled={rollbackMutation.isPending}
          className="inline-flex items-center gap-1.5 rounded-lg bg-red-600/80 px-3 py-1.5 text-sm font-semibold text-white hover:bg-red-500 disabled:opacity-50 transition-all"
        >
          <ArrowDownCircle className="h-4 w-4" />
          {rollbackMutation.isPending ? 'Rolling back…' : 'Rollback'}
        </button>
      </div>
    </div>
  )
}
