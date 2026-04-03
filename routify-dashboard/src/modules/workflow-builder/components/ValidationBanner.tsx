/**
 * ValidationBanner.tsx — Compact inline banner indicating flow completeness.
 *
 * Rendered in the React Flow Panel (top-right corner of the canvas).
 * Green = valid path from Client → Response; Amber = broken or missing connections.
 */
import { CheckCircle, AlertCircle } from 'lucide-react'

export default function ValidationBanner({ valid }: { valid: boolean }) {
  if (valid) {
    return (
      <div className="flex items-center gap-1.5 px-3 py-1.5 rounded-lg bg-emerald-500/10 border border-emerald-500/20 text-xs text-emerald-400">
        <CheckCircle className="w-3.5 h-3.5 shrink-0" />
        Flow complete — ready to save
      </div>
    )
  }
  return (
    <div className="flex items-center gap-1.5 px-3 py-1.5 rounded-lg bg-amber-500/10 border border-amber-500/20 text-xs text-amber-400">
      <AlertCircle className="w-3.5 h-3.5 shrink-0" />
      Connect all nodes: Client → … → Response
    </div>
  )
}

