/**
 * ValidationBanner.tsx — Compact inline banner indicating flow completeness
 * and filter latency warnings.
 *
 * Rendered in the React Flow Panel (top-right corner of the canvas).
 * Green = valid path from Client → Response; Amber = broken or missing connections.
 * A separate warning is shown when filterCount > 3.
 */
import { CheckCircle, AlertCircle, AlertTriangle } from 'lucide-react'
import { useWorkflowStore } from '../store/workflowStore'

/** Threshold above which the latency warning is triggered */
export const FILTER_LATENCY_THRESHOLD = 3

interface ValidationBannerProps {
  valid: boolean
}

export default function ValidationBanner({ valid }: ValidationBannerProps) {
  const { filterCount } = useWorkflowStore()
  const showLatencyWarning = filterCount > FILTER_LATENCY_THRESHOLD

  return (
    <div className="flex flex-col items-end gap-1.5">
      {/* ── Flow completeness ─────────────────────────────── */}
      {valid ? (
        <div className="flex items-center gap-1.5 px-3 py-1.5 rounded-lg bg-emerald-500/10 border border-emerald-500/20 text-xs text-emerald-400">
          <CheckCircle className="w-3.5 h-3.5 shrink-0" />
          Flow complete — ready to save
        </div>
      ) : (
        <div className="flex items-center gap-1.5 px-3 py-1.5 rounded-lg bg-amber-500/10 border border-amber-500/20 text-xs text-amber-400">
          <AlertCircle className="w-3.5 h-3.5 shrink-0" />
          Connect all nodes: Client → … → Response
        </div>
      )}

      {/* ── Filter latency warning ────────────────────────── */}
      {showLatencyWarning && (
        <div className="flex items-center gap-1.5 px-3 py-1.5 rounded-lg bg-orange-500/10 border border-orange-500/20 text-xs text-orange-400 max-w-xs">
          <AlertTriangle className="w-3.5 h-3.5 shrink-0" />
          Using more than {FILTER_LATENCY_THRESHOLD} filters may introduce high latency
          <span className="ml-1 font-bold text-orange-300">({filterCount} active)</span>
        </div>
      )}
    </div>
  )
}

