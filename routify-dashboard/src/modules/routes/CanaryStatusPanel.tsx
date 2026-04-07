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
    onError: (err) => toast.error(extractApiError(err).detail || 'Failed to promote canary'),
  })

  const rollbackMutation = useMutation({
    mutationFn: () => routesApi.rollbackCanary(routeId, 'Manual rollback from dashboard'),
    onSuccess: () => {
      toast.success('Canary rollback initiated')
      queryClient.invalidateQueries({ queryKey: ['routes'] })
      queryClient.invalidateQueries({ queryKey: ['route', routeId] })
      queryClient.invalidateQueries({ queryKey: ['canary-status', routeId] })
    },
    onError: (err) => toast.error(extractApiError(err).detail || 'Failed to rollback canary'),
  })

  const adjustMutation = useMutation({
    mutationFn: (weight: number) => routesApi.adjustCanaryWeight(routeId, { weight }),
    onSuccess: () => {
      toast.success('Weight adjusted')
      setAdjustWeight(null)
      queryClient.invalidateQueries({ queryKey: ['canary-status', routeId] })
    },
    onError: (err) => toast.error(extractApiError(err).detail || 'Failed to adjust weight'),
  })

  if (isLoading) {
    return <div className="animate-pulse rounded-lg border p-4">Loading canary status…</div>
  }

  if (!status) return null

  const isBreach = status.breachCount > 0

  return (
    <div className="rounded-lg border border-amber-200 bg-amber-50/50 p-4 dark:border-amber-800 dark:bg-amber-950/20">
      <div className="mb-3 flex items-center justify-between">
        <div className="flex items-center gap-2">
          <span className="inline-flex items-center rounded-full bg-amber-100 px-2.5 py-0.5 text-xs font-medium text-amber-800 dark:bg-amber-900 dark:text-amber-200">
            🐤 Canary Active
          </span>
          {isBreach && (
            <span className="inline-flex items-center gap-1 rounded-full bg-red-100 px-2.5 py-0.5 text-xs font-medium text-red-800 dark:bg-red-900 dark:text-red-200">
              <AlertTriangle className="h-3 w-3" />
              Breach {status.breachCount}/3
            </span>
          )}
        </div>
        {status.deployedAt && (
          <span className="text-xs text-muted-foreground">
            Deployed {new Date(status.deployedAt).toLocaleString()}
          </span>
        )}
      </div>

      {/* Traffic split visualization */}
      <div className="mb-4">
        <div className="mb-1 flex justify-between text-sm">
          <span>Primary ({status.primaryWeight}%)</span>
          <span className="text-amber-600">Canary ({status.canaryWeight}%)</span>
        </div>
        <div className="flex h-3 overflow-hidden rounded-full">
          <div className="bg-blue-500" style={{ width: `${status.primaryWeight}%` }} />
          <div className="bg-amber-500" style={{ width: `${status.canaryWeight}%` }} />
        </div>
      </div>

      {/* Error rate comparison */}
      <div className="mb-4 grid grid-cols-2 gap-4">
        <div className="rounded-md border bg-white p-3 dark:bg-gray-800">
          <div className="text-xs text-muted-foreground">Primary Error Rate</div>
          <div className="flex items-center gap-1.5">
            <CheckCircle2 className="h-4 w-4 text-green-500" />
            <span className="text-lg font-semibold">{status.primaryErrorRate.toFixed(2)}%</span>
          </div>
        </div>
        <div className="rounded-md border bg-white p-3 dark:bg-gray-800">
          <div className="text-xs text-muted-foreground">Canary Error Rate</div>
          <div className="flex items-center gap-1.5">
            {status.canaryErrorRate > status.autoRollbackThreshold ? (
              <AlertTriangle className="h-4 w-4 text-red-500" />
            ) : (
              <CheckCircle2 className="h-4 w-4 text-green-500" />
            )}
            <span className="text-lg font-semibold">{status.canaryErrorRate.toFixed(2)}%</span>
          </div>
          <div className="mt-0.5 text-xs text-muted-foreground">
            Threshold: {status.autoRollbackThreshold}%
          </div>
        </div>
      </div>

      <div className="mb-3 text-xs text-muted-foreground">
        Canary upstream: <code className="rounded bg-gray-100 px-1 dark:bg-gray-700">{status.canaryUpstreamUri}</code>
      </div>

      {/* Weight adjustment */}
      {adjustWeight !== null ? (
        <div className="mb-4 rounded-md border bg-white p-3 dark:bg-gray-800">
          <TrafficWeightSlider value={adjustWeight} onChange={setAdjustWeight} />
          <div className="mt-2 flex justify-end gap-2">
            <button
              onClick={() => setAdjustWeight(null)}
              className="rounded px-3 py-1.5 text-xs hover:bg-gray-100 dark:hover:bg-gray-700"
            >
              Cancel
            </button>
            <button
              onClick={() => adjustMutation.mutate(adjustWeight)}
              disabled={adjustMutation.isPending}
              className="rounded bg-amber-600 px-3 py-1.5 text-xs text-white hover:bg-amber-700 disabled:opacity-50"
            >
              Apply
            </button>
          </div>
        </div>
      ) : (
        <button
          onClick={() => setAdjustWeight(status.canaryWeight)}
          className="mb-4 text-xs text-amber-600 underline hover:text-amber-700"
        >
          Adjust traffic weight
        </button>
      )}

      {/* Actions */}
      <div className="flex gap-2">
        <button
          onClick={() => promoteMutation.mutate()}
          disabled={promoteMutation.isPending}
          className="inline-flex items-center gap-1.5 rounded-md bg-green-600 px-3 py-1.5 text-sm font-medium text-white hover:bg-green-700 disabled:opacity-50"
        >
          <ArrowUpCircle className="h-4 w-4" />
          Promote Canary
        </button>
        <button
          onClick={() => rollbackMutation.mutate()}
          disabled={rollbackMutation.isPending}
          className="inline-flex items-center gap-1.5 rounded-md bg-red-600 px-3 py-1.5 text-sm font-medium text-white hover:bg-red-700 disabled:opacity-50"
        >
          <ArrowDownCircle className="h-4 w-4" />
          Rollback
        </button>
      </div>
    </div>
  )
}

