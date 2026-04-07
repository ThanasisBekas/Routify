import { useQuery } from '@tanstack/react-query'
import { alertsApi } from '../../api/alertsApi'
import { cn } from '../../lib/utils'

const TRANSITION_COLORS: Record<string, string> = {
  OK_TO_PENDING: 'border-amber-500 bg-amber-500/10',
  PENDING_TO_FIRING: 'border-red-500 bg-red-500/10',
  FIRING_TO_OK: 'border-green-500 bg-green-500/10',
}

const TRANSITION_LABELS: Record<string, string> = {
  OK_TO_PENDING: 'Threshold Breached',
  PENDING_TO_FIRING: 'Alert Fired',
  FIRING_TO_OK: 'Resolved',
}

interface Props {
  ruleId: string
}

export default function AlertHistoryTimeline({ ruleId }: Props) {
  const { data, isLoading } = useQuery({
    queryKey: ['alert-history', ruleId],
    queryFn: () => alertsApi.history(ruleId, { page: 0, size: 20 }),
  })

  const events = data?.content ?? []

  if (isLoading) {
    return (
      <div className="flex items-center justify-center py-6">
        <div className="w-4 h-4 border-2 border-indigo-500/30 border-t-indigo-500 rounded-full animate-spin" />
      </div>
    )
  }

  if (events.length === 0) {
    return <div className="text-center py-4 text-xs text-gray-600">No state transitions yet.</div>
  }

  return (
    <div className="space-y-3">
      <h3 className="text-xs font-medium text-gray-400 uppercase tracking-wider">State Transition History</h3>
      <div className="relative">
        {/* Timeline line */}
        <div className="absolute left-3 top-3 bottom-3 w-px bg-white/[0.06]" />

        <div className="space-y-3">
          {events.map((event) => (
            <div key={event.id} className="flex items-start gap-3 relative">
              {/* Timeline dot */}
              <div
                className={cn(
                  'w-6 h-6 rounded-full border-2 flex items-center justify-center shrink-0 z-10',
                  TRANSITION_COLORS[event.transition] ?? 'border-gray-600 bg-gray-600/10',
                )}
              >
                <div
                  className={cn(
                    'w-2 h-2 rounded-full',
                    event.transition === 'PENDING_TO_FIRING'
                      ? 'bg-red-500'
                      : event.transition === 'FIRING_TO_OK'
                        ? 'bg-green-500'
                        : 'bg-amber-500',
                  )}
                />
              </div>

              <div className="flex-1 min-w-0">
                <div className="flex items-center gap-2">
                  <span className="text-xs font-medium text-white">
                    {TRANSITION_LABELS[event.transition] ?? event.transition}
                  </span>
                  <span className="text-[10px] text-gray-600">{new Date(event.occurredAt).toLocaleString()}</span>
                </div>
                {event.message && <p className="text-[11px] text-gray-500 mt-0.5 truncate">{event.message}</p>}
                {event.metricValue != null && (
                  <div className="text-[10px] text-gray-600 mt-0.5">
                    Value: {event.metricValue} · Threshold: {event.threshold}
                  </div>
                )}
              </div>
            </div>
          ))}
        </div>
      </div>
    </div>
  )
}
