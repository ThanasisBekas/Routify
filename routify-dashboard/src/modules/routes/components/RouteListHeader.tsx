import { Plus, RefreshCw, Wifi } from 'lucide-react'
import { cn } from '../../../lib/utils'
import type { RouteSummary, RouteStatus } from '../../../types'
import { STATUS_CONFIG } from '../constants/routeStatusConfig'
import { STATUS_FILTER_TABS, type StatusFilterTab } from '../routeConstants'

interface Props {
  total: number
  routes: RouteSummary[]
  statusFilter: StatusFilterTab
  isFetching: boolean
  isLive: boolean
  onStatusFilter: (s: StatusFilterTab) => void
  onRefresh: () => void
  onNew: () => void
}

export default function RouteListHeader({
  total, routes, statusFilter, isFetching, isLive,
  onStatusFilter, onRefresh, onNew,
}: Props) {
  const activeCount = routes.filter(r => r.status === 'ACTIVE').length

  return (
    <div className="px-6 py-5 border-b border-white/[0.06] bg-[#0c0e14] shrink-0">
      {/* Title row */}
      <div className="flex items-start justify-between">
        <div>
          <div className="flex items-center gap-2.5 mb-1">
            <h1 className="text-lg font-bold text-white tracking-tight">Routes</h1>
            {isLive && (
              <span className="flex items-center gap-1 text-[10px] font-semibold text-emerald-400 bg-emerald-400/10 border border-emerald-400/20 px-2 py-0.5 rounded-full">
                <Wifi className="w-2.5 h-2.5" />
                Live
              </span>
            )}
          </div>
          <p className="text-sm text-gray-500">
            {total} route{total !== 1 ? 's' : ''} · {activeCount} active
          </p>
        </div>

        <div className="flex items-center gap-2">
          <button
            onClick={onRefresh}
            className="p-2 rounded-lg text-gray-500 hover:text-gray-300 hover:bg-white/[0.05] transition-all border border-white/[0.06]"
            title="Refresh"
          >
            <RefreshCw className={cn('w-3.5 h-3.5', isFetching && 'animate-spin')} />
          </button>

          <button
            onClick={onNew}
            className="flex items-center gap-2 px-3.5 py-2 bg-indigo-600 hover:bg-indigo-500 text-white text-sm font-semibold rounded-lg transition-all shadow-lg shadow-indigo-500/20 hover:shadow-indigo-500/30"
          >
            <Plus className="w-4 h-4" />
            New Route
          </button>
        </div>
      </div>

      {/* Status filter tabs */}
      <div className="flex items-center gap-6 mt-4">
        {STATUS_FILTER_TABS.map(s => {
          const cfg   = s !== '' ? STATUS_CONFIG[s as RouteStatus] : null
          const count = s === '' ? total : routes.filter(r => r.status === s).length
          return (
            <button
              key={s}
              onClick={() => onStatusFilter(s)}
              className={cn(
                'flex items-center gap-2 text-sm transition-all pb-1 border-b-2',
                statusFilter === s
                  ? 'text-white border-indigo-500'
                  : 'text-gray-500 border-transparent hover:text-gray-300 hover:border-white/20',
              )}
            >
              {cfg && <span className={cn('w-1.5 h-1.5 rounded-full', cfg.dot)} />}
              <span className="font-medium">{s === '' ? 'All' : cfg!.label}</span>
              <span className={cn(
                'text-xs px-1.5 py-0.5 rounded-full',
                statusFilter === s ? 'bg-indigo-500/20 text-indigo-300' : 'bg-white/[0.05] text-gray-500',
              )}>
                {count}
              </span>
            </button>
          )
        })}
      </div>
    </div>
  )
}
