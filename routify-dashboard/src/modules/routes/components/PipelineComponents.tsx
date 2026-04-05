import { ArrowRight, Filter } from 'lucide-react'
import { cn } from '../../../lib/utils'

// ─── PipelineNode ──────────────────────────────────────────────────────────────

interface PipelineNodeProps {
  icon: React.ReactNode
  label: string
  color: string
  bg: string
}

export function PipelineNode({ icon, label, color, bg }: PipelineNodeProps) {
  return (
    <div className={cn('flex flex-col items-center gap-1 px-2.5 py-2 rounded-xl border shrink-0', bg)}>
      <span className={color}>{icon}</span>
      <span className={cn('text-[9px] font-semibold', color)}>{label}</span>
    </div>
  )
}

// ─── PipelineArrow ─────────────────────────────────────────────────────────────

interface PipelineArrowProps {
  label?: string
}

export function PipelineArrow({ label }: PipelineArrowProps) {
  return (
    <div className="flex flex-col items-center gap-0.5 shrink-0">
      {label && <span className="text-[8px] text-gray-600">{label}</span>}
      <ArrowRight className="w-3.5 h-3.5 text-gray-700" />
    </div>
  )
}

// ─── FilterPill ────────────────────────────────────────────────────────────────

interface FilterPillProps {
  count: number
  phase: 'PRE' | 'POST'
}

export function FilterPill({ count, phase }: FilterPillProps) {
  const isPre = phase === 'PRE'
  return (
    <div
      className={cn(
        'flex items-center gap-1 px-2 py-1 rounded-lg border text-[9px] font-semibold',
        isPre
          ? 'bg-blue-500/10 border-blue-500/20 text-blue-400'
          : 'bg-purple-500/10 border-purple-500/20 text-purple-400',
      )}
    >
      <Filter className="w-2.5 h-2.5" />
      {count}
    </div>
  )
}
