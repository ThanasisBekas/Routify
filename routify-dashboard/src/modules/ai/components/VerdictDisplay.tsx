import { cn } from '../../../lib/utils'
import { CheckCircle, XCircle, AlertTriangle } from 'lucide-react'

interface Verdict {
  action: string
  reason: string
  confidence: number
  latencyMs?: number
  cached?: boolean
}

interface Props {
  verdict: Verdict | null
  isLoading: boolean
}

const VERDICT_CONFIG: Record<string, { icon: typeof CheckCircle; color: string; bg: string; border: string }> = {
  ALLOW: { icon: CheckCircle, color: 'text-emerald-400', bg: 'bg-emerald-500/10', border: 'border-emerald-500/20' },
  BLOCK: { icon: XCircle, color: 'text-red-400', bg: 'bg-red-500/10', border: 'border-red-500/20' },
  FLAG: { icon: AlertTriangle, color: 'text-amber-400', bg: 'bg-amber-500/10', border: 'border-amber-500/20' },
}

export default function VerdictDisplay({ verdict, isLoading }: Props) {
  if (isLoading) {
    return (
      <div className="flex items-center justify-center py-8 gap-2 text-gray-500 text-sm">
        <span className="w-4 h-4 border-2 border-indigo-500/30 border-t-indigo-500 rounded-full animate-spin" />
        Evaluating prompt…
      </div>
    )
  }

  if (!verdict) {
    return <div className="py-8 text-center text-gray-600 text-sm">Run a test to see the AI verdict</div>
  }

  const config = VERDICT_CONFIG[verdict.action] ?? VERDICT_CONFIG.ALLOW
  const Icon = config.icon

  return (
    <div className="space-y-3">
      <label className="text-xs font-semibold text-gray-400 uppercase tracking-wider">Verdict</label>

      <div className={cn('rounded-lg border p-4', config.bg, config.border)}>
        <div className="flex items-center gap-2 mb-2">
          <Icon className={cn('w-5 h-5', config.color)} />
          <span className={cn('text-lg font-bold', config.color)}>{verdict.action}</span>
          <span className="text-xs text-gray-500 ml-auto">confidence: {(verdict.confidence * 100).toFixed(0)}%</span>
        </div>
        <p className="text-sm text-gray-300 leading-relaxed">{verdict.reason}</p>
        <div className="flex gap-4 mt-3 text-[11px] text-gray-500">
          {verdict.latencyMs != null && <span>Latency: {verdict.latencyMs}ms</span>}
          {verdict.cached != null && <span>Cached: {verdict.cached ? 'yes' : 'no'}</span>}
        </div>
      </div>
    </div>
  )
}
