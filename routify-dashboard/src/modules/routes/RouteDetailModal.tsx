import { useState } from 'react'
import { useQuery, useQueryClient } from '@tanstack/react-query'
import { X, Play, Pause, Zap, Terminal, Pencil, Network, List, RefreshCw } from 'lucide-react'
import { routesApi } from '../../api/routesApi'
import { cn } from '../../lib/utils'
import RouteFlowCanvas from './RouteFlowCanvas'
import RouteCurlModal from './RouteCurlModal'
import RouteFormModal from './RouteFormModal'
import { useRouteActions } from './useRouteActions'

export default function RouteDetailModal({ routeId, onClose }: { routeId: string; onClose: () => void }) {
  const qc = useQueryClient()
  const [view, setView]           = useState<'flow' | 'config'>('flow')
  const [curlOpen, setCurlOpen]   = useState(false)
  const [editOpen, setEditOpen]   = useState(false)

  const { data: route, isLoading } = useQuery({
    queryKey: ['route', routeId],
    queryFn:  () => routesApi.get(routeId),
  })

  const { activateMutation, deactivateMutation } = useRouteActions(routeId)

  return (
    <div className="fixed inset-0 z-50 flex items-center justify-center p-4 bg-black/70 backdrop-blur-sm animate-fade-in">
      <div className="bg-[#0d0f14] border border-white/10 rounded-2xl w-full max-w-5xl max-h-[92vh] flex flex-col shadow-2xl animate-fade-in-up overflow-hidden">

        {/* ── Header ────────────────────────────────────────────────────────── */}
        <div className="flex items-center justify-between px-6 py-4 border-b border-white/10 shrink-0">
          <div className="flex items-center gap-3">
            <div className={cn('w-2.5 h-2.5 rounded-full shrink-0', {
              'bg-emerald-400 shadow-[0_0_6px_rgba(52,211,153,0.6)]': route?.status === 'ACTIVE',
              'bg-amber-400': route?.status === 'DRAFT',
              'bg-gray-500':  route?.status === 'DISABLED',
              'bg-red-500':   route?.status === 'ARCHIVED',
            })} />
            <h2 className="text-base font-bold text-white">{route?.name ?? '…'}</h2>
            {route && <span className="text-xs text-gray-600 font-mono">v{route.version}</span>}
          </div>

          <div className="flex items-center gap-2">
            {route && (
              <>
                {/* Status toggle */}
                {(route.status === 'DRAFT' || route.status === 'DISABLED') && (
                  <button
                    onClick={() => activateMutation.mutate(routeId)}
                    disabled={activateMutation.isPending}
                    className="flex items-center gap-1.5 px-2.5 py-1.5 rounded-lg text-[11px] font-semibold text-emerald-300 bg-emerald-500/10 border border-emerald-500/20 hover:bg-emerald-500/20 disabled:opacity-50 transition-all"
                  >
                    {activateMutation.isPending
                      ? <RefreshCw className="w-3 h-3 animate-spin" />
                      : <Play className="w-3 h-3" />}
                    {activateMutation.isPending ? 'Activating…' : 'Activate'}
                  </button>
                )}
                {route.status === 'ACTIVE' && (
                  <button
                    onClick={() => deactivateMutation.mutate(routeId)}
                    disabled={deactivateMutation.isPending}
                    className="flex items-center gap-1.5 px-2.5 py-1.5 rounded-lg text-[11px] font-semibold text-amber-300 bg-amber-500/10 border border-amber-500/20 hover:bg-amber-500/20 disabled:opacity-50 transition-all"
                  >
                    <Pause className="w-3 h-3" />
                    Deactivate
                  </button>
                )}

                <button
                  onClick={() => setEditOpen(true)}
                  className="flex items-center gap-1.5 px-2.5 py-1.5 rounded-lg text-[11px] font-semibold text-indigo-300 bg-indigo-500/10 border border-indigo-500/20 hover:bg-indigo-500/20 transition-all"
                >
                  <Pencil className="w-3 h-3" /> Edit
                </button>

                <button
                  onClick={() => setCurlOpen(true)}
                  className="flex items-center gap-1.5 px-2.5 py-1.5 rounded-lg text-[11px] font-semibold text-violet-300 bg-violet-500/10 border border-violet-500/20 hover:bg-violet-500/20 transition-all"
                >
                  <Terminal className="w-3 h-3" /> cURL
                </button>

                {/* Flow ↔ Config toggle */}
                <div className="flex items-center bg-white/[0.04] border border-white/[0.07] rounded-lg p-0.5">
                  <button
                    onClick={() => setView('flow')}
                    className={cn('flex items-center gap-1 px-2.5 py-1.5 rounded-md text-[11px] font-medium transition-all',
                      view === 'flow' ? 'bg-indigo-600 text-white shadow' : 'text-gray-500 hover:text-gray-300')}
                  >
                    <Network className="w-3 h-3" /> Flow
                  </button>
                  <button
                    onClick={() => setView('config')}
                    className={cn('flex items-center gap-1 px-2.5 py-1.5 rounded-md text-[11px] font-medium transition-all',
                      view === 'config' ? 'bg-indigo-600 text-white shadow' : 'text-gray-500 hover:text-gray-300')}
                  >
                    <List className="w-3 h-3" /> Config
                  </button>
                </div>
              </>
            )}

            <button
              onClick={onClose}
              className="p-1.5 rounded-lg text-gray-400 hover:text-white hover:bg-white/5 transition-colors"
            >
              <X className="w-5 h-5" />
            </button>
          </div>
        </div>

        {/* ── Body ──────────────────────────────────────────────────────────── */}
        <div className="flex-1 overflow-y-auto p-6 space-y-4 min-h-0">
          {isLoading ? (
            <div className="flex items-center justify-center py-20">
              <div className="w-6 h-6 border-2 border-indigo-500 border-t-transparent rounded-full animate-spin" />
            </div>
          ) : route ? (
            <>
              {route.status === 'ACTIVE' && (
                <div className="flex items-center gap-2 p-3 bg-emerald-500/5 border border-emerald-500/20 rounded-lg text-xs text-emerald-400 shrink-0">
                  <Zap className="w-4 h-4 shrink-0" />
                  <span>Route is <strong>live</strong>. Filter chain changes apply instantly via Kafka hot-reload.</span>
                </div>
              )}

              {/* FLOW VIEW — interactive canvas with Add Filter panel */}
              {view === 'flow' && (
                <RouteFlowCanvas route={route} height={520} />
              )}

              {/* CONFIG VIEW — read-only details */}
              {view === 'config' && (
                <div className="bg-white/[0.03] rounded-xl p-5 space-y-3 text-sm border border-white/[0.05]">
                  <ConfigRow label="Path"     value={<code className="font-mono text-indigo-300 text-xs">{route.pathPattern}</code>} />
                  <ConfigRow label="Methods"  value={
                    <div className="flex gap-1 flex-wrap">
                      {route.methods.split(',').map(m => (
                        <span key={m} className="text-[10px] px-1.5 py-0.5 rounded bg-indigo-500/20 text-indigo-300 font-mono font-bold">{m.trim()}</span>
                      ))}
                    </div>
                  } />
                  <ConfigRow label="Upstream" value={<code className="font-mono text-gray-300 text-xs break-all">{route.upstreamUri}</code>} />
                  {route.stripPrefix && (
                    <ConfigRow label="Strip Prefix" value={<code className="font-mono text-xs text-gray-400">{route.stripPrefix}</code>} />
                  )}
                  {route.description && (
                    <ConfigRow label="Description"  value={<span className="text-gray-400">{route.description}</span>} />
                  )}
                  <ConfigRow label="Filters"  value={<span className="text-gray-300">{route.filters?.length ?? 0} attached</span>} />
                  <ConfigRow label="Status"   value={<span className="text-gray-300 font-semibold">{route.status}</span>} />
                  <ConfigRow label="Created"  value={<span className="text-gray-500 text-xs">{new Date(route.createdAt).toLocaleString()}</span>} />
                  {route.activatedAt && (
                    <ConfigRow label="Activated" value={<span className="text-gray-500 text-xs">{new Date(route.activatedAt).toLocaleString()}</span>} />
                  )}
                </div>
              )}
            </>
          ) : null}
        </div>
      </div>

      {curlOpen && route && (
        <RouteCurlModal route={route} onClose={() => setCurlOpen(false)} />
      )}
      {editOpen && (
        <RouteFormModal
          editingId={routeId}
          onClose={() => setEditOpen(false)}
          onSaved={() => {
            setEditOpen(false)
            qc.invalidateQueries({ queryKey: ['route', routeId] })
            qc.invalidateQueries({ queryKey: ['routes'] })
          }}
        />
      )}
    </div>
  )
}

function ConfigRow({ label, value }: { label: string; value: React.ReactNode }) {
  return (
    <div className="flex items-start gap-4">
      <span className="text-gray-500 w-24 shrink-0 text-sm">{label}</span>
      <div className="flex-1 min-w-0">{value}</div>
    </div>
  )
}

