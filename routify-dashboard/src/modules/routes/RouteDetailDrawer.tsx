import { useState } from 'react'
import { useQuery, useQueryClient } from '@tanstack/react-query'
import {
  X, Play, Pause, Plus, Trash2, Shield, Zap, RefreshCw, GitBranch,
  Gauge, Code2, ToggleLeft, Network, List, Terminal, Pencil, Copy,
} from 'lucide-react'
import { routesApi } from '../../api/routesApi'
import type { RouteFilterRef } from '../../types'
import { cn } from '../../lib/utils'
import RouteFlowCanvas from './RouteFlowCanvas'
import RouteCurlModal from './RouteCurlModal'
import RouteFormModal from './RouteFormModal'
import { useFilterChain } from './useFilterChain'
import { Select } from '../../components/ui/Select'
import { useRouteActions } from './useRouteActions'

const FILTER_CATEGORY_ICONS: Record<string, React.ReactNode> = {
  AUTH_JWT:                 <Shield className="w-4 h-4 text-green-400" />,
  AUTH_API_KEY:             <Shield className="w-4 h-4 text-blue-400" />,
  RATE_LIMIT_TOKEN_BUCKET:  <Gauge className="w-4 h-4 text-yellow-400" />,
  CIRCUIT_BREAKER:          <RefreshCw className="w-4 h-4 text-orange-400" />,
  BODY_JOLT_TRANSFORM:      <Code2 className="w-4 h-4 text-purple-400" />,
  CONDITIONAL_ROUTE:        <GitBranch className="w-4 h-4 text-indigo-400" />,
  API_VERSIONING:           <ToggleLeft className="w-4 h-4 text-cyan-400" />,
}

export default function RouteDetailDrawer({ routeId, onClose }: { routeId: string; onClose: () => void }) {
  const qc = useQueryClient()
  const [detailView, setDetailView] = useState<'workflow' | 'config'>('workflow')
  const [curlOpen, setCurlOpen] = useState(false)
  const [editOpen, setEditOpen] = useState(false)

  const { data: route, isLoading } = useQuery({
    queryKey: ['route', routeId],
    queryFn: () => routesApi.get(routeId),
  })

  const { activateMutation, deactivateMutation, cloneMutation } = useRouteActions(routeId)

  // Clone closes the drawer on success so the user sees the new card in the list
  const handleClone = () => {
    cloneMutation.mutate(routeId, {
      onSuccess: () => onClose(),
    })
  }

  // ── Filter chain hook ───────────────────────────────────────────────────────
  const filterChain = useFilterChain(routeId)

  return (
    <div className="fixed inset-0 z-50 flex">
      <div className="flex-1 bg-black/50 backdrop-blur-sm" onClick={onClose} />
      <div className="w-[620px] bg-[#0d0f14] border-l border-white/10 h-full overflow-y-auto flex flex-col">
        {/* Header */}
        <div className="sticky top-0 bg-[#0d0f14] px-6 py-4 border-b border-white/10 flex items-center justify-between z-10">
          <div className="flex items-center gap-3">
            <div className={cn('w-2.5 h-2.5 rounded-full', {
              'bg-emerald-400 shadow-[0_0_6px_rgba(52,211,153,0.6)]': route?.status === 'ACTIVE',
              'bg-yellow-400': route?.status === 'DRAFT',
              'bg-gray-500':   route?.status === 'DISABLED',
              'bg-red-500':    route?.status === 'ARCHIVED',
            })} />
            <h2 className="text-base font-bold text-white">{route?.name ?? '…'}</h2>
          </div>
          <div className="flex items-center gap-2">
            {route && (
              <>
                <button
                  onClick={() => setEditOpen(true)}
                  className="flex items-center gap-1.5 px-2.5 py-1.5 rounded-lg text-[11px] font-semibold text-indigo-300 bg-indigo-500/10 border border-indigo-500/20 hover:bg-indigo-500/20 transition-all"
                  title="Edit Route"
                >
                  <Pencil className="w-3 h-3" />
                  Edit
                </button>
                <button
                  onClick={handleClone}
                  disabled={cloneMutation.isPending}
                  className="flex items-center gap-1.5 px-2.5 py-1.5 rounded-lg text-[11px] font-semibold text-cyan-300 bg-cyan-500/10 border border-cyan-500/20 hover:bg-cyan-500/20 transition-all disabled:opacity-50"
                  title="Clone Route"
                >
                  {cloneMutation.isPending
                    ? <RefreshCw className="w-3 h-3 animate-spin" />
                    : <Copy className="w-3 h-3" />}
                  Clone
                </button>
                <button
                  onClick={() => setCurlOpen(true)}
                  className="flex items-center gap-1.5 px-2.5 py-1.5 rounded-lg text-[11px] font-semibold text-violet-300 bg-violet-500/10 border border-violet-500/20 hover:bg-violet-500/20 transition-all"
                  title="Generate cURL"
                >
                  <Terminal className="w-3 h-3" />
                  cURL
                </button>
                <div className="flex items-center bg-white/[0.04] border border-white/[0.07] rounded-lg p-0.5">
                  <button
                    onClick={() => setDetailView('workflow')}
                    className={cn(
                      'flex items-center gap-1 px-2.5 py-1.5 rounded-md text-[11px] font-medium transition-all',
                      detailView === 'workflow' ? 'bg-indigo-600 text-white shadow' : 'text-gray-500 hover:text-gray-300',
                    )}
                  >
                    <Network className="w-3 h-3" />
                    Flow
                  </button>
                  <button
                    onClick={() => setDetailView('config')}
                    className={cn(
                      'flex items-center gap-1 px-2.5 py-1.5 rounded-md text-[11px] font-medium transition-all',
                      detailView === 'config' ? 'bg-indigo-600 text-white shadow' : 'text-gray-500 hover:text-gray-300',
                    )}
                  >
                    <List className="w-3 h-3" />
                    Config
                  </button>
                </div>
              </>
            )}
            <button onClick={onClose} className="p-1.5 rounded-lg text-gray-400 hover:text-white hover:bg-white/5 transition-colors">
              <X className="w-5 h-5" />
            </button>
          </div>
        </div>

        {/* Body */}
        {isLoading ? (
          <div className="flex items-center justify-center py-20">
            <div className="w-6 h-6 border-2 border-indigo-500 border-t-transparent rounded-full animate-spin" />
          </div>
        ) : route ? (
          <div className="flex-1 p-6 space-y-6">
            {/* Status bar & actions */}
            <div className="flex items-center justify-between">
              <div className="text-sm text-gray-400">
                <span className="text-white font-medium">{route.status}</span>
                {' · '} v{route.version}
                {route.activatedAt && (
                  <> · Active since {new Date(route.activatedAt).toLocaleDateString()}</>
                )}
              </div>
              <div className="flex gap-2">
                {(route.status === 'DRAFT' || route.status === 'DISABLED') && (
                  <button
                    onClick={() => activateMutation.mutate(routeId)}
                    disabled={activateMutation.isPending}
                    className="flex items-center gap-1.5 px-3 py-1.5 bg-green-600 hover:bg-green-500 disabled:opacity-50 text-white text-sm rounded-lg transition-colors"
                  >
                    {activateMutation.isPending
                      ? <RefreshCw className="w-3.5 h-3.5 animate-spin" />
                      : <Play className="w-3.5 h-3.5" />}
                    {activateMutation.isPending ? 'Activating…' : 'Activate'}
                  </button>
                )}
                {route.status === 'ACTIVE' && (
                  <button
                    onClick={() => deactivateMutation.mutate(routeId)}
                    disabled={deactivateMutation.isPending}
                    className="flex items-center gap-1.5 px-3 py-1.5 bg-yellow-600/80 hover:bg-yellow-600 disabled:opacity-50 text-white text-sm rounded-lg transition-colors"
                  >
                    <Pause className="w-3.5 h-3.5" />
                    Deactivate
                  </button>
                )}
              </div>
            </div>

            {/* FLOW VIEW */}
            {detailView === 'workflow' && (
              <div className="space-y-5">
                {route.status === 'ACTIVE' && (
                  <div className="flex items-center gap-2 p-3 bg-green-500/5 border border-green-500/20 rounded-lg text-xs text-green-400">
                    <Zap className="w-4 h-4 shrink-0" />
                    <span>This route is <strong>live</strong>. Filter chain changes apply instantly via Kafka hot-reload.</span>
                  </div>
                )}
                <div>
                  <div className="flex items-center justify-between mb-2.5">
                    <h3 className="text-sm font-semibold text-white flex items-center gap-1.5">
                      <Network className="w-4 h-4 text-indigo-400" />
                      Route Workflow
                    </h3>
                    <span className="text-[10px] text-gray-600 italic">Drag nodes · Scroll to zoom</span>
                  </div>
                  <RouteFlowCanvas route={route} height={360} />
                </div>
                <FilterChainSection filters={route.filters} filterChain={filterChain} />
              </div>
            )}

            {/* CONFIG VIEW */}
            {detailView === 'config' && (
              <div className="space-y-6">
                {route.status === 'ACTIVE' && (
                  <div className="flex items-center gap-2 p-3 bg-green-500/5 border border-green-500/20 rounded-lg text-xs text-green-400">
                    <Zap className="w-4 h-4 shrink-0" />
                    <span>This route is <strong>live</strong>. Changes apply instantly via Kafka hot-reload — no restart needed.</span>
                  </div>
                )}
                <div className="bg-white/[0.03] rounded-xl p-4 space-y-3 text-sm border border-white/[0.05]">
                  <DetailRow label="Path" value={<code className="font-mono text-indigo-300 text-xs">{route.pathPattern}</code>} />
                  <DetailRow label="Methods" value={
                    <div className="flex gap-1 flex-wrap">
                      {route.methods.split(',').map(m => (
                        <span key={m} className="text-[10px] px-1.5 py-0.5 rounded bg-indigo-500/20 text-indigo-300 font-mono font-bold">
                          {m.trim()}
                        </span>
                      ))}
                    </div>
                  } />
                  <DetailRow label="Upstream" value={
                    <code className="font-mono text-gray-300 text-xs break-all">{route.upstreamUri}</code>
                  } />
                  {route.stripPrefix && (
                    <DetailRow label="Strip Prefix" value={<code className="font-mono text-xs text-gray-400">{route.stripPrefix}</code>} />
                  )}
                  {route.description && (
                    <DetailRow label="Description" value={<span className="text-gray-400">{route.description}</span>} />
                  )}
                </div>
                <FilterChainSection filters={route.filters} filterChain={filterChain} />
              </div>
            )}
          </div>
        ) : null}
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

// ─── FilterChainSection ────────────────────────────────────────────────────────

type FilterChainHook = ReturnType<typeof useFilterChain>

function FilterChainSection({
  filters,
  filterChain,
}: {
  filters: RouteFilterRef[]
  filterChain: FilterChainHook
}) {
  const {
    attachMode, selectedFilter, filterOrder, filterPhase,
    availableFilters, isAttaching,
    toggleAttach, cancelAttach, setSelectedFilter, setFilterOrder, setFilterPhase,
    attach, detach,
  } = filterChain

  const preFilters  = [...filters].filter(f => f.phase === 'PRE').sort((a, b) => a.order - b.order)
  const postFilters = [...filters].filter(f => f.phase === 'POST').sort((a, b) => a.order - b.order)

  return (
    <div>
      <div className="flex items-center justify-between mb-3">
        <h3 className="text-sm font-semibold text-white">Filter Chain</h3>
        <button
          onClick={toggleAttach}
          className="flex items-center gap-1 text-xs text-indigo-400 hover:text-indigo-300 transition-colors"
        >
          <Plus className="w-3.5 h-3.5" />
          Attach Filter
        </button>
      </div>

      {filters.length === 0 ? (
        <div className="text-center py-6 text-gray-600 text-sm bg-white/[0.02] rounded-xl border border-dashed border-white/[0.06]">
          No filters attached · Routes work without filters
        </div>
      ) : (
        <div className="space-y-1.5">
          {preFilters.length > 0 && (
            <>
              <div className="text-[9px] text-blue-400/70 font-bold uppercase tracking-widest px-1 mb-1">PRE — Before Upstream</div>
              {preFilters.map(f => (
                <FilterChainItem key={f.filterId} f={f} onDetach={() => detach(f.filterId)} />
              ))}
            </>
          )}
          {preFilters.length > 0 && postFilters.length > 0 && (
            <div className="flex items-center gap-2 py-2 px-1">
              <div className="flex-1 h-px bg-white/[0.05]" />
              <span className="text-[9px] text-gray-600 uppercase tracking-widest font-medium">↕ Upstream</span>
              <div className="flex-1 h-px bg-white/[0.05]" />
            </div>
          )}
          {postFilters.length > 0 && (
            <>
              <div className="text-[9px] text-purple-400/70 font-bold uppercase tracking-widest px-1 mb-1">POST — After Upstream</div>
              {postFilters.map(f => (
                <FilterChainItem key={f.filterId} f={f} onDetach={() => detach(f.filterId)} />
              ))}
            </>
          )}
        </div>
      )}

      {attachMode && (
        <div className="mt-3 p-4 bg-white/[0.03] rounded-xl border border-white/10 space-y-3">
          <h4 className="text-sm font-medium text-white">Attach Filter</h4>
          <Select
            value={selectedFilter}
            onChange={setSelectedFilter}
            placeholder="Select a filter…"
            searchable
            options={availableFilters.map(f => ({
              value: f.id,
              label: f.name,
              description: f.filterType,
            }))}
          />
          <div className="flex gap-3">
            <div className="flex-1">
              <label className="block text-xs text-gray-400 mb-1">Order</label>
              <input
                type="number"
                min={0}
                value={filterOrder}
                onChange={e => setFilterOrder(Number(e.target.value))}
                className="w-full bg-[#0d0f14] border border-white/10 rounded-lg px-3 py-2 text-sm text-white focus:outline-none focus:border-indigo-500"
              />
            </div>
            <div className="flex-1">
              <label className="block text-xs text-gray-400 mb-1">Phase</label>
              <Select
                value={filterPhase}
                onChange={v => setFilterPhase(v as 'PRE' | 'POST')}
                options={[
                  { value: 'PRE',  label: '↑ PRE', description: 'Runs before forwarding to upstream' },
                  { value: 'POST', label: '↓ POST', description: 'Runs after upstream responds' },
                ]}
              />
            </div>
          </div>
          <div className="flex justify-end gap-2">
            <button onClick={cancelAttach} className="px-3 py-1.5 text-sm text-gray-400 hover:text-white transition-colors">
              Cancel
            </button>
            <button
              disabled={!selectedFilter || isAttaching}
              onClick={attach}
              className="px-3 py-1.5 bg-indigo-600 hover:bg-indigo-500 disabled:opacity-50 text-white text-sm rounded-lg transition-colors"
            >
              {isAttaching ? 'Attaching…' : 'Attach'}
            </button>
          </div>
        </div>
      )}
    </div>
  )
}

// ─── FilterChainItem ───────────────────────────────────────────────────────────

function FilterChainItem({ f, onDetach }: { f: RouteFilterRef; onDetach: () => void }) {
  return (
    <div className="flex items-center gap-3 p-3 bg-white/[0.03] rounded-lg border border-white/[0.04] group hover:border-white/[0.08] transition-colors">
      <div className="text-gray-600 text-xs font-mono w-4 text-center shrink-0">{f.order}</div>
      <div className="shrink-0">
        {FILTER_CATEGORY_ICONS[f.filterType] ?? <Shield className="w-4 h-4 text-gray-400" />}
      </div>
      <div className="flex-1 min-w-0">
        <div className="text-sm text-white font-medium truncate">{f.filterName}</div>
        <div className="text-xs text-gray-500">{f.filterType}</div>
      </div>
      <div className={cn(
        'text-[10px] px-1.5 py-0.5 rounded font-bold shrink-0',
        f.phase === 'PRE' ? 'bg-blue-500/20 text-blue-300' : 'bg-purple-500/20 text-purple-300',
      )}>
        {f.phase}
      </div>
      <button
        onClick={onDetach}
        className="opacity-0 group-hover:opacity-100 p-1 rounded text-red-400 hover:bg-red-400/10 transition-all shrink-0"
        title="Detach filter"
      >
        <Trash2 className="w-3.5 h-3.5" />
      </button>
    </div>
  )
}

// ─── DetailRow ─────────────────────────────────────────────────────────────────

function DetailRow({ label, value }: { label: string; value: React.ReactNode }) {
  return (
    <div className="flex items-start gap-4">
      <span className="text-gray-500 w-24 shrink-0 text-sm">{label}</span>
      <div className="flex-1 min-w-0">{value}</div>
    </div>
  )
}
