/**
 * CircuitBreakersTab — Live circuit breaker state cards with transition history.
 *
 * Shows per-route/service circuit breaker cards that update in real-time
 * via WebSocket (`wsStore`). Tracks the last 10 state transitions per CB
 * in Zustand client state.
 */
import { useEffect, useRef, useState } from 'react'
import { RefreshCw, Wifi, Clock } from 'lucide-react'
import { useWsStore } from '../../../store/wsStore'
import { useRealtimeQuery } from '../../../hooks/useRealtimeQuery'
import { gatewayApi } from '../../../api/gatewayApi'
import { cn } from '../../../lib/utils'
import type { GatewayConfig } from '../../../types'
import { Card, StatusBadge } from '../components/GatewayPrimitives'

// ─── CB state transition history (client-side) ──────────────────────────────

interface CbTransition {
  from: string
  to: string
  at: string
}

// ─── CB Card ────────────────────────────────────────────────────────────────

function CbDetailCard({
  name,
  state,
  history,
}: {
  name: string
  state: { state: string; failureRate: number; bufferedCalls: number }
  history: CbTransition[]
}) {
  const norm = (state.state ?? 'UNKNOWN').toUpperCase()
  const ring =
    {
      CLOSED: 'border-emerald-500/30 bg-emerald-500/5',
      OPEN: 'border-red-500/40 bg-red-500/[0.07]',
      HALF_OPEN: 'border-amber-500/30 bg-amber-500/[0.06]',
    }[norm] ?? 'border-white/[0.06] bg-white/[0.02]'

  const stateColor =
    {
      CLOSED: 'text-emerald-400',
      OPEN: 'text-red-400',
      HALF_OPEN: 'text-amber-400',
    }[norm] ?? 'text-gray-400'

  return (
    <div className={cn('rounded-xl border p-4 space-y-4', ring)}>
      <div className="flex items-start justify-between gap-2">
        <div className="min-w-0">
          <div className="text-sm font-medium text-white font-mono truncate">{name}</div>
          <div className={cn('text-xs font-semibold mt-1', stateColor)}>{norm}</div>
        </div>
        <StatusBadge state={state.state} />
      </div>

      {/* Metrics */}
      <div className="grid grid-cols-2 gap-2 text-xs">
        <div className="bg-white/[0.04] rounded-lg px-3 py-2">
          <div className="text-gray-500 mb-0.5">Failure Rate</div>
          <div
            className={cn(
              'font-semibold',
              state.failureRate > 50 ? 'text-red-400' : state.failureRate > 20 ? 'text-amber-400' : 'text-emerald-400',
            )}
          >
            {state.failureRate?.toFixed(1)}%
          </div>
        </div>
        <div className="bg-white/[0.04] rounded-lg px-3 py-2">
          <div className="text-gray-500 mb-0.5">Buffered Calls</div>
          <div className="font-semibold text-white">{state.bufferedCalls}</div>
        </div>
      </div>

      {/* Transition timeline */}
      {history.length > 0 && (
        <div className="space-y-1.5">
          <div className="text-[10px] text-gray-600 font-medium uppercase tracking-wider flex items-center gap-1.5">
            <Clock className="w-3 h-3" />
            Recent Transitions
          </div>
          <div className="space-y-1 max-h-32 overflow-y-auto scrollbar-none">
            {history.map((t, i) => (
              <div key={i} className="flex items-center gap-2 text-[10px]">
                <span className="text-gray-600 font-mono w-16 shrink-0">{new Date(t.at).toLocaleTimeString()}</span>
                <span className="text-gray-500">{t.from}</span>
                <span className="text-gray-600">→</span>
                <span
                  className={cn(
                    'font-semibold',
                    t.to === 'CLOSED' ? 'text-emerald-400' : t.to === 'OPEN' ? 'text-red-400' : 'text-amber-400',
                  )}
                >
                  {t.to}
                </span>
              </div>
            ))}
          </div>
        </div>
      )}
    </div>
  )
}

// ─── Main component ─────────────────────────────────────────────────────────

interface Props {
  config: GatewayConfig
}

// eslint-disable-next-line @typescript-eslint/no-unused-vars
export default function CircuitBreakersTab({ config: _config }: Props) {
  const wsCbStates = useWsStore((s) => s.circuitBreakers)
  const wsStatus = useWsStore((s) => s.status)

  // Track CB state transition history (last 10 per CB)
  const [cbHistory, setCbHistory] = useState<Record<string, CbTransition[]>>({})
  const prevStatesRef = useRef<Record<string, string>>({})

  const { data: status } = useRealtimeQuery({
    queryKey: ['gateway-status'],
    queryFn: gatewayApi.getStatus,
    wsEvents: ['gateway', 'route'],
  })

  // Prefer live WS data; fall back to HTTP polled data
  const cbStates =
    Object.keys(wsCbStates).length > 0
      ? wsCbStates
      : ((status?.circuitBreakers ?? {}) as Record<
          string,
          { state: string; failureRate: number; bufferedCalls: number }
        >)

  // Detect state transitions and record them
  useEffect(() => {
    const prev = prevStatesRef.current
    const newHistory = { ...cbHistory }

    for (const [name, state] of Object.entries(cbStates)) {
      const norm = (state.state ?? 'UNKNOWN').toUpperCase()
      const prevNorm = prev[name]

      if (prevNorm && prevNorm !== norm) {
        const transitions = newHistory[name] ?? []
        newHistory[name] = [{ from: prevNorm, to: norm, at: new Date().toISOString() }, ...transitions].slice(0, 10)
      }

      prev[name] = norm
    }

    prevStatesRef.current = prev
    if (
      Object.keys(newHistory).length !== Object.keys(cbHistory).length ||
      JSON.stringify(newHistory) !== JSON.stringify(cbHistory)
    ) {
      setCbHistory(newHistory)
    }
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [cbStates])

  const cbCount = Object.keys(cbStates).length
  const openCbs = Object.values(cbStates).filter((s) => s.state?.toUpperCase() === 'OPEN').length
  const halfOpenCbs = Object.values(cbStates).filter((s) => s.state?.toUpperCase() === 'HALF_OPEN').length

  return (
    <div className="space-y-6 max-w-5xl">
      {/* Header */}
      <div className="flex items-center justify-between">
        <div>
          <h2 className="text-base font-semibold text-white">Circuit Breakers</h2>
          <p className="text-sm text-gray-400 mt-0.5">Live circuit breaker states updated via WebSocket — no polling</p>
        </div>
        <div className="flex items-center gap-3">
          {wsStatus === 'CONNECTED' && (
            <div className="flex items-center gap-1.5 text-xs text-emerald-400 bg-emerald-400/10 border border-emerald-400/20 px-2.5 py-1 rounded-full">
              <Wifi className="w-3 h-3 animate-pulse" />
              Live
            </div>
          )}
        </div>
      </div>

      {/* Summary strip */}
      <div className="grid grid-cols-3 gap-3">
        <div className="bg-white/[0.025] rounded-lg px-4 py-3 border border-white/[0.05]">
          <div className="text-[10px] text-gray-500 uppercase tracking-wider mb-1 font-medium">Total</div>
          <div className="text-lg font-bold text-white">{cbCount}</div>
        </div>
        <div className="bg-white/[0.025] rounded-lg px-4 py-3 border border-white/[0.05]">
          <div className="text-[10px] text-gray-500 uppercase tracking-wider mb-1 font-medium">Open</div>
          <div className={cn('text-lg font-bold', openCbs > 0 ? 'text-red-400' : 'text-emerald-400')}>{openCbs}</div>
        </div>
        <div className="bg-white/[0.025] rounded-lg px-4 py-3 border border-white/[0.05]">
          <div className="text-[10px] text-gray-500 uppercase tracking-wider mb-1 font-medium">Half-Open</div>
          <div className={cn('text-lg font-bold', halfOpenCbs > 0 ? 'text-amber-400' : 'text-emerald-400')}>
            {halfOpenCbs}
          </div>
        </div>
      </div>

      {/* CB cards */}
      {cbCount > 0 ? (
        <div className="grid grid-cols-1 sm:grid-cols-2 lg:grid-cols-3 gap-3">
          {Object.entries(cbStates)
            .sort(([, a], [, b]) => {
              const order = { OPEN: 0, HALF_OPEN: 1, CLOSED: 2 } as Record<string, number>
              return (order[a.state?.toUpperCase()] ?? 3) - (order[b.state?.toUpperCase()] ?? 3)
            })
            .map(([name, state]) => (
              <CbDetailCard key={name} name={name} state={state} history={cbHistory[name] ?? []} />
            ))}
        </div>
      ) : (
        <Card>
          <div className="flex items-center gap-3 text-sm text-gray-500 py-4">
            <RefreshCw className="w-4 h-4 text-gray-600" />
            No circuit breaker data — data streams in once gateway routes are active
          </div>
        </Card>
      )}
    </div>
  )
}
