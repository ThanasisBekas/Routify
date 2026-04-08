/**
 * TraceLink — "View Trace" button that opens Grafana Tempo with the trace query.
 *
 * Uses the VITE_GRAFANA_URL env var (default: http://localhost:3001) to construct
 * the Tempo explore URL for the given correlation ID.
 */
import { ExternalLink } from 'lucide-react'
import { cn } from '../../../lib/utils'

const GRAFANA_BASE_URL = import.meta.env.VITE_GRAFANA_URL || 'http://localhost:3001'

interface TraceLinkProps {
  correlationId?: string | null
  className?: string
  compact?: boolean
}

export default function TraceLink({ correlationId, className, compact = false }: TraceLinkProps) {
  if (!correlationId) return null

  const url = `${GRAFANA_BASE_URL}/explore?left=${encodeURIComponent(
    JSON.stringify({
      datasource: 'Tempo',
      queries: [{ queryType: 'traceql', query: correlationId }],
    }),
  )}`

  if (compact) {
    return (
      <a
        href={url}
        target="_blank"
        rel="noopener noreferrer"
        title="View trace in Grafana Tempo"
        className={cn(
          'inline-flex items-center gap-1 text-[10px] text-indigo-400 hover:text-indigo-300 transition-colors',
          className,
        )}
      >
        <ExternalLink className="w-3 h-3" />
        Trace
      </a>
    )
  }

  return (
    <a
      href={url}
      target="_blank"
      rel="noopener noreferrer"
      className={cn(
        'inline-flex items-center gap-1.5 text-xs text-indigo-400 hover:text-indigo-300 bg-indigo-500/10 border border-indigo-500/20 px-2.5 py-1 rounded-full transition-colors',
        className,
      )}
    >
      <ExternalLink className="w-3 h-3" />
      View Trace
    </a>
  )
}
