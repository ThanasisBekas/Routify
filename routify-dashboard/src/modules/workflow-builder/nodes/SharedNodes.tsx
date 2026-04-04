/**
 * SharedNodes.tsx — Remaining custom node components for the WorkflowBuilder.
 *
 * Includes: ClientNode, UpstreamNode, FilterNode, ResponseNode, LabelNode.
 * All follow the same glass-dark design language as RouteTriggerNode.
 */
import { Handle, Position, type NodeProps } from '@xyflow/react'
import { Globe, Server, Shield, Gauge, RefreshCw, Code2, GitBranch, CheckCircle, AlertCircle, Trash2 } from 'lucide-react'
import { cn } from '../../../lib/utils'
import { getFilterMeta } from '../constants/nodeMetadata'
import type { FilterNodeData, UpstreamNodeData, ResponseNodeData, LabelNodeData } from '../hooks/buildGraph'

// ─── Client Node ─────────────────────────────────────────────────────────────

// eslint-disable-next-line @typescript-eslint/no-unused-vars
export function ClientNode(_props: NodeProps) {
  return (
    <div className="flex flex-col items-center gap-2 px-4 py-3 bg-[#111318] border border-white/10 rounded-xl shadow-xl min-w-[100px]">
      <div className="w-10 h-10 rounded-full bg-indigo-500/15 border border-indigo-400/25 flex items-center justify-center">
        <Globe className="w-5 h-5 text-indigo-400" />
      </div>
      <div className="text-center">
        <div className="text-[11px] font-bold text-white tracking-wide">CLIENT</div>
        <div className="text-[10px] text-gray-500 mt-0.5">HTTP Request</div>
      </div>
      <Handle
        type="source"
        position={Position.Right}
        className="!w-2.5 !h-2.5 !bg-indigo-400 !border-0 !rounded-full"
      />
    </div>
  )
}

// ─── Upstream Node ────────────────────────────────────────────────────────────

export function UpstreamNode({ data, selected }: NodeProps) {
  const d = data as UpstreamNodeData
  const isLB = d.uri.startsWith('lb://')

  return (
    <div
      className={cn(
        'px-4 py-3.5 bg-[#111318] rounded-xl shadow-xl min-w-[200px] transition-all duration-150 cursor-pointer',
        'border',
        selected
          ? 'border-emerald-400 shadow-emerald-500/20 shadow-lg'
          : 'border-emerald-500/30 hover:border-emerald-400/60 shadow-emerald-500/5',
      )}
      onClick={() => d.onSelect?.()}
    >
      <Handle type="target" position={Position.Left}  className="!w-2.5 !h-2.5 !bg-emerald-400 !border-0 !rounded-full" />
      <Handle type="source" position={Position.Right} className="!w-2.5 !h-2.5 !bg-emerald-400 !border-0 !rounded-full" />

      <div className="flex items-start gap-3">
        <div className="mt-0.5 p-2 rounded-lg bg-emerald-400/10 border border-emerald-400/20 shrink-0">
          <Server className="w-5 h-5 text-emerald-400" />
        </div>
        <div className="flex-1 min-w-0">
          <div className="text-[10px] font-bold text-emerald-400 uppercase tracking-widest mb-1">Upstream</div>
          <code className="text-[11px] text-gray-300 font-mono break-all leading-relaxed">{d.uri}</code>
          {isLB && (
            <div className="mt-1.5 text-[9px] text-emerald-400/70 bg-emerald-400/5 border border-emerald-400/15 rounded px-1.5 py-0.5 inline-block">
              Load Balanced
            </div>
          )}
          {d.stripPrefix && (
            <div className="mt-1 text-[10px] text-gray-500">
              Strip: <code className="text-gray-400 font-mono">{d.stripPrefix}</code>
            </div>
          )}
        </div>
      </div>
    </div>
  )
}

// ─── Filter Node ──────────────────────────────────────────────────────────────

export function FilterNode({ data, selected }: NodeProps) {
  const d = data as FilterNodeData
  const meta = getFilterMeta(d.filter.filterType)

  return (
    <div
      className={cn(
        'px-3 py-2.5 border rounded-xl shadow-lg min-w-[170px] bg-[#111318] group cursor-pointer transition-all duration-150',
        selected ? `${meta.border} shadow-lg` : meta.border,
        'hover:brightness-110',
      )}
      onClick={() => d.onSelect?.()}
    >
      <Handle type="target" position={Position.Left}  className="!w-2.5 !h-2.5 !bg-gray-500 !border-0 !rounded-full" />
      <Handle type="source" position={Position.Right} className="!w-2.5 !h-2.5 !bg-gray-500 !border-0 !rounded-full" />

      <div className="flex items-start gap-2">
        {/* Type icon */}
        <div className={cn('mt-0.5 shrink-0 p-1.5 rounded-lg', meta.bg)}>
          <span className={meta.color}>{meta.icon}</span>
        </div>

        <div className="flex-1 min-w-0">
          <div className={cn('text-[9px] font-bold uppercase tracking-widest mb-0.5', meta.color)}>
            {d.filter.filterType.replace(/_/g, ' ')}
          </div>
          <div className="text-[11px] font-semibold text-white truncate">{d.filter.filterName}</div>
          <div className="flex items-center gap-1.5 mt-1">
            <span className={cn(
              'text-[9px] px-1.5 py-0.5 rounded-full font-bold',
              d.phase === 'PRE' ? 'bg-blue-500/20 text-blue-300' : 'bg-purple-500/20 text-purple-300',
            )}>
              {d.phase}
            </span>
            <span className="text-[9px] text-gray-600 font-mono">#{d.filter.order}</span>
          </div>
        </div>

        {/* Detach button — shown on hover */}
        <button
          onClick={(e) => { e.stopPropagation(); d.onDetach(d.filter.filterId) }}
          className="opacity-0 group-hover:opacity-100 p-1 rounded text-red-400 hover:bg-red-400/10 transition-all shrink-0 mt-0.5"
          title="Detach filter"
        >
          <Trash2 className="w-3 h-3" />
        </button>
      </div>
    </div>
  )
}

// ─── Response Node ────────────────────────────────────────────────────────────

export function ResponseNode({ data }: NodeProps) {
  const d = data as ResponseNodeData
  const isActive = d.status === 'ACTIVE'

  return (
    <div className={cn(
      'flex flex-col items-center gap-2 px-4 py-3 bg-[#111318] border rounded-xl shadow-xl min-w-[100px]',
      isActive ? 'border-emerald-500/25' : 'border-white/10',
    )}>
      <Handle type="target" position={Position.Left} className="!w-2.5 !h-2.5 !bg-gray-400 !border-0 !rounded-full" />

      <div className={cn(
        'w-10 h-10 rounded-full flex items-center justify-center',
        isActive ? 'bg-emerald-500/15 border border-emerald-400/25' : 'bg-gray-500/15 border border-gray-500/25',
      )}>
        {isActive
          ? <CheckCircle className="w-5 h-5 text-emerald-400" />
          : <AlertCircle className="w-5 h-5 text-gray-500" />
        }
      </div>

      <div className="text-center">
        <div className={cn('text-[11px] font-bold tracking-wide', isActive ? 'text-emerald-400' : 'text-gray-500')}>
          RESPONSE
        </div>
        <div className="text-[10px] text-gray-600 mt-0.5">{isActive ? 'Live' : 'Inactive'}</div>
      </div>
    </div>
  )
}

// ─── Label Node (non-interactive column header) ───────────────────────────────

export function LabelNode({ data }: NodeProps) {
  const d = data as LabelNodeData
  return (
    <div className="flex flex-col items-center gap-1 pointer-events-none select-none">
      <div className={cn('text-[10px] font-bold uppercase tracking-widest px-2.5 py-1 rounded-full border', d.color)}>
        {d.label}
      </div>
      {d.sub && <div className="text-[9px] text-gray-600">{d.sub}</div>}
    </div>
  )
}

// ─── Placeholder nodes for palette-dropped nodes (not yet configured) ─────────

function PlaceholderNode({
  label,
  icon,
  accentColor,
  accentBg,
  accentBorder,
  hint,
}: {
  label: string
  icon: React.ReactNode
  accentColor: string
  accentBg: string
  accentBorder: string
  hint?: string
}) {
  return (
    <div className={cn('px-3 py-2.5 border border-dashed rounded-xl bg-[#111318] min-w-[160px]', accentBorder)}>
      <Handle type="target" position={Position.Left}  className="!w-2.5 !h-2.5 !bg-gray-500 !border-0 !rounded-full" />
      <Handle type="source" position={Position.Right} className="!w-2.5 !h-2.5 !bg-gray-500 !border-0 !rounded-full" />
      <div className="flex items-center gap-2">
        <div className={cn('p-1.5 rounded-lg', accentBg)}>
          <span className={accentColor}>{icon}</span>
        </div>
        <div>
          <div className={cn('text-[10px] font-bold uppercase tracking-widest', accentColor)}>{label}</div>
          {hint && <div className="text-[9px] text-gray-600 mt-0.5">{hint}</div>}
        </div>
      </div>
    </div>
  )
}

export function AuthNode(props: NodeProps) {
  return <PlaceholderNode label="Auth Filter" icon={<Shield className="w-4 h-4" />} accentColor="text-blue-400" accentBg="bg-blue-400/10" accentBorder="border-blue-400/25" hint="Click to configure" {...props as unknown as Record<string, never>} />
}

export function RateLimitNode(props: NodeProps) {
  return <PlaceholderNode label="Rate Limit" icon={<Gauge className="w-4 h-4" />} accentColor="text-orange-400" accentBg="bg-orange-400/10" accentBorder="border-orange-400/25" hint="Click to configure" {...props as unknown as Record<string, never>} />
}

export function ResilienceNode(props: NodeProps) {
  return <PlaceholderNode label="Resilience" icon={<RefreshCw className="w-4 h-4" />} accentColor="text-amber-400" accentBg="bg-amber-400/10" accentBorder="border-amber-400/25" hint="Click to configure" {...props as unknown as Record<string, never>} />
}

export function TransformNode(props: NodeProps) {
  return <PlaceholderNode label="Transform" icon={<Code2 className="w-4 h-4" />} accentColor="text-purple-400" accentBg="bg-purple-400/10" accentBorder="border-purple-400/25" hint="Click to configure" {...props as unknown as Record<string, never>} />
}

export function ConditionalNode(props: NodeProps) {
  return <PlaceholderNode label="Conditional" icon={<GitBranch className="w-4 h-4" />} accentColor="text-indigo-400" accentBg="bg-indigo-400/10" accentBorder="border-indigo-400/25" hint="Click to configure" {...props as unknown as Record<string, never>} />
}

