import type { ReconciliationResult, ReconciliationOutcome } from '../../types'
import { cn } from '../../lib/utils'
import { CheckCircle, XCircle, AlertTriangle, Minus, GitCommit } from 'lucide-react'

interface Props {
  history: ReconciliationResult[]
}

function outcomeBadge(outcome: ReconciliationOutcome) {
  switch (outcome) {
    case 'APPLIED':
      return (
        <span className="inline-flex items-center gap-1.5 px-2 py-0.5 rounded-full text-[11px] font-medium bg-emerald-500/10 text-emerald-400 border border-emerald-500/20">
          <CheckCircle className="w-3 h-3" />
          Applied
        </span>
      )
    case 'DRIFT_DETECTED':
      return (
        <span className="inline-flex items-center gap-1.5 px-2 py-0.5 rounded-full text-[11px] font-medium bg-amber-500/10 text-amber-400 border border-amber-500/20">
          <AlertTriangle className="w-3 h-3" />
          Drift Detected
        </span>
      )
    case 'FAILED':
      return (
        <span className="inline-flex items-center gap-1.5 px-2 py-0.5 rounded-full text-[11px] font-medium bg-red-500/10 text-red-400 border border-red-500/20">
          <XCircle className="w-3 h-3" />
          Failed
        </span>
      )
    case 'NO_CHANGE':
      return (
        <span className="inline-flex items-center gap-1.5 px-2 py-0.5 rounded-full text-[11px] font-medium bg-gray-500/10 text-gray-400 border border-gray-500/20">
          <Minus className="w-3 h-3" />
          No Change
        </span>
      )
  }
}

export default function ReconciliationHistoryTable({ history }: Props) {
  if (history.length === 0) {
    return (
      <div className="text-center py-12 text-gray-500 text-sm">
        No reconciliation history yet. The agent will sync on the next poll cycle.
      </div>
    )
  }

  return (
    <div className="overflow-x-auto">
      <table className="w-full text-sm">
        <thead>
          <tr className="border-b border-white/[0.06] text-gray-500 text-xs uppercase tracking-wider">
            <th className="text-left py-3 px-4 font-medium">Time</th>
            <th className="text-left py-3 px-4 font-medium">Outcome</th>
            <th className="text-left py-3 px-4 font-medium">Commit</th>
            <th className="text-left py-3 px-4 font-medium">Changes</th>
            <th className="text-left py-3 px-4 font-medium">Details</th>
          </tr>
        </thead>
        <tbody>
          {history.map((entry, idx) => (
            <tr
              key={idx}
              className={cn(
                'border-b border-white/[0.04] hover:bg-white/[0.02] transition-colors',
                entry.outcome === 'FAILED' && 'bg-red-500/[0.02]',
              )}
            >
              <td className="py-3 px-4 text-gray-300 text-xs whitespace-nowrap">
                {new Date(entry.timestamp).toLocaleString()}
              </td>
              <td className="py-3 px-4">{outcomeBadge(entry.outcome)}</td>
              <td className="py-3 px-4">
                {entry.commitHash ? (
                  <span className="inline-flex items-center gap-1.5 text-xs font-mono text-indigo-400">
                    <GitCommit className="w-3 h-3" />
                    {entry.commitHash.substring(0, 8)}
                  </span>
                ) : (
                  <span className="text-gray-600 text-xs">—</span>
                )}
              </td>
              <td className="py-3 px-4 text-xs text-gray-400">
                {entry.outcome === 'NO_CHANGE' ? (
                  '—'
                ) : (
                  <span>
                    {entry.routesCreated > 0 && <span className="text-emerald-400">+{entry.routesCreated}R </span>}
                    {entry.routesUpdated > 0 && <span className="text-amber-400">~{entry.routesUpdated}R </span>}
                    {entry.filtersCreated > 0 && <span className="text-emerald-400">+{entry.filtersCreated}F </span>}
                    {entry.filtersUpdated > 0 && <span className="text-amber-400">~{entry.filtersUpdated}F </span>}
                    {entry.routesCreated + entry.routesUpdated + entry.filtersCreated + entry.filtersUpdated === 0 &&
                      '—'}
                  </span>
                )}
              </td>
              <td className="py-3 px-4 text-xs">
                {entry.errorMessage ? (
                  <span className="text-red-400">{entry.errorMessage}</span>
                ) : entry.warnings && entry.warnings.length > 0 ? (
                  <span className="text-amber-400">{entry.warnings.join(', ')}</span>
                ) : (
                  <span className="text-gray-600">—</span>
                )}
              </td>
            </tr>
          ))}
        </tbody>
      </table>
    </div>
  )
}
