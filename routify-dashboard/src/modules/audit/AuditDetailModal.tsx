import {
  X,
  Clock,
  Tag,
  CornerDownRight,
  Activity,
  AlertTriangle,
  CheckCircle2,
  XCircle,
  SkipForward,
  Loader2,
  ChevronDown,
  ChevronRight,
} from 'lucide-react'
import { cn } from '../../lib/utils'
import type { AuditEntry, RequestLogDto, FailedRequestDto, ReplayStatus } from '../../types'
import { useState } from 'react'
import TraceLink from '../gateway/components/TraceLink'

/* ── helpers ────────────────────────────────────────────────────────────────── */

function Field({ label, children, mono = false }: { label: string; children: React.ReactNode; mono?: boolean }) {
  return (
    <div className="flex flex-col gap-0.5">
      <span className="text-[10px] font-semibold text-gray-500 uppercase tracking-widest">{label}</span>
      <span className={cn('text-sm text-gray-200 break-all', mono && 'font-mono text-xs text-gray-300')}>
        {children}
      </span>
    </div>
  )
}

function StatusBadge({ code }: { code?: number }) {
  const cls = !code
    ? 'text-gray-400 bg-gray-500/10 border-gray-500/20'
    : code >= 500
      ? 'text-red-400 bg-red-500/10 border-red-500/20'
      : code >= 400
        ? 'text-amber-400 bg-amber-500/10 border-amber-500/20'
        : code >= 300
          ? 'text-blue-400 bg-blue-500/10 border-blue-500/20'
          : 'text-emerald-400 bg-emerald-500/10 border-emerald-500/20'
  return (
    <span className={cn('inline-flex items-center text-xs font-bold px-2.5 py-0.5 rounded-full border', cls)}>
      {code ?? '—'}
    </span>
  )
}

function MethodBadge({ method }: { method: string }) {
  const cls =
    method === 'GET'
      ? 'bg-blue-500/10 text-blue-300 border-blue-500/20'
      : method === 'POST'
        ? 'bg-green-500/10 text-green-300 border-green-500/20'
        : method === 'PUT'
          ? 'bg-orange-500/10 text-orange-300 border-orange-500/20'
          : method === 'PATCH'
            ? 'bg-yellow-500/10 text-yellow-300 border-yellow-500/20'
            : method === 'DELETE'
              ? 'bg-red-500/10 text-red-300 border-red-500/20'
              : 'bg-gray-500/10 text-gray-300 border-gray-500/20'
  return (
    <span className={cn('inline-flex items-center text-[11px] px-2 py-0.5 rounded font-mono font-bold border', cls)}>
      {method}
    </span>
  )
}

function ReplayStatusBadge({ status }: { status?: ReplayStatus }) {
  if (!status) return <span className="text-gray-600 text-xs">—</span>
  const cfg = {
    PENDING: { label: 'Pending', cls: 'text-amber-400 bg-amber-400/10 border-amber-400/20', icon: Clock },
    IN_PROGRESS: { label: 'Replaying…', cls: 'text-blue-400 bg-blue-400/10 border-blue-400/20', icon: Loader2 },
    SUCCEEDED: {
      label: 'Replayed',
      cls: 'text-emerald-400 bg-emerald-400/10 border-emerald-400/20',
      icon: CheckCircle2,
    },
    FAILED: { label: 'Failed', cls: 'text-red-400 bg-red-400/10 border-red-400/20', icon: XCircle },
    SKIPPED: { label: 'Skipped', cls: 'text-gray-400 bg-gray-400/10 border-gray-400/20', icon: SkipForward },
  }[status]
  if (!cfg) return null
  const Icon = cfg.icon
  return (
    <span
      className={cn('inline-flex items-center gap-1.5 text-xs font-semibold px-2.5 py-1 rounded-full border', cfg.cls)}
    >
      <Icon className={cn('w-3 h-3', status === 'IN_PROGRESS' && 'animate-spin')} />
      {cfg.label}
    </span>
  )
}

function EventTypeBadge({ type }: { type: string }) {
  const cls = type.includes('ACTIVATED')
    ? 'text-emerald-400 bg-emerald-400/10 border-emerald-400/20'
    : type.includes('DEACTIVATED')
      ? 'text-amber-400 bg-amber-400/10 border-amber-400/20'
      : type.includes('DELETED')
        ? 'text-red-400 bg-red-400/10 border-red-400/20'
        : 'text-indigo-400 bg-indigo-400/10 border-indigo-400/20'
  return (
    <span className={cn('inline-flex items-center text-xs font-bold px-3 py-1 rounded-full border tracking-wide', cls)}>
      {type}
    </span>
  )
}

/* ── modal shell ─────────────────────────────────────────────────────────────── */

interface ModalShellProps {
  title: string
  subtitle?: string
  icon: React.ElementType
  iconColor?: string
  onClose: () => void
  children: React.ReactNode
}

function ModalShell({
  title,
  subtitle,
  icon: Icon,
  iconColor = 'text-indigo-400',
  onClose,
  children,
}: ModalShellProps) {
  return (
    // eslint-disable-next-line jsx-a11y/click-events-have-key-events, jsx-a11y/no-static-element-interactions
    <div
      className="fixed inset-0 z-50 flex items-center justify-center p-4"
      style={{ background: 'rgba(0,0,0,0.7)', backdropFilter: 'blur(4px)' }}
      onClick={onClose}
    >
      {/* panel */}
      {/* eslint-disable-next-line jsx-a11y/click-events-have-key-events, jsx-a11y/no-noninteractive-element-interactions */}
      <div
        role="dialog"
        aria-modal="true"
        aria-label={title}
        className="relative w-full max-w-2xl max-h-[90vh] overflow-y-auto rounded-2xl border border-white/[0.09] bg-[#0e1018] shadow-2xl animate-fade-in"
        onClick={(e) => e.stopPropagation()}
      >
        {/* header */}
        <div className="sticky top-0 z-10 flex items-center gap-3 px-6 py-4 border-b border-white/[0.06] bg-[#0e1018]">
          <div
            className={cn(
              'w-8 h-8 rounded-xl border border-white/[0.08] flex items-center justify-center bg-white/[0.04]',
              iconColor,
            )}
          >
            <Icon className="w-4 h-4" />
          </div>
          <div className="flex-1 min-w-0">
            <h2 className="text-sm font-bold text-white truncate">{title}</h2>
            {subtitle && <p className="text-xs text-gray-500 truncate">{subtitle}</p>}
          </div>
          <button
            onClick={onClose}
            className="ml-auto p-1.5 rounded-lg text-gray-500 hover:text-white hover:bg-white/[0.06] transition-all"
          >
            <X className="w-4 h-4" />
          </button>
        </div>

        {/* body */}
        <div className="px-6 py-5 flex flex-col gap-5">{children}</div>
      </div>
    </div>
  )
}

/* ── section wrapper ──────────────────────────────────────────────────────────── */

function Section({ title, children }: { title: string; children: React.ReactNode }) {
  return (
    <div>
      <p className="text-[10px] font-semibold text-gray-600 uppercase tracking-widest mb-3">{title}</p>
      <div className="rounded-xl border border-white/[0.07] bg-white/[0.02] px-4 py-4 grid grid-cols-2 gap-x-6 gap-y-4">
        {children}
      </div>
    </div>
  )
}

function FullRow({ children }: { children: React.ReactNode }) {
  return <div className="col-span-2">{children}</div>
}

/* ── Collapsible headers table ───────────────────────────────────────────────── */

function parseHeaders(raw?: Record<string, string> | string | null): Record<string, string> | null {
  if (!raw) return null
  if (typeof raw === 'object') return raw
  try {
    const parsed = JSON.parse(raw)
    if (parsed && typeof parsed === 'object' && !Array.isArray(parsed)) return parsed as Record<string, string>
    return null
  } catch {
    return null
  }
}

function HeadersSection({ title, headers: rawHeaders }: { title: string; headers?: Record<string, string> | string }) {
  const [open, setOpen] = useState(false)
  const headers = parseHeaders(rawHeaders)
  if (!headers || Object.keys(headers).length === 0) return null
  return (
    <div>
      <button
        onClick={() => setOpen((v) => !v)}
        className="flex items-center gap-2 text-[10px] font-semibold text-gray-500 uppercase tracking-widest mb-2 hover:text-gray-300 transition-colors"
      >
        {open ? <ChevronDown className="w-3 h-3" /> : <ChevronRight className="w-3 h-3" />}
        {title} ({Object.keys(headers).length})
      </button>
      {open && (
        <div className="rounded-xl border border-white/[0.07] bg-white/[0.02] overflow-hidden">
          <table className="w-full text-xs">
            <tbody>
              {Object.entries(headers).map(([k, v]) => (
                <tr key={k} className="border-b border-white/[0.04] last:border-0">
                  <td className="px-3 py-1.5 font-mono text-gray-400 w-1/3 break-all">{k}</td>
                  <td className="px-3 py-1.5 font-mono text-gray-300 break-all">{v}</td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      )}
    </div>
  )
}

/* ── Collapsible body payload ─────────────────────────────────────────────────── */

function BodySection({ title, body }: { title: string; body?: string }) {
  const [open, setOpen] = useState(false)
  if (!body) return null

  let formatted = body
  try {
    formatted = JSON.stringify(JSON.parse(body), null, 2)
  } catch {
    /* not JSON — display as-is */
  }

  return (
    <div>
      <button
        onClick={() => setOpen((v) => !v)}
        className="flex items-center gap-2 text-[10px] font-semibold text-gray-500 uppercase tracking-widest mb-2 hover:text-gray-300 transition-colors"
      >
        {open ? <ChevronDown className="w-3 h-3" /> : <ChevronRight className="w-3 h-3" />}
        {title} ({body.length} chars)
      </button>
      {open && (
        <pre className="rounded-xl border border-white/[0.07] bg-white/[0.02] px-4 py-3 text-xs font-mono text-gray-300 overflow-x-auto whitespace-pre-wrap break-all max-h-64 overflow-y-auto">
          {formatted}
        </pre>
      )}
    </div>
  )
}

/* ═══════════════════════════════════════════════════════════════════════════════
   AuditEntry modal
═══════════════════════════════════════════════════════════════════════════════ */

function AuditEntryModal({ entry, onClose }: { entry: AuditEntry; onClose: () => void }) {
  return (
    <ModalShell
      title="Domain Event Details"
      subtitle={entry.eventId}
      icon={Tag}
      iconColor="text-indigo-400"
      onClose={onClose}
    >
      <Section title="Event">
        <FullRow>
          <Field label="Event Type">
            <EventTypeBadge type={entry.eventType} />
          </Field>
        </FullRow>
        <Field label="Event ID" mono>
          {entry.eventId}
        </Field>
        <Field label="Correlation ID" mono>
          <span className="flex items-center gap-2">
            {entry.correlationId ?? '—'}
            <TraceLink correlationId={entry.correlationId} compact />
          </span>
        </Field>
      </Section>

      <Section title="Aggregate">
        <Field label="Type">
          <span className="text-xs text-gray-400 bg-white/[0.03] border border-white/[0.06] px-2 py-0.5 rounded-md">
            {entry.aggregateType}
          </span>
        </Field>
        <Field label="ID" mono>
          {entry.aggregateId}
        </Field>
        <Field label="Tenant ID" mono>
          {entry.tenantId}
        </Field>
        <Field label="Actor">{entry.actorId ?? <span className="text-gray-600 italic">system</span>}</Field>
      </Section>

      <Section title="Timestamps">
        <Field label="Occurred At">
          <span className="flex items-center gap-1.5">
            <Clock className="w-3.5 h-3.5 text-gray-500" />
            {new Date(entry.occurredAt).toLocaleString()}
          </span>
        </Field>
        <Field label="Recorded At">
          <span className="flex items-center gap-1.5">
            <Clock className="w-3.5 h-3.5 text-gray-500" />
            {new Date(entry.recordedAt).toLocaleString()}
          </span>
        </Field>
      </Section>
    </ModalShell>
  )
}

/* ═══════════════════════════════════════════════════════════════════════════════
   RequestLogDto modal
═══════════════════════════════════════════════════════════════════════════════ */

function RequestLogModal({ log, onClose }: { log: RequestLogDto; onClose: () => void }) {
  const durationColor =
    (log.durationMs ?? 0) > 1000 ? 'text-red-400' : (log.durationMs ?? 0) > 300 ? 'text-amber-400' : 'text-emerald-400'

  return (
    <ModalShell
      title="Request Log Details"
      subtitle={log.id}
      icon={Activity}
      iconColor="text-blue-400"
      onClose={onClose}
    >
      <Section title="Request">
        <Field label="Method">
          <MethodBadge method={log.method} />
        </Field>
        <Field label="Status">
          <StatusBadge code={log.responseStatus} />
        </Field>
        <FullRow>
          <Field label="Path" mono>
            {log.path}
          </Field>
        </FullRow>
        {log.queryString && (
          <FullRow>
            <Field label="Query String" mono>
              {log.queryString}
            </Field>
          </FullRow>
        )}
        {log.upstreamUri && (
          <FullRow>
            <Field label="Upstream URI">
              <span className="flex items-center gap-1.5 font-mono text-xs text-gray-400">
                <CornerDownRight className="w-3.5 h-3.5 text-gray-600 shrink-0" />
                {log.upstreamUri}
              </span>
            </Field>
          </FullRow>
        )}
      </Section>

      <Section title="Route & Client">
        <Field label="Route Name">{log.routeName ?? '—'}</Field>
        <Field label="Route ID" mono>
          {log.routeId ?? '—'}
        </Field>
        <Field label="Client IP" mono>
          {log.clientIp ?? '—'}
        </Field>
        <Field label="Correlation ID" mono>
          <span className="flex items-center gap-2">
            {log.correlationId ?? '—'}
            <TraceLink correlationId={log.correlationId} compact />
          </span>
        </Field>
        <Field label="Duration">
          <span className={cn('font-mono text-sm font-semibold', durationColor)}>
            {log.durationMs != null ? `${log.durationMs} ms` : '—'}
          </span>
        </Field>
        <Field label="Failed">
          {log.failed ? (
            <span className="flex items-center gap-1.5 text-xs text-red-400">
              <AlertTriangle className="w-3.5 h-3.5" />
              Yes
            </span>
          ) : (
            <span className="flex items-center gap-1.5 text-xs text-emerald-400">
              <CheckCircle2 className="w-3.5 h-3.5" />
              No
            </span>
          )}
        </Field>
      </Section>

      {log.errorMessage && (
        <Section title="Error">
          <FullRow>
            <Field label="Error Message">
              <span className="text-red-400/90 text-xs font-mono leading-relaxed">{log.errorMessage}</span>
            </Field>
          </FullRow>
        </Section>
      )}

      {/* Headers — collapsible, only rendered when captured */}
      {(log.requestHeaders || log.responseHeaders) && (
        <div>
          <p className="text-[10px] font-semibold text-gray-600 uppercase tracking-widest mb-3">Headers</p>
          <div className="space-y-3">
            <HeadersSection title="Request Headers" headers={log.requestHeaders} />
            <HeadersSection title="Response Headers" headers={log.responseHeaders} />
          </div>
        </div>
      )}

      {/* Body payloads — collapsible, only rendered when captured */}
      {(log.requestBody || log.responseBody) && (
        <div>
          <p className="text-[10px] font-semibold text-gray-600 uppercase tracking-widest mb-3">Payloads</p>
          <div className="space-y-3">
            <BodySection title="Request Body" body={log.requestBody} />
            <BodySection title="Response Body" body={log.responseBody} />
          </div>
        </div>
      )}

      {log.replayStatus && (
        <Section title="Replay">
          <Field label="Status">
            <ReplayStatusBadge status={log.replayStatus} />
          </Field>
          <Field label="Attempts">
            <span
              className={cn(
                'font-mono font-bold text-sm',
                log.replayCount >= 5 ? 'text-red-400' : log.replayCount >= 3 ? 'text-amber-400' : 'text-gray-300',
              )}
            >
              {log.replayCount} / 5
            </span>
          </Field>
          {log.replayResponseStatus && (
            <Field label="Replay Response Status">
              <StatusBadge code={log.replayResponseStatus} />
            </Field>
          )}
          {log.replayedAt && (
            <Field label="Replayed At">
              <span className="flex items-center gap-1.5">
                <Clock className="w-3.5 h-3.5 text-gray-500" />
                {new Date(log.replayedAt).toLocaleString()}
              </span>
            </Field>
          )}
          {log.replayError && (
            <FullRow>
              <Field label="Replay Error">
                <span className="text-red-400/90 text-xs font-mono leading-relaxed">{log.replayError}</span>
              </Field>
            </FullRow>
          )}
        </Section>
      )}

      <Section title="Timestamp">
        <Field label="Requested At">
          <span className="flex items-center gap-1.5">
            <Clock className="w-3.5 h-3.5 text-gray-500" />
            {new Date(log.requestedAt).toLocaleString()}
          </span>
        </Field>
        <Field label="Tenant ID" mono>
          {log.tenantId}
        </Field>
      </Section>
    </ModalShell>
  )
}

/* ═══════════════════════════════════════════════════════════════════════════════
   FailedRequestDto modal
═══════════════════════════════════════════════════════════════════════════════ */

function FailedRequestModal({ req, onClose }: { req: FailedRequestDto; onClose: () => void }) {
  const durationColor =
    (req.durationMs ?? 0) > 1000 ? 'text-red-400' : (req.durationMs ?? 0) > 300 ? 'text-amber-400' : 'text-emerald-400'

  return (
    <ModalShell
      title="Failed Request Details"
      subtitle={req.id}
      icon={AlertTriangle}
      iconColor="text-red-400"
      onClose={onClose}
    >
      <Section title="Request">
        <Field label="Method">
          <MethodBadge method={req.method} />
        </Field>
        <Field label="Status">
          <StatusBadge code={req.responseStatus} />
        </Field>
        <FullRow>
          <Field label="Path" mono>
            {req.path}
          </Field>
        </FullRow>
        {req.queryString && (
          <FullRow>
            <Field label="Query String" mono>
              {req.queryString}
            </Field>
          </FullRow>
        )}
        {req.upstreamUri && (
          <FullRow>
            <Field label="Upstream URI">
              <span className="flex items-center gap-1.5 font-mono text-xs text-gray-400">
                <CornerDownRight className="w-3.5 h-3.5 text-gray-600 shrink-0" />
                {req.upstreamUri}
              </span>
            </Field>
          </FullRow>
        )}
      </Section>

      <Section title="Route & Client">
        <Field label="Route Name">{req.routeName ?? '—'}</Field>
        <Field label="Route ID" mono>
          {req.routeId ?? '—'}
        </Field>
        <Field label="Correlation ID" mono>
          <span className="flex items-center gap-2">
            {req.correlationId ?? '—'}
            <TraceLink correlationId={req.correlationId} compact />
          </span>
        </Field>
        <Field label="Duration">
          <span className={cn('font-mono text-sm font-semibold', durationColor)}>
            {req.durationMs != null ? `${req.durationMs} ms` : '—'}
          </span>
        </Field>
      </Section>

      <Section title="Error">
        <FullRow>
          <Field label="Error Message">
            {req.errorMessage ? (
              <span className="text-red-400/90 text-xs font-mono leading-relaxed">{req.errorMessage}</span>
            ) : (
              <span className="text-gray-600 italic text-xs">—</span>
            )}
          </Field>
        </FullRow>
      </Section>

      {/* Headers — collapsible */}
      {(req.requestHeaders || req.responseHeaders) && (
        <div>
          <p className="text-[10px] font-semibold text-gray-600 uppercase tracking-widest mb-3">Headers</p>
          <div className="space-y-3">
            <HeadersSection title="Request Headers" headers={req.requestHeaders} />
            <HeadersSection title="Response Headers" headers={req.responseHeaders} />
          </div>
        </div>
      )}

      {/* Body payloads — collapsible */}
      {(req.requestBody || req.responseBody) && (
        <div>
          <p className="text-[10px] font-semibold text-gray-600 uppercase tracking-widest mb-3">Payloads</p>
          <div className="space-y-3">
            <BodySection title="Request Body" body={req.requestBody} />
            <BodySection title="Response Body" body={req.responseBody} />
          </div>
        </div>
      )}

      <Section title="Replay">
        <Field label="Status">
          <ReplayStatusBadge status={req.replayStatus} />
        </Field>
        <Field label="Attempts">
          <span
            className={cn(
              'font-mono font-bold text-sm',
              req.replayCount >= 5 ? 'text-red-400' : req.replayCount >= 3 ? 'text-amber-400' : 'text-gray-300',
            )}
          >
            {req.replayCount} / 5
          </span>
        </Field>
        {req.replayResponseStatus && (
          <Field label="Replay Response Status">
            <StatusBadge code={req.replayResponseStatus} />
          </Field>
        )}
        {req.replayedAt && (
          <Field label="Replayed At">
            <span className="flex items-center gap-1.5">
              <Clock className="w-3.5 h-3.5 text-gray-500" />
              {new Date(req.replayedAt).toLocaleString()}
            </span>
          </Field>
        )}
        {req.replayError && (
          <FullRow>
            <Field label="Replay Error">
              <span className="text-red-400/90 text-xs font-mono leading-relaxed">{req.replayError}</span>
            </Field>
          </FullRow>
        )}
      </Section>

      <Section title="Timestamps">
        <Field label="Requested At">
          <span className="flex items-center gap-1.5">
            <Clock className="w-3.5 h-3.5 text-gray-500" />
            {new Date(req.requestedAt).toLocaleString()}
          </span>
        </Field>
        <Field label="Tenant ID" mono>
          {req.tenantId}
        </Field>
      </Section>
    </ModalShell>
  )
}

/* ═══════════════════════════════════════════════════════════════════════════════
   Public unified export
═══════════════════════════════════════════════════════════════════════════════ */

export type AuditModalPayload =
  | { kind: 'event'; data: AuditEntry }
  | { kind: 'request'; data: RequestLogDto }
  | { kind: 'failed'; data: FailedRequestDto }

interface AuditDetailModalProps {
  payload: AuditModalPayload | null
  onClose: () => void
}

export function AuditDetailModal({ payload, onClose }: AuditDetailModalProps) {
  if (!payload) return null

  if (payload.kind === 'event') return <AuditEntryModal entry={payload.data} onClose={onClose} />
  if (payload.kind === 'request') return <RequestLogModal log={payload.data} onClose={onClose} />
  if (payload.kind === 'failed') return <FailedRequestModal req={payload.data} onClose={onClose} />

  return null
}
