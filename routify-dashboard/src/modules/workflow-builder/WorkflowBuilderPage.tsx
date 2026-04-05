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
import { useEffect, useCallback, useState } from 'react'
import { useParams, useNavigate } from 'react-router-dom'
import { useQueryClient, useMutation } from '@tanstack/react-query'
import { toast } from 'sonner'
import { ArrowLeft, Play, Pause, RefreshCw, Save, Zap, Network, Trash2, AlertTriangle, X, Lock } from 'lucide-react'
import { cn, extractApiError } from '../../lib/utils'
import { routesApi } from '../../api/routesApi'
import { useRealtimeQuery } from '../../hooks/useRealtimeQuery'
import { useWorkflowStore } from './store/workflowStore'
import { buildGraph, buildEmptyGraph } from './hooks/buildGraph'
import NodePalette from './components/NodePalette'
import WorkflowCanvas from './components/WorkflowCanvas'
import { STATUS_CFG } from './constants/nodeMetadata'
import { useDocumentTitle } from '../../hooks/useDocumentTitle'

// ─── Clear Canvas confirmation dialog ────────────────────────────────────────

function ClearCanvasDialog({ onConfirm, onCancel }: { onConfirm: () => void; onCancel: () => void }) {
  return (
    <div className="fixed inset-0 z-50 flex items-center justify-center p-4 bg-black/70 backdrop-blur-sm animate-fade-in">
      <div className="bg-[#111318] border border-white/[0.08] rounded-2xl w-full max-w-sm shadow-2xl animate-fade-in-up">
        {/* Header */}
        <div className="flex items-center justify-between px-5 py-4 border-b border-white/[0.06]">
          <div className="flex items-center gap-2.5">
            <div className="p-2 rounded-lg bg-red-500/10 border border-red-500/20">
              <AlertTriangle className="w-4 h-4 text-red-400" />
            </div>
            <div>
              <div className="text-[10px] text-gray-500 uppercase tracking-widest font-medium">Destructive action</div>
              <div className="text-sm font-bold text-white">Clear Canvas</div>
            </div>
          </div>
          <button
            onClick={onCancel}
            className="p-1.5 rounded-lg text-gray-500 hover:text-white hover:bg-white/5 transition-colors"
          >
            <X className="w-4 h-4" />
          </button>
        </div>

        {/* Body */}
        <div className="px-5 py-4 space-y-3">
          <p className="text-sm text-gray-300 leading-relaxed">
            Are you sure you want to clear the canvas? This will remove all nodes and connections.
          </p>
          <p className="text-xs text-gray-500 leading-relaxed">
            The route itself won't be deleted — only the current canvas layout will be wiped. You can rebuild from
            scratch or reload from the backend.
          </p>
        </div>

        {/* Footer */}
        <div className="flex items-center justify-end gap-2 px-5 py-3.5 border-t border-white/[0.06]">
          <button onClick={onCancel} className="px-3 py-1.5 text-sm text-gray-400 hover:text-white transition-colors">
            Cancel
          </button>
          <button
            onClick={onConfirm}
            className="flex items-center gap-1.5 px-4 py-1.5 bg-red-600 hover:bg-red-500 text-white text-sm font-semibold rounded-lg transition-colors"
          >
            <Trash2 className="w-3.5 h-3.5" />
            Clear Canvas
          </button>
        </div>
      </div>
    </div>
  )
}

// ─── Page ────────────────────────────────────────────────────────────────────

export default function WorkflowBuilderPage() {
  useDocumentTitle('Route Builder')
  const { routeId } = useParams<{ routeId: string }>()
  const navigate = useNavigate()
  const qc = useQueryClient()

  const { initFromGraph, isDirty, markClean, resetCanvas, clearCanvas, setRouteStatus } = useWorkflowStore()

  /** Controls the Clear Canvas confirmation dialog */
  const [clearDialogOpen, setClearDialogOpen] = useState(false)

  // ── Fetch route ────────────────────────────────────────────────────────────
  const { data: route, isLoading } = useRealtimeQuery({
    queryKey: ['route', routeId],
    queryFn: () => routesApi.get(routeId!),
    wsEvents: ['route'],
  })

  /** Derived lock state — active routes cannot be edited */
  const isLocked = route?.status === 'ACTIVE'

  // onDetach callback — defined before initFromRoute call
  const detachCallback = useCallback(
    (filterId: string) =>
      routesApi.detachFilter(routeId!, filterId).then(() => {
        qc.invalidateQueries({ queryKey: ['route', routeId] })
      }),
    [routeId, qc],
  )

  // Seed canvas whenever the route data changes (initial load or after save)
  useEffect(() => {
    if (route) {
      // Keep store's routeStatus in sync for hard-guards inside setNodes/setEdges
      setRouteStatus(route.status)
      // Use empty skeleton graph for routes with no filters yet
      const { nodes, edges } =
        (route.filters?.length ?? 0) > 0
          ? buildGraph(route, detachCallback, (nodeId: string) => {
              useWorkflowStore.getState().selectNode(nodeId)
            })
          : buildEmptyGraph(route)
      initFromGraph(route.id, route.status, nodes, edges)
    }
  }, [route, initFromGraph, detachCallback, setRouteStatus])

  // Reset store on unmount to avoid stale state if the user navigates away
  useEffect(() => () => resetCanvas(), [resetCanvas])

  // ── Clear canvas handler ───────────────────────────────────────────────────
  const handleClearConfirm = useCallback(() => {
    if (isLocked) {
      console.warn('[WorkflowBuilder] Edit blocked: route is active', { routeId, action: 'clearCanvas' })
      return
    }
    clearCanvas()
    setClearDialogOpen(false)
    toast.success('Canvas cleared', {
      description: 'All nodes and connections have been removed. Drag nodes from the palette to rebuild.',
    })
  }, [clearCanvas, isLocked, routeId])

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
      toast.success('Route paused', { description: 'You can now make changes.' })
    },
    onError: (err) => toast.error('Cannot pause', { description: extractApiError(err) }),
  })

  // "Save Draft" persists current canvas node properties to the backend.
  // Edge / layout changes are purely local and don't map to the route schema.
  const saveMutation = useMutation({
    mutationFn: () =>
      routesApi.update(routeId!, {
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
          <div
            className={cn(
              'flex items-center gap-1 text-[11px] font-semibold px-2 py-0.5 rounded-full border',
              sc.color,
              route.status === 'ACTIVE'
                ? 'bg-emerald-500/10 border-emerald-500/20'
                : route.status === 'DRAFT'
                  ? 'bg-amber-500/10 border-amber-500/20'
                  : 'bg-gray-500/10 border-gray-500/20',
            )}
          >
            {sc.icon}
            {route.status}
          </div>

          {/* Version */}
          <span className="text-[10px] text-gray-600 font-mono">v{route.version}</span>

          {/* Dirty indicator (only when not locked) */}
          {isDirty && !isLocked && (
            <span className="text-[10px] text-amber-400 bg-amber-400/10 border border-amber-400/20 px-1.5 py-0.5 rounded-full">
              Unsaved changes
            </span>
          )}

          {/* Lock indicator */}
          {isLocked && (
            <span className="flex items-center gap-1 text-[10px] text-emerald-400 bg-emerald-400/10 border border-emerald-400/20 px-1.5 py-0.5 rounded-full">
              <Lock className="w-2.5 h-2.5" /> Read-only
            </span>
          )}
        </div>

        {/* Right: action buttons */}
        <div className="flex items-center gap-2">
          {/* Clear Canvas — disabled when route is active */}
          <button
            onClick={() => !isLocked && setClearDialogOpen(true)}
            disabled={isLocked}
            className={cn(
              'flex items-center gap-1.5 px-3 py-1.5 rounded-lg text-xs font-semibold transition-all border',
              isLocked
                ? 'text-gray-600 bg-transparent border-white/[0.06] cursor-not-allowed opacity-50'
                : 'text-red-400 bg-red-400/10 border-red-400/20 hover:bg-red-400/20',
            )}
            title={
              isLocked
                ? 'Pause the route first to clear the canvas'
                : 'Remove all nodes and connections from the canvas'
            }
          >
            <Trash2 className="w-3.5 h-3.5" />
            Clear
          </button>

          {/* Save Draft — disabled when route is active */}
          <button
            onClick={() => !isLocked && saveMutation.mutate()}
            disabled={saveMutation.isPending || isLocked}
            className={cn(
              'flex items-center gap-1.5 px-3 py-1.5 rounded-lg text-xs font-semibold border transition-all',
              isLocked
                ? 'text-gray-600 bg-transparent border-white/[0.06] cursor-not-allowed opacity-50'
                : 'text-gray-300 bg-white/[0.05] border-white/[0.08] hover:bg-white/[0.08] disabled:opacity-50',
            )}
            title={isLocked ? 'Pause the route first to save changes' : undefined}
          >
            {saveMutation.isPending ? (
              <RefreshCw className="w-3.5 h-3.5 animate-spin" />
            ) : (
              <Save className="w-3.5 h-3.5" />
            )}
            Save
          </button>

          {/* Active route: show Pause button prominently */}
          {route.status === 'ACTIVE' && (
            <>
              <div className="flex items-center gap-1.5 text-[11px] text-emerald-400">
                <Zap className="w-3.5 h-3.5" />
                <span>Live</span>
              </div>
              <button
                onClick={() => deactivateMutation.mutate()}
                disabled={deactivateMutation.isPending}
                className="flex items-center gap-1.5 px-3 py-1.5 rounded-lg text-xs font-semibold text-amber-300 bg-amber-500/10 border border-amber-500/20 hover:bg-amber-500/20 disabled:opacity-50 transition-all"
              >
                {deactivateMutation.isPending ? (
                  <RefreshCw className="w-3.5 h-3.5 animate-spin" />
                ) : (
                  <Pause className="w-3.5 h-3.5" />
                )}
                {deactivateMutation.isPending ? 'Pausing…' : 'Pause to Edit'}
              </button>
            </>
          )}

          {/* Draft / Disabled route: show Activate */}
          {(route.status === 'DRAFT' || route.status === 'DISABLED') && (
            <button
              onClick={() => activateMutation.mutate()}
              disabled={activateMutation.isPending}
              className="flex items-center gap-1.5 px-3 py-1.5 rounded-lg text-xs font-semibold text-emerald-300 bg-emerald-500/10 border border-emerald-500/20 hover:bg-emerald-500/20 disabled:opacity-50 transition-all shadow-lg shadow-emerald-500/10"
            >
              {activateMutation.isPending ? (
                <RefreshCw className="w-3.5 h-3.5 animate-spin" />
              ) : (
                <Play className="w-3.5 h-3.5" />
              )}
              Activate
            </button>
          )}
        </div>
      </div>

      {/* ── Active-route lock banner ──────────────────────────────────────────── */}
      {isLocked && (
        <div className="flex items-center gap-3 px-4 py-2.5 bg-emerald-500/[0.06] border-b border-emerald-500/20 shrink-0">
          <Lock className="w-3.5 h-3.5 text-emerald-400 shrink-0" />
          <p className="text-xs text-emerald-300 flex-1">
            <span className="font-semibold">This route is active.</span> Pause it to make changes — adding nodes,
            removing filters, editing properties, and clearing the canvas are all disabled while live.
          </p>
          <button
            onClick={() => deactivateMutation.mutate()}
            disabled={deactivateMutation.isPending}
            className="flex items-center gap-1.5 px-3 py-1.5 rounded-lg text-[11px] font-semibold text-amber-300 bg-amber-500/10 border border-amber-500/20 hover:bg-amber-500/20 disabled:opacity-50 transition-all shrink-0"
          >
            {deactivateMutation.isPending ? (
              <RefreshCw className="w-3 h-3 animate-spin" />
            ) : (
              <Pause className="w-3 h-3" />
            )}
            Pause Route
          </button>
        </div>
      )}

      {/* ── Main canvas area ─────────────────────────────────────────────────── */}
      <div className="flex flex-1 min-h-0 overflow-hidden">
        {/* Node palette sidebar */}
        <NodePalette />

        {/* Canvas + PropertiesDrawer overlay */}
        <WorkflowCanvas route={route} />
      </div>

      {/* ── Clear Canvas confirmation dialog ─────────────────────────────────── */}
      {clearDialogOpen && (
        <ClearCanvasDialog onConfirm={handleClearConfirm} onCancel={() => setClearDialogOpen(false)} />
      )}
    </div>
  )
}
