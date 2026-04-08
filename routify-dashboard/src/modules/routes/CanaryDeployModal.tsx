import { useState } from 'react'
import { useMutation, useQueryClient } from '@tanstack/react-query'
import { routesApi } from '../../api/routesApi'
import { extractApiError } from '../../lib/utils'
import { toast } from 'sonner'
import { TrafficWeightSlider } from './components/TrafficWeightSlider'
import { Rocket, X } from 'lucide-react'

interface CanaryDeployModalProps {
  routeId: string
  routeName: string
  open: boolean
  onClose: () => void
}

export function CanaryDeployModal({ routeId, routeName, open, onClose }: CanaryDeployModalProps) {
  const queryClient = useQueryClient()
  const [canaryUpstreamUri, setCanaryUpstreamUri] = useState('')
  const [trafficWeight, setTrafficWeight] = useState(10)
  const [autoRollbackThreshold, setAutoRollbackThreshold] = useState(5)

  const deployMutation = useMutation({
    mutationFn: () =>
      routesApi.deployCanary(routeId, {
        canaryUpstreamUri,
        trafficWeight,
        autoRollbackThreshold,
      }),
    onSuccess: () => {
      toast.success('Canary deployment initiated')
      queryClient.invalidateQueries({ queryKey: ['routes'] })
      queryClient.invalidateQueries({ queryKey: ['route', routeId] })
      onClose()
    },
    onError: (err) => {
      toast.error(extractApiError(err, 'Failed to deploy canary'))
    },
  })

  if (!open) return null

  return (
    <div className="fixed inset-0 z-50 flex items-center justify-center bg-black/50">
      <div className="w-full max-w-md rounded-lg bg-white p-6 shadow-xl dark:bg-gray-800">
        <div className="mb-4 flex items-center justify-between">
          <div className="flex items-center gap-2">
            <Rocket className="h-5 w-5 text-amber-600" />
            <h2 className="text-lg font-semibold">Deploy Canary</h2>
          </div>
          <button onClick={onClose} className="rounded p-1 hover:bg-gray-100 dark:hover:bg-gray-700">
            <X className="h-4 w-4" />
          </button>
        </div>

        <p className="mb-4 text-sm text-muted-foreground">
          Deploy a canary for <strong>{routeName}</strong>. A portion of traffic will be routed to the canary upstream.
          If the error rate exceeds the threshold, it will automatically roll back.
        </p>

        <div className="space-y-4">
          <div>
            <label htmlFor="canary-upstream-uri" className="mb-1 block text-sm font-medium">
              Canary Upstream URI
            </label>
            <input
              id="canary-upstream-uri"
              type="text"
              value={canaryUpstreamUri}
              onChange={(e) => setCanaryUpstreamUri(e.target.value)}
              placeholder="http://orders-v2:8080"
              className="w-full rounded-md border px-3 py-2 text-sm focus:outline-none focus:ring-2 focus:ring-amber-500"
            />
          </div>

          <div>
            <label htmlFor="canary-traffic-weight" className="mb-1 block text-sm font-medium">
              Traffic Weight
            </label>
            <TrafficWeightSlider value={trafficWeight} onChange={setTrafficWeight} />
          </div>

          <div>
            <label htmlFor="canary-rollback-threshold" className="mb-1 block text-sm font-medium">
              Auto-Rollback Threshold (%)
            </label>
            <input
              id="canary-rollback-threshold"
              type="number"
              min={1}
              max={100}
              step={0.5}
              value={autoRollbackThreshold}
              onChange={(e) => setAutoRollbackThreshold(Number(e.target.value))}
              className="w-full rounded-md border px-3 py-2 text-sm focus:outline-none focus:ring-2 focus:ring-amber-500"
            />
            <p className="mt-1 text-xs text-muted-foreground">
              If the canary error rate exceeds this threshold for 90 seconds, it will be automatically rolled back.
            </p>
          </div>
        </div>

        <div className="mt-6 flex justify-end gap-3">
          <button
            onClick={onClose}
            className="rounded-md px-4 py-2 text-sm font-medium hover:bg-gray-100 dark:hover:bg-gray-700"
          >
            Cancel
          </button>
          <button
            onClick={() => deployMutation.mutate()}
            disabled={!canaryUpstreamUri || deployMutation.isPending}
            className="rounded-md bg-amber-600 px-4 py-2 text-sm font-medium text-white hover:bg-amber-700 disabled:opacity-50"
          >
            {deployMutation.isPending ? 'Deploying…' : 'Deploy Canary'}
          </button>
        </div>
      </div>
    </div>
  )
}
