import { useState } from 'react'
import { useMutation, useQueryClient } from '@tanstack/react-query'
import { auditApi } from '../../api/auditApi'
import { useWsStore } from '../../store/wsStore'
import { useRealtimeQuery } from '../../hooks/useRealtimeQuery'
import { useDocumentTitle } from '../../hooks/useDocumentTitle'
import {
  ClipboardList, RefreshCw, Activity, Clock, Wifi, Radio,
  AlertTriangle, RotateCcw, CheckCircle2, XCircle, SkipForward,
  Loader2, Play, Zap, ChevronRight, ShieldAlert,
} from 'lucide-react'
import { cn } from '../../lib/utils'
import type { ReplayStatus } from '../../types'
import { AuditDetailModal } from './AuditDetailModal'
import type { AuditModalPayload } from './AuditDetailModal'

type Tab = 'events' | 'requests' | 'replay'

function ReplayBadge({ status }: { status?: ReplayStatus }) {
  if (!status) return null
  const cfg = {
    PENDING:     { label: 'Pending',    cls: 'text-amber-400 bg-amber-400/10 border-amber-400/20',       icon: Clock },
    IN_PROGRESS: { label: 'Replaying…', cls: 'text-blue-400 bg-blue-400/10 border-blue-400/20',         icon: Loader2 },
    SUCCEEDED:   { label: 'Replayed',   cls: 'text-emerald-400 bg-emerald-400/10 border-emerald-400/20', icon: CheckCircle2 },
    FAILED:      { label: 'Failed',     cls: 'text-red-400 bg-red-400/10 border-red-400/20',             icon: XCircle },
    SKIPPED:     { label: 'Skipped',    cls: 'text-gray-400 bg-gray-400/10 border-gray-400/20',         icon: SkipForward },
  }[status]
  if (!cfg) return null
  const Icon = cfg.icon
  return (
    <span className={cn('inline-flex items-center gap-1 text-[10px] font-semibold px-1.5 py-0.5 rounded-full border', cfg.cls)}>
      <Icon className={cn('w-2.5 h-2.5', status === 'IN_PROGRESS' && 'animate-spin')} />
      {cfg.label}
    </span>
  )
}

function StatusBadge({ code }: { code?: number }) {
  const cls =
    !code        ? 'text-gray-400 bg-gray-500/10 border-gray-500/20' :
    code >= 500  ? 'text-red-400 bg-red-500/10 border-red-500/20' :
    code >= 400  ? 'text-amber-400 bg-amber-500/10 border-amber-500/20' :
    code >= 300  ? 'text-blue-400 bg-blue-500/10 border-blue-500/20' :
                   'text-emerald-400 bg-emerald-500/10 border-emerald-500/20'
  return <span className={cn('text-xs font-bold px-2 py-0.5 rounded-full border', cls)}>{code ?? '—'}</span>
}

function MethodBadge({ method }: { method: string }) {
  const cls =
    method === 'GET'    ? 'bg-blue-500/10 text-blue-300 border-blue-500/20' :
    method === 'POST'   ? 'bg-green-500/10 text-green-300 border-green-500/20' :
    method === 'PUT'    ? 'bg-orange-500/10 text-orange-300 border-orange-500/20' :
    method === 'PATCH'  ? 'bg-yellow-500/10 text-yellow-300 border-yellow-500/20' :
    method === 'DELETE' ? 'bg-red-500/10 text-red-300 border-red-500/20' :
                          'bg-gray-500/10 text-gray-300 border-gray-500/20'
  return <span className={cn('text-[10px] px-1.5 py-0.5 rounded font-mono font-bold border', cls)}>{method}</span>
}

function ReplayStatsBar() {
  const { data } = useRealtimeQuery({
    queryKey: ['replay-stats'],
    queryFn: () => auditApi.getReplayStats(),
    wsEvents: ['replay', 'audit'],
  })
  if (!data) return null
  const total = data.pending + data.inProgress + data.succeeded + data.failed + data.skipped
  if (total === 0) return null
  return (
    <div className="flex items-center gap-4 px-6 py-3 border-b border-white/[0.06] bg-[#0c0e14]">
      <span className="text-[11px] text-gray-500 font-medium uppercase tracking-wider shrink-0">Replay</span>
      <div className="flex items-center gap-3 flex-wrap">
        {data.pending > 0 && (
          <span className="flex items-center gap-1.5 text-xs text-amber-400"><Clock className="w-3 h-3" /><strong>{data.pending}</strong> pending</span>
        )}
        {data.inProgress > 0 && (
          <span className="flex items-center gap-1.5 text-xs text-blue-400"><Loader2 className="w-3 h-3 animate-spin" /><strong>{data.inProgress}</strong> in progress</span>
        )}
        {data.succeeded > 0 && (
          <span className="flex items-center gap-1.5 text-xs text-emerald-400"><CheckCircle2 className="w-3 h-3" /><strong>{data.succeeded}</strong> succeeded</span>
        )}
        {data.failed > 0 && (
          <span className="flex items-center gap-1.5 text-xs text-red-400"><XCircle className="w-3 h-3" /><strong>{data.failed}</strong> failed</span>
        )}
        {data.skipped > 0 && (
          <span className="flex items-center gap-1.5 text-xs text-gray-500"><SkipForward className="w-3 h-3" /><strong>{data.skipped}</strong> skipped</span>
        )}
      </div>
    </div>
  )
}

function TH({ children }: { children: React.ReactNode }) {
  return <th className="px-4 py-3 text-[11px] font-semibold text-gray-500 uppercase tracking-widest first:px-6">{children}</th>
}

function EmptyState({ icon: Icon, title, desc, accent = 'indigo' }: {
  icon: React.ElementType; title: string; desc: string; accent?: 'indigo' | 'emerald'
}) {
  return (
    <div className="flex flex-col items-center justify-center py-24 gap-4">
      <div className={cn('w-16 h-16 rounded-2xl border flex items-center justify-center',
        accent === 'emerald' ? 'bg-emerald-500/[0.05] border-emerald-500/20' : 'bg-white/[0.03] border-white/[0.06]')}>
        <Icon className={cn('w-7 h-7', accent === 'emerald' ? 'text-emerald-600' : 'text-gray-600')} />
      </div>
      <div className="text-center">
        <p className="text-sm font-medium text-gray-300 mb-1">{title}</p>
        <p className="text-xs text-gray-600">{desc}</p>
      </div>
    </div>
  )
}

export default function AuditPage() {
  useDocumentTitle('Audit Log')
  const [tab, setTab]                   = useState<Tab>('events')
  const [page, setPage]                 = useState(0)
  const [liveFeed, setLiveFeed]         = useState(true)
  const [replayFilter, setReplayFilter] = useState<'all' | 'pending'>('pending')
  const [replayingId, setReplayingId]   = useState<string | null>(null)
  const [modalPayload, setModalPayload] = useState<AuditModalPayload | null>(null)

  const queryClient  = useQueryClient()
  const wsStatus     = useWsStore(s => s.status)
  const recentEvents = useWsStore(s => s.recentEvents)

  const liveEvents = liveFeed
    ? recentEvents.filter(e => !['metrics', 'connected', 'pong', 'gateway.config.changed', 'gateway.reloaded'].includes(e.type))
    : []

  const eventsQuery = useRealtimeQuery({
    queryKey: ['audit-events', page],
    queryFn: () => auditApi.listEvents({ page, size: 50 }),
    enabled: tab === 'events',
    wsEvents: ['route', 'filter', 'gateway', 'audit'],
  })
  const requestsQuery = useRealtimeQuery({
    queryKey: ['audit-requests', page],
    queryFn: () => auditApi.listRequests({ page, size: 50 }),
    enabled: tab === 'requests',
    wsEvents: ['audit'],
  })
  const failedQuery = useRealtimeQuery({
    queryKey: ['audit-failed', replayFilter, page],
    queryFn: () => replayFilter === 'pending'
      ? auditApi.listPendingReplay({ page, size: 50 })
      : auditApi.listFailed({ page, size: 50 }),
    enabled: tab === 'replay',
    wsEvents: ['replay', 'audit'],
  })

  const replaySingle = useMutation({
    mutationFn: (id: string) => auditApi.replaySingle(id),
    onMutate: (id) => setReplayingId(id),
    onSettled: () => {
      setReplayingId(null)
      queryClient.invalidateQueries({ queryKey: ['audit-failed'] })
      queryClient.invalidateQueries({ queryKey: ['replay-stats'] })
    },
  })
  const replayBulk = useMutation({
    mutationFn: () => auditApi.replayBulk(50),
    onSettled: () => {
      queryClient.invalidateQueries({ queryKey: ['audit-failed'] })
      queryClient.invalidateQueries({ queryKey: ['replay-stats'] })
    },
  })

  const activeQuery = tab === 'events' ? eventsQuery : tab === 'requests' ? requestsQuery : failedQuery
  const isLoading   = activeQuery.isLoading
  const isFetching  = activeQuery.isFetching
  const events      = eventsQuery.data?.content ?? []
  const requests    = requestsQuery.data?.content ?? []
  const failedReqs  = failedQuery.data?.content ?? []
  const total       = activeQuery.data?.totalElements ?? 0
  const totalPages  = activeQuery.data?.totalPages ?? 0
  const pendingCount = failedQuery.data?.content?.filter(r => r.replayStatus === 'PENDING' || r.replayStatus === 'FAILED').length ?? 0

  return (
    <div className="flex flex-col h-full animate-fade-in">
      {/* Header */}
      <div className="px-6 py-5 border-b border-white/[0.06] bg-[#0c0e14]">
        <div className="flex items-center justify-between mb-4">
          <div>
            <div className="flex items-center gap-2.5 mb-1">
              <h1 className="text-lg font-bold text-white tracking-tight">Audit</h1>
              {wsStatus === 'CONNECTED' && (
                <span className="flex items-center gap-1 text-[10px] font-semibold text-emerald-400 bg-emerald-400/10 border border-emerald-400/20 px-2 py-0.5 rounded-full">
                  <Wifi className="w-2.5 h-2.5" />Live
                </span>
              )}
            </div>
            <p className="text-sm text-gray-500">Immutable event trail, full request telemetry, and failed-request replay</p>
          </div>
          <div className="flex items-center gap-2">
            {tab === 'events' && wsStatus === 'CONNECTED' && (
              <button
                onClick={() => setLiveFeed(v => !v)}
                className={cn('flex items-center gap-1.5 text-xs px-3 py-1.5 rounded-lg border font-medium transition-all',
                  liveFeed ? 'bg-emerald-400/10 border-emerald-400/20 text-emerald-400'
                           : 'bg-white/[0.03] border-white/[0.06] text-gray-400 hover:text-white')}
              >
                <Radio className={cn('w-3 h-3', liveFeed && 'animate-pulse')} />
                Live {liveFeed ? 'on' : 'off'}
              </button>
            )}
            {tab === 'replay' && (
              <button
                onClick={() => replayBulk.mutate()}
                disabled={replayBulk.isPending || pendingCount === 0}
                className={cn('flex items-center gap-1.5 text-xs px-3 py-1.5 rounded-lg border font-medium transition-all',
                  replayBulk.isPending || pendingCount === 0
                    ? 'opacity-40 cursor-not-allowed bg-white/[0.03] border-white/[0.06] text-gray-500'
                    : 'bg-indigo-500/10 border-indigo-500/20 text-indigo-300 hover:bg-indigo-500/20')}
              >
                {replayBulk.isPending
                  ? <><Loader2 className="w-3 h-3 animate-spin" />Replaying…</>
                  : <><Zap className="w-3 h-3" />Replay All ({pendingCount})</>}
              </button>
            )}
            <button
              onClick={() => activeQuery.refetch()}
              className="p-2 rounded-lg text-gray-500 hover:text-gray-300 hover:bg-white/[0.05] border border-white/[0.06] transition-all"
            >
              <RefreshCw className={cn('w-3.5 h-3.5', isFetching && 'animate-spin')} />
            </button>
          </div>
        </div>

        {/* Tabs */}
        <div className="flex gap-1">
          {(['events', 'requests', 'replay'] as Tab[]).map(t => (
            <button key={t} onClick={() => { setTab(t); setPage(0) }}
              className={cn('flex items-center gap-2 px-4 py-2 text-sm font-medium rounded-lg transition-all',
                tab === t ? 'bg-white/[0.08] text-white' : 'text-gray-500 hover:text-gray-300 hover:bg-white/[0.04]')}>
              {t === 'events'   && <><ClipboardList className="w-3.5 h-3.5" />Domain Events</>}
              {t === 'requests' && <><Activity className="w-3.5 h-3.5" />Request Logs</>}
              {t === 'replay'   && <><ShieldAlert className="w-3.5 h-3.5" />Failed &amp; Replay</>}
              {tab === t && (
                <span className={cn('text-xs px-1.5 py-0.5 rounded-full',
                  t === 'replay' ? 'bg-red-500/20 text-red-300' : 'bg-indigo-500/20 text-indigo-300')}>
                  {total}
                </span>
              )}
            </button>
          ))}
        </div>
      </div>

      {/* Replay stats bar */}
      {tab === 'replay' && <ReplayStatsBar />}

      {/* Replay sub-filter */}
      {tab === 'replay' && (
        <div className="flex items-center gap-2 px-6 py-2.5 border-b border-white/[0.06] bg-[#0a0c12]">
          <span className="text-[11px] text-gray-500 font-medium">Show:</span>
          {(['pending', 'all'] as const).map(f => (
            <button key={f} onClick={() => { setReplayFilter(f); setPage(0) }}
              className={cn('text-xs px-3 py-1 rounded-md border font-medium transition-all',
                replayFilter === f
                  ? 'bg-white/[0.08] border-white/[0.12] text-white'
                  : 'bg-transparent border-white/[0.06] text-gray-500 hover:text-gray-300')}>
              {f === 'pending' ? 'Pending / Failed' : 'All Failed'}
            </button>
          ))}
        </div>
      )}

      {/* Live feed banner */}
      {tab === 'events' && liveEvents.length > 0 && (
        <div className="border-b border-emerald-500/10 bg-emerald-500/[0.04]">
          <div className="px-6 py-2 flex items-center gap-2 text-xs text-emerald-400">
            <Radio className="w-3 h-3 animate-pulse" />
            <span className="font-semibold">Streaming live</span>
            <span className="text-emerald-600">— {liveEvents.length} new event{liveEvents.length !== 1 ? 's' : ''}</span>
          </div>
          <div>
            {liveEvents.slice(0, 5).map(e => (
              <div key={e.id} className="flex items-center gap-3 px-6 py-2 border-t border-emerald-500/5">
                <span className="w-1.5 h-1.5 rounded-full bg-emerald-400 animate-pulse shrink-0" />
                <span className={cn('text-[11px] px-2 py-0.5 rounded-full font-semibold border',
                  e.type.includes('activated')   ? 'text-emerald-400 bg-emerald-400/10 border-emerald-400/20' :
                  e.type.includes('deactivated') ? 'text-amber-400 bg-amber-400/10 border-amber-400/20' :
                  e.type.includes('deleted')     ? 'text-red-400 bg-red-400/10 border-red-400/20' :
                  'text-indigo-400 bg-indigo-400/10 border-indigo-400/20')}>
                  {e.type.toUpperCase().replace('.', '_')}
                </span>
                <span className="text-xs text-gray-600 font-mono">{new Date(e.occurredAt).toLocaleTimeString()}</span>
                <span className="text-xs text-gray-500 ml-auto truncate">{e.label}</span>
              </div>
            ))}
          </div>
        </div>
      )}

      {/* Content */}
      <div className="flex-1 overflow-auto">
        {isLoading ? (
          <div className="flex flex-col items-center justify-center py-24 gap-3">
            <div className="w-7 h-7 border-2 border-indigo-500/30 border-t-indigo-500 rounded-full animate-spin" />
            <p className="text-sm text-gray-500">Loading…</p>
          </div>
        ) : tab === 'events' ? (
          events.length === 0
            ? <EmptyState icon={ClipboardList} title="No audit events yet" desc="Events appear here as actions occur on the platform" />
            : (
              <table className="w-full text-sm">
                <thead>
                  <tr className="text-left border-b border-white/[0.06] bg-[#0c0e14]">
                    <TH>Event Type</TH><TH>Aggregate</TH><TH>ID</TH><TH>Actor</TH><TH>Occurred At</TH>
                  </tr>
                </thead>
                <tbody>
                  {events.map(e => (
                    <tr key={e.eventId}
                      onClick={() => setModalPayload({ kind: 'event', data: e })}
                      className="border-b border-white/[0.04] hover:bg-white/[0.025] transition-colors cursor-pointer group">
                      <td className="px-6 py-3">
                        <span className={cn('text-[11px] px-2 py-1 rounded-full font-semibold border',
                          e.eventType.includes('ACTIVATED')   ? 'text-emerald-400 bg-emerald-400/10 border-emerald-400/20' :
                          e.eventType.includes('DEACTIVATED') ? 'text-amber-400 bg-amber-400/10 border-amber-400/20' :
                          e.eventType.includes('DELETED')     ? 'text-red-400 bg-red-400/10 border-red-400/20' :
                          'text-indigo-400 bg-indigo-400/10 border-indigo-400/20')}>
                          {e.eventType}
                        </span>
                      </td>
                      <td className="px-4 py-3">
                        <span className="text-xs text-gray-400 bg-white/[0.03] border border-white/[0.06] px-2 py-0.5 rounded-md">{e.aggregateType}</span>
                      </td>
                      <td className="px-4 py-3">
                        <code className="text-xs text-gray-500 font-mono bg-white/[0.03] px-1.5 py-0.5 rounded">{e.aggregateId?.slice(0, 8)}…</code>
                      </td>
                      <td className="px-4 py-3 text-xs text-gray-500">{e.actorId ?? 'system'}</td>
                      <td className="px-4 py-3">
                        <span className="flex items-center gap-1.5 text-xs text-gray-500">
                          <Clock className="w-3 h-3 text-gray-600" />{new Date(e.occurredAt).toLocaleString()}
                        </span>
                      </td>
                    </tr>
                  ))}
                </tbody>
              </table>
            )
        ) : tab === 'requests' ? (
          requests.length === 0
            ? <EmptyState icon={Activity} title="No request logs yet" desc="Request logs appear here once traffic flows through the gateway" />
            : (
              <table className="w-full text-sm">
                <thead>
                  <tr className="text-left border-b border-white/[0.06] bg-[#0c0e14]">
                    <TH>Route</TH><TH>Method</TH><TH>Path</TH><TH>Status</TH><TH>Duration</TH><TH>Failed</TH><TH>Time</TH>
                  </tr>
                </thead>
                <tbody>
                  {requests.map(r => (
                    <tr key={r.id}
                      onClick={() => setModalPayload({ kind: 'request', data: r })}
                      className={cn('border-b border-white/[0.04] hover:bg-white/[0.025] transition-colors cursor-pointer group', r.failed && 'bg-red-500/[0.02]')}>
                      <td className="px-6 py-3 text-xs text-gray-300 font-medium">{r.routeName}</td>
                      <td className="px-4 py-3"><MethodBadge method={r.method} /></td>
                      <td className="px-4 py-3"><code className="text-xs text-gray-400 font-mono">{r.path}</code></td>
                      <td className="px-4 py-3"><StatusBadge code={r.responseStatus} /></td>
                      <td className="px-4 py-3">
                        <span className={cn('text-xs font-mono',
                          (r.durationMs ?? 0) > 1000 ? 'text-red-400' : (r.durationMs ?? 0) > 300 ? 'text-amber-400' : 'text-gray-400')}>
                          {r.durationMs != null ? `${r.durationMs}ms` : '—'}
                        </span>
                      </td>
                      <td className="px-4 py-3">
                        {r.failed
                          ? <span className="flex items-center gap-1 text-[10px] text-red-400"><AlertTriangle className="w-3 h-3" />Yes</span>
                          : <span className="text-[10px] text-gray-600">—</span>}
                      </td>
                      <td className="px-4 py-3 text-xs text-gray-500">{new Date(r.requestedAt).toLocaleString()}</td>
                    </tr>
                  ))}
                </tbody>
              </table>
            )
        ) : (
          /* ── Failed & Replay ─────────────────────────────────────────── */
          failedReqs.length === 0
            ? <EmptyState icon={CheckCircle2} accent="emerald"
                title={replayFilter === 'pending' ? 'No pending replays' : 'No failed requests'}
                desc={replayFilter === 'pending'
                  ? 'All failed requests have been replayed or skipped'
                  : 'No requests have failed through the gateway'} />
            : (
              <table className="w-full text-sm">
                <thead>
                  <tr className="text-left border-b border-white/[0.06] bg-[#0c0e14]">
                    <TH>Route</TH><TH>Method</TH><TH>Path / Upstream</TH><TH>Status</TH>
                    <TH>Error</TH><TH>Replay</TH><TH>Attempts</TH><TH>Time</TH><TH> </TH>
                  </tr>
                </thead>
                <tbody>
                  {failedReqs.map(r => {
                    const canReplay  = (r.replayStatus === 'PENDING' || r.replayStatus === 'FAILED') && r.replayCount < 5
                    const isReplaying = replayingId === r.id

                    return (
                      <tr key={r.id}
                        onClick={() => setModalPayload({ kind: 'failed', data: r })}
                        className={cn('border-b border-white/[0.04] hover:bg-white/[0.025] transition-colors cursor-pointer group',
                          r.replayStatus === 'SUCCEEDED' && 'opacity-60')}>
                        <td className="px-6 py-3 text-xs text-gray-300 font-medium">
                          {r.routeName ?? <span className="text-gray-600">—</span>}
                        </td>
                        <td className="px-4 py-3"><MethodBadge method={r.method} /></td>
                        <td className="px-4 py-3 max-w-[260px]">
                          <code className="text-xs text-gray-400 font-mono block truncate">{r.path}</code>
                          {r.upstreamUri && (
                            <span className="flex items-center gap-1 text-[10px] text-gray-600 font-mono mt-0.5 truncate">
                              <ChevronRight className="w-2.5 h-2.5 shrink-0" />{r.upstreamUri}
                            </span>
                          )}
                        </td>
                        <td className="px-4 py-3"><StatusBadge code={r.responseStatus} /></td>
                        <td className="px-4 py-3 max-w-[180px]">
                          {r.errorMessage
                            ? <span className="text-[10px] text-red-400/80 truncate block" title={r.errorMessage}>{r.errorMessage}</span>
                            : <span className="text-[10px] text-gray-600">—</span>}
                        </td>
                        <td className="px-4 py-3">
                          <div className="flex flex-col gap-1">
                            <ReplayBadge status={r.replayStatus} />
                            {r.replayResponseStatus && (
                              <span className="text-[10px] text-gray-600 font-mono">↳ {r.replayResponseStatus}</span>
                            )}
                            {r.replayError && (
                              <span className="text-[10px] text-red-500/70 truncate max-w-[140px]" title={r.replayError}>{r.replayError}</span>
                            )}
                          </div>
                        </td>
                        <td className="px-4 py-3 text-center">
                          <span className={cn('text-xs font-mono font-bold',
                            r.replayCount >= 5 ? 'text-red-400' : r.replayCount >= 3 ? 'text-amber-400' : 'text-gray-400')}>
                            {r.replayCount} / 5
                          </span>
                        </td>
                        <td className="px-4 py-3 text-xs text-gray-500">
                          <div>{new Date(r.requestedAt).toLocaleString()}</div>
                          {r.replayedAt && (
                            <div className="text-[10px] text-gray-600 mt-0.5">Replayed {new Date(r.replayedAt).toLocaleTimeString()}</div>
                          )}
                        </td>
                        <td className="px-4 py-3">
                          {canReplay && (
                            <button
                              onClick={(e) => { e.stopPropagation(); replaySingle.mutate(r.id) }}
                              disabled={!!replayingId}
                              className={cn('flex items-center gap-1 text-[10px] font-semibold px-2.5 py-1 rounded-lg border transition-all',
                                isReplaying
                                  ? 'bg-indigo-500/10 border-indigo-500/20 text-indigo-300'
                                  : 'bg-white/[0.03] border-white/[0.08] text-gray-400 hover:bg-indigo-500/10 hover:border-indigo-500/20 hover:text-indigo-300',
                                !!replayingId && !isReplaying && 'opacity-30 cursor-not-allowed')}
                            >
                              {isReplaying ? <><Loader2 className="w-3 h-3 animate-spin" />Replaying</> : <><Play className="w-3 h-3" />Replay</>}
                            </button>
                          )}
                          {!canReplay && r.replayStatus === 'SUCCEEDED' && (
                            <span className="flex items-center gap-1 text-[10px] text-emerald-400/60"><CheckCircle2 className="w-3 h-3" />Done</span>
                          )}
                          {!canReplay && (r.replayStatus === 'SKIPPED' || r.replayCount >= 5) && (
                            <span className="flex items-center gap-1 text-[10px] text-gray-600"><SkipForward className="w-3 h-3" />Skipped</span>
                          )}
                        </td>
                      </tr>
                    )
                  })}
                </tbody>
              </table>
            )
        )}
      </div>

      {/* Bulk replay result */}
      {replayBulk.isSuccess && replayBulk.data && (
        <div className="mx-6 mb-4 px-4 py-3 rounded-xl border border-indigo-500/20 bg-indigo-500/[0.06] flex items-center gap-3">
          <RotateCcw className="w-4 h-4 text-indigo-400 shrink-0" />
          <span className="text-xs text-indigo-300">
            Bulk replay complete —{' '}
            <strong>{replayBulk.data.succeeded}</strong> succeeded,{' '}
            <strong>{replayBulk.data.failed}</strong> failed,{' '}
            <strong>{replayBulk.data.skipped}</strong> skipped out of{' '}
            <strong>{replayBulk.data.total}</strong> total
          </span>
        </div>
      )}

      {/* Pagination */}
      {totalPages > 1 && (
        <div className="flex items-center justify-between px-6 py-3 border-t border-white/[0.06] bg-[#0c0e14]">
          <span className="text-xs text-gray-500">{total} total</span>
          <div className="flex items-center gap-1">
            {Array.from({ length: Math.min(totalPages, 10) }, (_, i) => (
              <button key={i} onClick={() => setPage(i)}
                className={cn('w-7 h-7 rounded-md text-xs font-medium transition-all',
                  page === i ? 'bg-indigo-600 text-white shadow-lg shadow-indigo-500/20' : 'text-gray-500 hover:text-white hover:bg-white/[0.05]')}>
                {i + 1}
              </button>
            ))}
          </div>
        </div>
      )}

      <AuditDetailModal payload={modalPayload} onClose={() => setModalPayload(null)} />
    </div>
  )
}
