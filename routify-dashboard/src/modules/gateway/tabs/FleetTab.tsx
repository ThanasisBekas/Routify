/**
 * FleetTab — Multi-Gateway Cluster fleet status view.
 *
 * Displays a summary bar ("3 instances — 2 healthy, 1 stale") and a grid
 * of InstanceCards. Auto-refreshes every 10s via useRealtimeQuery with
 * WebSocket event-driven invalidation.
 */
import { Server, AlertTriangle, CheckCircle, Loader2 } from 'lucide-react'
import { useRealtimeQuery } from '../../../hooks/useRealtimeQuery'
import { gatewayApi } from '../../../api/gatewayApi'
import { SectionHeader, EmptyState, Card } from '../components/GatewayPrimitives'
import InstanceCard from '../components/InstanceCard'

export default function FleetTab() {
  const {
    data: fleet,
    isLoading,
    error,
  } = useRealtimeQuery({
    queryKey: ['gateway-fleet'],
    queryFn: gatewayApi.getFleetStatus,
    wsEvents: ['gateway'],
    refetchInterval: 10_000,
  })

  if (isLoading) {
    return (
      <div className="flex items-center justify-center py-24">
        <div className="flex flex-col items-center gap-3">
          <Loader2 className="w-6 h-6 text-indigo-500 animate-spin" />
          <p className="text-sm text-gray-500">Loading fleet status…</p>
        </div>
      </div>
    )
  }

  if (error) {
    return (
      <div className="py-12">
        <EmptyState
          icon={AlertTriangle}
          title="Failed to load fleet status"
          description={error instanceof Error ? error.message : 'Unknown error'}
        />
      </div>
    )
  }

  if (!fleet || fleet.instanceCount === 0) {
    return (
      <div className="py-12">
        <EmptyState
          icon={Server}
          title="No gateway instances registered"
          description="Gateway instances register automatically via Redis heartbeat when they start. Make sure at least one routify-api-gateway instance is running."
        />
      </div>
    )
  }

  return (
    <div>
      <SectionHeader
        title="Gateway Fleet"
        description="Cluster-wide view of all registered gateway instances. Instances heartbeat to Redis every 10s with TTL=30s."
        icon={Server}
        badge={
          <span className="text-xs font-mono bg-indigo-500/10 border border-indigo-500/20 text-indigo-400 px-2 py-0.5 rounded-md">
            v{fleet.globalConfigVersion}
          </span>
        }
      />

      {/* ── Summary bar ──────────────────────────────────────────────────── */}
      <Card className="mb-6">
        <div className="flex items-center justify-between flex-wrap gap-4 p-4">
          <div className="flex items-center gap-6">
            <div className="flex items-center gap-2">
              <Server className="w-4 h-4 text-gray-400" />
              <span className="text-sm text-white font-medium">
                {fleet.instanceCount} instance{fleet.instanceCount !== 1 ? 's' : ''}
              </span>
            </div>

            <div className="flex items-center gap-4 text-xs">
              <span className="flex items-center gap-1.5">
                <CheckCircle className="w-3.5 h-3.5 text-emerald-400" />
                <span className="text-emerald-400 font-medium">{fleet.healthyCount} healthy</span>
              </span>
              {fleet.staleCount > 0 && (
                <span className="flex items-center gap-1.5">
                  <AlertTriangle className="w-3.5 h-3.5 text-amber-400" />
                  <span className="text-amber-400 font-medium">{fleet.staleCount} stale</span>
                </span>
              )}
            </div>
          </div>

          <div className="text-[10px] text-gray-600 font-mono">
            Global config version: <span className="text-indigo-400 font-bold">v{fleet.globalConfigVersion}</span>
          </div>
        </div>
      </Card>

      {/* ── Instance cards grid ─────────────────────────────────────────── */}
      <div className="grid grid-cols-1 md:grid-cols-2 xl:grid-cols-3 gap-4">
        {fleet.instances.map((instance) => (
          <InstanceCard key={instance.instanceId} instance={instance} globalConfigVersion={fleet.globalConfigVersion} />
        ))}
      </div>
    </div>
  )
}
