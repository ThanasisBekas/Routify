/**
 * RouteTriggerNode.tsx — The primary "Route Trigger" custom node.
 *
 * Design intent:
 *  - Indigo accent ring conveys "this is the gateway entry point"
 *  - Inline HTTP method badges use colour-coded pills (GET=blue, POST=green, …)
 *  - Pulsing status dot communicates live state at a glance
 *  - onSelect callback wires into PropertiesDrawer via node data
 */
import { Handle, Position, type NodeProps } from '@xyflow/react'
import { Globe, CheckCircle, Clock, Pause, Archive } from 'lucide-react'
import { cn } from '../../../lib/utils'
import type { RouteNodeData } from '../hooks/buildGraph'

const STATUS_ICON = {
  ACTIVE:   <CheckCircle className="w-3.5 h-3.5 text-emerald-400" />,
  DRAFT:    <Clock className="w-3.5 h-3.5 text-amber-400" />,
  DISABLED: <Pause className="w-3.5 h-3.5 text-gray-400" />,
  ARCHIVED: <Archive className="w-3.5 h-3.5 text-red-400" />,
} as const

const STATUS_COLOR = {
  ACTIVE: 'text-emerald-400',
  DRAFT: 'text-amber-400',
  DISABLED: 'text-gray-400',
  ARCHIVED: 'text-red-400',
} as const

const METHOD_BADGE: Record<string, string> = {
  GET:    'bg-blue-500/15 text-blue-300 border-blue-500/25',
  POST:   'bg-green-500/15 text-green-300 border-green-500/25',
  PUT:    'bg-amber-500/15 text-amber-300 border-amber-500/25',
  DELETE: 'bg-red-500/15 text-red-300 border-red-500/25',
  PATCH:  'bg-purple-500/15 text-purple-300 border-purple-500/25',
}

export function RouteTriggerNode({ data, selected }: NodeProps) {
  const d = data as RouteNodeData
  const status = d.status as keyof typeof STATUS_ICON
  const methods = d.methods.split(',').map((m: string) => m.trim())

  return (
    /*
     * The outer wrapper carries the selection ring via `selected` prop.
     * We use `relative overflow-hidden` so the subtle gradient wash
     * stays clipped to the card boundary.
     */
    <div
      className={cn(
        'group relative px-4 py-3.5 bg-[#111318] rounded-xl shadow-2xl min-w-[230px] transition-all duration-150',
        'border-2',
        selected
          ? 'border-indigo-400 shadow-indigo-500/20 shadow-lg'
          : 'border-indigo-500/40 hover:border-indigo-400/70 shadow-indigo-500/10',
      )}
      onClick={() => d.onSelect?.()}
    >
      {/* Subtle radial glow wash — gives the card depth without being garish */}
      <div className="absolute inset-0 bg-indigo-500/[0.04] rounded-xl pointer-events-none" />

      {/* Left handle — receives connections from Client / PRE filters */}
      <Handle
        type="target"
        position={Position.Left}
        className="!w-3 !h-3 !bg-indigo-400 !border-0 !rounded-full transition-transform hover:scale-125"
      />
      {/* Right handle — sends to Upstream */}
      <Handle
        type="source"
        position={Position.Right}
        className="!w-3 !h-3 !bg-indigo-400 !border-0 !rounded-full transition-transform hover:scale-125"
      />

      <div className="relative space-y-2">
        {/* ── Header row ─────────────────────────────────────────────────── */}
        <div className="flex items-start justify-between gap-2">
          <div className="flex items-center gap-2">
            {/* Node type icon */}
            <div className="p-1.5 rounded-lg bg-indigo-500/15 border border-indigo-400/20 shrink-0">
              <Globe className="w-4 h-4 text-indigo-400" />
            </div>
            <div>
              <div className="text-[10px] font-bold text-indigo-400 uppercase tracking-widest leading-none mb-0.5">
                Route Trigger
              </div>
              <div className="text-sm font-bold text-white truncate max-w-[140px]">{d.name}</div>
            </div>
          </div>

          {/* Status badge */}
          <div className={cn('flex items-center gap-1 shrink-0 mt-0.5', STATUS_COLOR[status] ?? 'text-gray-400')}>
            {STATUS_ICON[status]}
            <span className="text-[10px] font-semibold">{d.status}</span>
          </div>
        </div>

        {/* ── Path pattern ─────────────────────────────────────────────────── */}
        <code className="block text-[11px] bg-white/5 border border-white/[0.07] px-2 py-1.5 rounded-lg text-indigo-300 font-mono truncate">
          {d.pathPattern}
        </code>

        {/* ── HTTP method badges ──────────────────────────────────────────── */}
        <div className="flex gap-1 flex-wrap">
          {methods.map((m: string) => (
            <span
              key={m}
              className={cn(
                'text-[9px] px-1.5 py-0.5 rounded font-mono font-bold border',
                METHOD_BADGE[m] ?? 'bg-gray-500/15 text-gray-300 border-gray-500/25',
              )}
            >
              {m}
            </span>
          ))}
        </div>

        {/* ── Version watermark ───────────────────────────────────────────── */}
        <div className="flex items-center justify-between">
          <span className="text-[10px] text-gray-600 font-mono">v{d.version}</span>
          {/* Click hint — visible on group-hover */}
          <span className="text-[9px] text-gray-700 group-hover:text-gray-500 transition-colors">
            Click to configure →
          </span>
        </div>
      </div>
    </div>
  )
}

