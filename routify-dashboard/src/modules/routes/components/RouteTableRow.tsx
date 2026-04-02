import { useState } from 'react'
import { Play, Pause, Trash2, Edit, RefreshCw, Filter, ChevronRight, Terminal } from 'lucide-react'
import { cn } from '../../../lib/utils'
import type { RouteSummary } from '../../../types'
import { STATUS_CONFIG, METHOD_COLORS } from '../routeConstants'
import ConfirmDeletePopover from './ConfirmDeletePopover'

interface Props {
  route: RouteSummary
  index: number
  onSelect: () => void
  onEdit: () => void
  onCurl: () => void
  onActivate: () => void
  onDeactivate: () => void
  onDelete: () => void
  isActivating: boolean
}

export default function RouteTableRow({
  route, index, onSelect, onEdit, onCurl, onActivate, onDeactivate, onDelete, isActivating,
}: Props) {
  const sc = STATUS_CONFIG[route.status]
  const [confirmDelete, setConfirmDelete] = useState(false)

  return (
    <tr
      className="group border-b border-white/[0.04] hover:bg-white/[0.025] cursor-pointer transition-colors"
      style={{ animationDelay: `${index * 30}ms` }}
      onClick={onSelect}
    >
      {/* Name */}
      <td className="px-6 py-3.5">
        <div className="flex items-center gap-3">
          <div className="relative shrink-0">
            <div className={cn('w-2 h-2 rounded-full', sc.dot)} />
            {route.status === 'ACTIVE' && (
              <div className="absolute inset-0 w-2 h-2 rounded-full bg-emerald-400 animate-ping opacity-50" />
            )}
          </div>
          <div>
            <div className="font-semibold text-white text-sm">{route.name}</div>
            {route.description && (
              <div className="text-xs text-gray-600 mt-0.5 truncate max-w-[180px]">{route.description}</div>
            )}
          </div>
        </div>
      </td>

      {/* Path */}
      <td className="px-4 py-3.5">
        <code className="text-xs bg-white/[0.05] border border-white/[0.07] px-2 py-1 rounded-md text-indigo-300 font-mono">
          {route.pathPattern}
        </code>
      </td>

      {/* Methods */}
      <td className="px-4 py-3.5">
        <div className="flex gap-1 flex-wrap">
          {route.methods.split(',').map(m => (
            <span key={m} className={cn('text-[10px] px-1.5 py-0.5 rounded font-mono font-bold border', METHOD_COLORS[m.trim()] ?? METHOD_COLORS['*'])}>
              {m.trim()}
            </span>
          ))}
        </div>
      </td>

      {/* Upstream */}
      <td className="px-4 py-3.5 max-w-[200px]">
        <span className="text-xs text-gray-400 font-mono truncate block" title={route.upstreamUri}>
          {route.upstreamUri}
        </span>
      </td>

      {/* Filters */}
      <td className="px-4 py-3.5">
        <span className="inline-flex items-center gap-1 text-xs text-gray-500 bg-white/[0.03] border border-white/[0.06] px-2 py-0.5 rounded-full">
          <Filter className="w-2.5 h-2.5" />
          {route.filterCount}
        </span>
      </td>

      {/* Status */}
      <td className="px-4 py-3.5">
        <span className={cn('inline-flex items-center gap-1.5 text-xs px-2 py-1 rounded-full font-semibold border', sc.color)}>
          {sc.icon}
          {sc.label}
        </span>
      </td>

      {/* Version */}
      <td className="px-4 py-3.5">
        <span className="text-xs text-gray-600 font-mono">v{route.version}</span>
      </td>

      {/* Actions */}
      <td className="px-4 py-3.5">
        <div
          className="flex items-center justify-end gap-0.5 opacity-0 group-hover:opacity-100 transition-opacity relative"
          onClick={e => e.stopPropagation()}
        >
          {(route.status === 'DRAFT' || route.status === 'DISABLED') && (
            <button
              onClick={onActivate}
              disabled={isActivating}
              title="Activate"
              className="p-1.5 rounded-md text-emerald-400 hover:bg-emerald-400/10 transition-colors disabled:opacity-50"
            >
              {isActivating
                ? <RefreshCw className="w-3.5 h-3.5 animate-spin" />
                : <Play className="w-3.5 h-3.5" />}
            </button>
          )}

          {route.status === 'ACTIVE' && (
            <button
              onClick={onDeactivate}
              title="Deactivate"
              className="p-1.5 rounded-md text-amber-400 hover:bg-amber-400/10 transition-colors"
            >
              <Pause className="w-3.5 h-3.5" />
            </button>
          )}

          <button
            onClick={onEdit}
            title="Edit"
            className="p-1.5 rounded-md text-indigo-400 hover:bg-indigo-400/10 transition-colors"
          >
            <Edit className="w-3.5 h-3.5" />
          </button>

          {route.status !== 'ACTIVE' && (
            <div className="relative">
              <button
                onClick={() => setConfirmDelete(true)}
                title="Delete"
                className="p-1.5 rounded-md text-red-400 hover:bg-red-400/10 transition-colors"
              >
                <Trash2 className="w-3.5 h-3.5" />
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

          <button
            onClick={onCurl}
            title="Generate cURL"
            className="p-1.5 rounded-md text-violet-400 hover:bg-violet-400/10 transition-colors"
          >
            <Terminal className="w-3.5 h-3.5" />
          </button>

          <ChevronRight className="w-3.5 h-3.5 text-gray-700 ml-0.5" />
        </div>
      </td>
    </tr>
  )
}

