import { useState } from 'react'
import { Play, Pause, Trash2, Edit, RefreshCw, Server, Filter, CheckCircle, Terminal, Copy } from 'lucide-react'
import { cn } from '../../../lib/utils'
import type { RouteSummary } from '../../../types'
import { STATUS_CONFIG, METHOD_COLORS } from '../routeConstants'
import { PipelineNode, PipelineArrow, FilterPill } from './PipelineComponents'
import ConfirmDeletePopover from './ConfirmDeletePopover'

interface Props {
  route: RouteSummary
  index: number
  onSelect: () => void
  onEdit: () => void
  onCurl: () => void
  onClone: () => void
  onActivate: () => void
  onDeactivate: () => void
  onDelete: () => void
  isActivating: boolean
  isCloning: boolean
}

export default function RouteWorkflowCard({
  route, index, onSelect, onEdit, onCurl, onClone, onActivate, onDeactivate, onDelete, isActivating, isCloning,
}: Props) {
  const sc = STATUS_CONFIG[route.status]
  const methods = route.methods.split(',').map(m => m.trim()).filter(Boolean)
  const [confirmDelete, setConfirmDelete] = useState(false)

  return (
    <div
      className={cn(
        'group bg-[#0d0f14] border rounded-2xl overflow-hidden transition-all duration-200 cursor-pointer',
        'hover:border-indigo-500/30 hover:shadow-xl hover:shadow-indigo-500/[0.07]',
        route.status === 'ACTIVE'
          ? 'border-white/[0.08] shadow-lg shadow-emerald-500/[0.04]'
          : 'border-white/[0.06]',
      )}
      style={{ animationDelay: `${index * 40}ms` }}
      onClick={onSelect}
    >
      {/* ── Card Header ─────────────────────────────────────────────────────── */}
      <div className="px-4 py-3.5 border-b border-white/[0.05] flex items-start justify-between gap-3">
        <div className="flex items-start gap-3 flex-1 min-w-0">
          {/* Status indicator */}
          <div className="mt-0.5 relative shrink-0">
            <div className={cn('w-2.5 h-2.5 rounded-full', sc.dot)} />
            {route.status === 'ACTIVE' && (
              <div className="absolute inset-0 w-2.5 h-2.5 rounded-full bg-emerald-400 animate-ping opacity-40" />
            )}
          </div>
          <div className="flex-1 min-w-0">
            <div className="flex items-center gap-2 mb-0.5">
              <h3 className="text-sm font-bold text-white truncate">{route.name}</h3>
              <span className="text-[9px] text-gray-600 font-mono shrink-0">v{route.version}</span>
            </div>
            {route.description && (
              <p className="text-[11px] text-gray-500 truncate">{route.description}</p>
            )}
          </div>
        </div>

        <div className="flex items-center gap-1.5 shrink-0" onClick={e => e.stopPropagation()}>
          <span className={cn('inline-flex items-center gap-1 text-[10px] font-semibold px-2 py-0.5 rounded-full border', sc.color)}>
            {sc.icon}
            {sc.label}
          </span>
        </div>
      </div>

      {/* ── Pipeline visualization ───────────────────────────────────────────── */}
      <div className="px-4 py-4">
        <div className="flex items-center gap-1.5 overflow-x-auto pb-1">

          {/* Client */}
          <PipelineNode
            icon={<svg className="w-3.5 h-3.5" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth={2}><circle cx="12" cy="12" r="10"/><path d="M12 2a14.5 14.5 0 0 0 0 20 14.5 14.5 0 0 0 0-20"/><path d="M2 12h20"/></svg>}
            label="Client"
            color="text-indigo-400"
            bg="bg-indigo-500/10 border-indigo-500/20"
          />

          <PipelineArrow />

          {/* PRE filters */}
          <div className="flex flex-col gap-1 shrink-0">
            <div className="text-[8px] text-blue-400/70 font-bold uppercase tracking-widest text-center mb-0.5">PRE</div>
            {route.preFilterCount === 0 ? (
              <div className="px-2 py-1 rounded-lg bg-white/[0.03] border border-dashed border-white/10 text-[9px] text-gray-600 text-center min-w-[60px]">
                No filters
              </div>
            ) : (
              <FilterPill count={route.preFilterCount} phase="PRE" />
            )}
          </div>

          <PipelineArrow />

          {/* Route config */}
          <div className="shrink-0 px-2.5 py-2 rounded-xl bg-indigo-500/10 border border-indigo-500/25 min-w-[130px]">
            <div className="text-[8px] text-indigo-400/70 font-bold uppercase tracking-widest mb-1">Route Config</div>
            <code className="text-[10px] text-indigo-300 font-mono block truncate max-w-[120px]">{route.pathPattern}</code>
            <div className="flex gap-0.5 mt-1 flex-wrap">
              {methods.map(m => (
                <span key={m} className={cn('text-[8px] px-1 py-0.5 rounded font-mono font-bold border', METHOD_COLORS[m] ?? METHOD_COLORS['*'])}>
                  {m}
                </span>
              ))}
            </div>
          </div>

          <PipelineArrow label="forward" />

          {/* Upstream */}
          <div className="shrink-0 px-2.5 py-2 rounded-xl bg-emerald-500/10 border border-emerald-500/20 min-w-[120px]">
            <div className="text-[8px] text-emerald-400/70 font-bold uppercase tracking-widest mb-1">Upstream</div>
            <div className="flex items-center gap-1">
              <Server className="w-3 h-3 text-emerald-400 shrink-0" />
              <code className="text-[10px] text-emerald-300 font-mono truncate max-w-[90px]">
                {route.upstreamUri.replace(/^https?:\/\//, '').replace(/^lb:\/\//, '⚖ ')}
              </code>
            </div>
          </div>

          <PipelineArrow />

          {/* POST filters */}
          <div className="flex flex-col gap-1 shrink-0">
            <div className="text-[8px] text-purple-400/70 font-bold uppercase tracking-widest text-center mb-0.5">POST</div>
            {route.postFilterCount === 0 ? (
              <div className="px-2 py-1 rounded-lg bg-white/[0.03] border border-dashed border-white/10 text-[9px] text-gray-600 text-center min-w-[60px]">
                No filters
              </div>
            ) : (
              <FilterPill count={route.postFilterCount} phase="POST" />
            )}
          </div>

          <PipelineArrow />

          {/* Response */}
          <PipelineNode
            icon={route.status === 'ACTIVE' ? <CheckCircle className="w-3.5 h-3.5" /> : undefined as any}
            label="Response"
            color={route.status === 'ACTIVE' ? 'text-emerald-400' : 'text-gray-500'}
            bg={route.status === 'ACTIVE' ? 'bg-emerald-500/10 border-emerald-500/20' : 'bg-white/[0.04] border-white/10'}
          />
        </div>
      </div>

      {/* ── Card Footer ──────────────────────────────────────────────────────── */}
      <div
        className="px-4 py-2.5 border-t border-white/[0.04] flex items-center justify-between gap-2 bg-white/[0.01]"
        onClick={e => e.stopPropagation()}
      >
        <div className="flex items-center gap-3 text-[10px] text-gray-600">
          <span className="flex items-center gap-1">
            <Filter className="w-3 h-3" />
            {route.filterCount} filter{route.filterCount !== 1 ? 's' : ''}
          </span>
          {route.activatedAt && (
            <span>Active since {new Date(route.activatedAt).toLocaleDateString()}</span>
          )}
        </div>

        <div className="flex items-center gap-1 relative">
          {(route.status === 'DRAFT' || route.status === 'DISABLED') && (
            <button
              onClick={onActivate}
              disabled={isActivating}
              title="Activate"
              className="flex items-center gap-1 px-2 py-1 rounded-lg text-[10px] font-semibold text-emerald-400 bg-emerald-400/10 hover:bg-emerald-400/20 border border-emerald-400/20 transition-all disabled:opacity-50"
            >
              {isActivating
                ? <RefreshCw className="w-3 h-3 animate-spin" />
                : <Play className="w-3 h-3" />}
              Activate
            </button>
          )}

          {route.status === 'ACTIVE' && (
            <button
              onClick={onDeactivate}
              title="Deactivate"
              className="flex items-center gap-1 px-2 py-1 rounded-lg text-[10px] font-semibold text-amber-400 bg-amber-400/10 hover:bg-amber-400/20 border border-amber-400/20 transition-all"
            >
              <Pause className="w-3 h-3" />
              Pause
            </button>
          )}

          {/* Edit */}
          <button
            onClick={onEdit}
            title="Edit Route"
            className="flex items-center gap-1 px-2 py-1 rounded-lg text-[10px] font-medium text-indigo-400 hover:text-indigo-300 hover:bg-indigo-500/10 border border-indigo-500/20 transition-all"
          >
            <Edit className="w-3 h-3" />
            Edit
          </button>

          {/* Clone */}
          <button
            onClick={onClone}
            disabled={isCloning}
            title="Clone Route"
            className="flex items-center gap-1 px-2 py-1 rounded-lg text-[10px] font-medium text-cyan-400 hover:text-cyan-300 hover:bg-cyan-500/10 border border-cyan-500/20 transition-all disabled:opacity-50"
          >
            {isCloning
              ? <RefreshCw className="w-3 h-3 animate-spin" />
              : <Copy className="w-3 h-3" />}
            Clone
          </button>

          {/* cURL */}
          <button
            onClick={onCurl}
            title="Generate cURL"
            className="flex items-center gap-1 px-2 py-1 rounded-lg text-[10px] font-medium text-violet-400 hover:text-violet-300 hover:bg-violet-500/10 border border-violet-500/20 transition-all"
          >
            <Terminal className="w-3 h-3" />
            cURL
          </button>

          {/* Delete */}
          {route.status !== 'ACTIVE' && (
            <div className="relative">
              <button
                onClick={() => setConfirmDelete(true)}
                title="Delete"
                className="p-1.5 rounded-lg text-gray-600 hover:text-red-400 hover:bg-red-400/10 transition-all"
              >
                <Trash2 className="w-3 h-3" />
              </button>
              {confirmDelete && (
                <ConfirmDeletePopover
                  routeName={route.name}
                  onConfirm={() => { setConfirmDelete(false); onDelete() }}
                  onCancel={() => setConfirmDelete(false)}
                />
              )}
            </div>
          )}
        </div>
      </div>
    </div>
  )
}
