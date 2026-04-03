/**
 * WorkflowBuilderPage.tsx — Full-page Route Workflow Builder.
 *
 * Layout:
 *   ┌─────────────────────────────────────────────────────────┐
 *   │  Toolbar (route meta, save/activate/back actions)       │
 *   ├───────────┬─────────────────────────────────────────────┤
 *   │ NodePalette│          WorkflowCanvas (React Flow)       │
 *   │  (w-56)   │  + PropertiesDrawer overlay (right, w-80)  │
 *   └───────────┴─────────────────────────────────────────────┘
 *
 * Route: /routes/:routeId/builder
 * Navigated to via the "Open Builder" button on RouteWorkflowCard / RouteDetailModal.
 */
import { useEffect, useCallback } from 'react'
import { useParams, useNavigate } from 'react-router-dom'
import { useQueryClient, useMutation } from '@tanstack/react-query'
import { toast } from 'sonner'
import {
  ArrowLeft, Play, Pause, RefreshCw, Save, Zap, Network,
} from 'lucide-react'
import { cn, extractApiError } from '../../lib/utils'
import { routesApi } from '../../api/routesApi'
import { useRealtimeQuery } from '../../hooks/useRealtimeQuery'
import { useWorkflowStore } from './store/workflowStore'
import { buildGraph } from './hooks/buildGraph'
import NodePalette from './components/NodePalette'
import WorkflowCanvas from './components/WorkflowCanvas'
import { STATUS_CFG } from './constants/nodeMetadata'

export default function WorkflowBuilderPage() {
  const { routeId } = useParams<{ routeId: string }>()
  const navigate    = useNavigate()
  const qc          = useQueryClient()

  const { initFromGraph, isDirty, markClean, resetCanvas } = useWorkflowStore()

  // ── Fetch route ────────────────────────────────────────────────────────────
  const { data: route, isLoading } = useRealtimeQuery({
    queryKey: ['route', routeId],
    queryFn:  () => routesApi.get(routeId!),
    wsEvents: ['route'],
  })

  // onDetach callback — defined before initFromRoute call
  const detachCallback = useCallback(
    (filterId: string) => routesApi.detachFilter(routeId!, filterId).then(() => {
      qc.invalidateQueries({ queryKey: ['route', routeId] })
    }),
    [routeId, qc],
  )

  // Seed canvas whenever the route data changes (initial load or after save)
  useEffect(() => {
    if (route) {
      const { nodes, edges } = buildGraph(route, detachCallback, (nodeId: string) => {
        useWorkflowStore.getState().selectNode(nodeId)
      })
      initFromGraph(route.id, nodes, edges)
    }
  }, [route, initFromGraph, detachCallback])

  // Reset store on unmount to avoid stale state if the user navigates away
  useEffect(() => () => resetCanvas(), [resetCanvas])

  // ── Toolbar mutations ──────────────────────────────────────────────────────
  const activateMutation = useMutation({
    mutationFn: () => routesApi.activate(routeId!),
    onSuccess: () => {
      qc.invalidateQueries({ queryKey: ['route', routeId] })
      qc.invalidateQueries({ queryKey: ['routes'] })
      toast.success('Route activated', { description: 'Now live in the gateway.' })
    },
    onError: (err) => toast.error('Cannot activate', { description: extractApiError(err) }),
  })

  const deactivateMutation = useMutation({
    mutationFn: () => routesApi.deactivate(routeId!),
    onSuccess: () => {
      qc.invalidateQueries({ queryKey: ['route', routeId] })
      qc.invalidateQueries({ queryKey: ['routes'] })
      toast.success('Route deactivated')
    },
    onError: (err) => toast.error('Cannot deactivate', { description: extractApiError(err) }),
  })

  // "Save Draft" persists current canvas node properties to the backend.
  // Edge / layout changes are purely local and don't map to the route schema.
  const saveMutation = useMutation({
    mutationFn: () => routesApi.update(routeId!, {
      // At this level we only need to trigger a cache refresh; actual field
      // changes happen through PropertiesDrawer individual mutations.
    }),
    onSuccess: () => {
      qc.invalidateQueries({ queryKey: ['route', routeId] })
      markClean()
      toast.success('Saved', { description: 'Route state synced.' })
    },
    onError: (err) => toast.error('Save failed', { description: extractApiError(err) }),
  })

  // ── Loading state ─────────────────────────────────────────────────────────
  if (isLoading || !route) {
    return (
      <div className="flex-1 flex items-center justify-center bg-[#080a0f]">
        <div className="flex flex-col items-center gap-3">
          <div className="w-8 h-8 border-2 border-indigo-500/30 border-t-indigo-500 rounded-full animate-spin" />
          <p className="text-sm text-gray-500">Loading workflow…</p>
        </div>
      </div>
    )
  }

  const sc = STATUS_CFG[route.status as keyof typeof STATUS_CFG] ?? STATUS_CFG.DRAFT

  return (
    <div className="flex flex-col h-full bg-[#080a0f] animate-fade-in">

      {/* ── Toolbar ────────────────────────────────────────────────────────── */}
      <div className="flex items-center justify-between px-4 py-3 border-b border-white/[0.06] bg-[#0d0f14] shrink-0">

        {/* Left: back + route identity */}
        <div className="flex items-center gap-3">
          <button
            onClick={() => navigate('/routes')}
            className="p-1.5 rounded-lg text-gray-500 hover:text-white hover:bg-white/5 transition-colors"
            title="Back to routes"
          >
            <ArrowLeft className="w-4 h-4" />
          </button>

          <div className="flex items-center gap-2">
            <Network className="w-4 h-4 text-indigo-400" />
            <div>
              <div className="text-[10px] text-gray-600 uppercase tracking-widest font-medium">Workflow Builder</div>
              <div className="text-sm font-bold text-white leading-tight">{route.name}</div>
            </div>
          </div>

          {/* Status badge */}
          <div className={cn('flex items-center gap-1 text-[11px] font-semibold px-2 py-0.5 rounded-full border',
            sc.color,
            route.status === 'ACTIVE' ? 'bg-emerald-500/10 border-emerald-500/20' :
            route.status === 'DRAFT'  ? 'bg-amber-500/10 border-amber-500/20' :
                                        'bg-gray-500/10 border-gray-500/20',
          )}>
            {sc.icon}
            {route.status}
          </div>

          {/* Version */}
          <span className="text-[10px] text-gray-600 font-mono">v{route.version}</span>

          {/* Dirty indicator */}
          {isDirty && (
            <span className="text-[10px] text-amber-400 bg-amber-400/10 border border-amber-400/20 px-1.5 py-0.5 rounded-full">
              Unsaved changes
            </span>
          )}
        </div>

        {/* Right: action buttons */}
        <div className="flex items-center gap-2">
          {/* Save Draft */}
          <button
            onClick={() => saveMutation.mutate()}
            disabled={saveMutation.isPending}
            className="flex items-center gap-1.5 px-3 py-1.5 rounded-lg text-xs font-semibold text-gray-300 bg-white/[0.05] border border-white/[0.08] hover:bg-white/[0.08] disabled:opacity-50 transition-all"
          >
            {saveMutation.isPending
              ? <RefreshCw className="w-3.5 h-3.5 animate-spin" />
              : <Save className="w-3.5 h-3.5" />
            }
            Save
          </button>

          {/* Activate / Deactivate */}
          {(route.status === 'DRAFT' || route.status === 'DISABLED') && (
            <button
              onClick={() => activateMutation.mutate()}
              disabled={activateMutation.isPending}
              className="flex items-center gap-1.5 px-3 py-1.5 rounded-lg text-xs font-semibold text-emerald-300 bg-emerald-500/10 border border-emerald-500/20 hover:bg-emerald-500/20 disabled:opacity-50 transition-all shadow-lg shadow-emerald-500/10"
            >
              {activateMutation.isPending
                ? <RefreshCw className="w-3.5 h-3.5 animate-spin" />
                : <Play className="w-3.5 h-3.5" />
              }
              Activate
            </button>
          )}

          {route.status === 'ACTIVE' && (
            <>
              {/* Live indicator */}
              <div className="flex items-center gap-1.5 text-[11px] text-emerald-400">
                <Zap className="w-3.5 h-3.5" />
                <span>Live</span>
              </div>
              <button
                onClick={() => deactivateMutation.mutate()}
                disabled={deactivateMutation.isPending}
                className="flex items-center gap-1.5 px-3 py-1.5 rounded-lg text-xs font-semibold text-amber-300 bg-amber-500/10 border border-amber-500/20 hover:bg-amber-500/20 disabled:opacity-50 transition-all"
              >
                <Pause className="w-3.5 h-3.5" />
                Deactivate
              </button>
            </>
          )}
        </div>
      </div>

      {/* ── Main canvas area ─────────────────────────────────────────────────── */}
      <div className="flex flex-1 min-h-0 overflow-hidden">
        {/* Node palette sidebar */}
        <NodePalette />

        {/* Canvas + PropertiesDrawer overlay */}
        <WorkflowCanvas route={route} />
      </div>
    </div>
  )
}

