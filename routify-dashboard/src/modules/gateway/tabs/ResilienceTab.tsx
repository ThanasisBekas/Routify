/**
 * ResilienceTab — Circuit breaker + retry/timeout/bulkhead defaults.
 *
 * Redesigned with a sub-tab nav (Circuit Breaker | Retry & Timeout)
 * inside the Resilience section to eliminate the "two separate save buttons
 * in one scroll" UX issue.
 */
import { useState } from 'react'
import { RefreshCw, AlertTriangle, Timer } from 'lucide-react'
import type { GatewayCircuitBreakerDefaults, GatewayResilienceDefaults } from '../../../types'
import {
  SectionHeader,
  ToggleRow,
  Field,
  InlineSaveButton,
  inputCls,
  monoInputCls,
  Card,
  InfoBanner,
} from '../components/GatewayPrimitives'
import { cn } from '../../../lib/utils'
import { Select } from '../../../components/ui/Select'

type ResilienceSubTab = 'circuit-breaker' | 'retry-timeout'

interface Props {
  initialCb: GatewayCircuitBreakerDefaults
  initialRd: GatewayResilienceDefaults
  onSaveCb: (v: GatewayCircuitBreakerDefaults) => void
  onSaveRd: (v: GatewayResilienceDefaults) => void
  isPendingCb: boolean
  isPendingRd: boolean
}

export default function ResilienceTab({ initialCb, initialRd, onSaveCb, onSaveRd, isPendingCb, isPendingRd }: Props) {
  const [sub, setSub] = useState<ResilienceSubTab>('circuit-breaker')
  const [cb, setCb] = useState<GatewayCircuitBreakerDefaults>(initialCb)
  const [rd, setRd] = useState<GatewayResilienceDefaults>(initialRd)
  const dirtyCb = JSON.stringify(cb) !== JSON.stringify(initialCb)
  const dirtyRd = JSON.stringify(rd) !== JSON.stringify(initialRd)

  const SUBS: {
    id: ResilienceSubTab
    label: string
    icon: React.ComponentType<{ className?: string }>
    dirty: boolean
  }[] = [
    { id: 'circuit-breaker', label: 'Circuit Breaker', icon: AlertTriangle, dirty: dirtyCb },
    { id: 'retry-timeout', label: 'Retry & Timeout', icon: Timer, dirty: dirtyRd },
  ]

  return (
    <div className="max-w-2xl">
      <SectionHeader
        icon={RefreshCw}
        title="Resilience Defaults"
        description="Default Resilience4J settings applied when a route uses CIRCUIT_BREAKER, RETRY, or TIMEOUT filters without explicit per-filter overrides."
      />

      {/* Sub-tab nav */}
      <div className="flex items-center gap-1 mb-6 bg-white/[0.03] border border-white/[0.06] rounded-xl p-1">
        {SUBS.map((s) => (
          <button
            key={s.id}
            onClick={() => setSub(s.id)}
            className={cn(
              'flex-1 flex items-center justify-center gap-2 px-4 py-2 rounded-lg text-sm font-medium transition-all',
              sub === s.id
                ? 'bg-indigo-600 text-white shadow-lg shadow-indigo-500/20'
                : 'text-gray-400 hover:text-white',
            )}
          >
            <s.icon className="w-3.5 h-3.5" />
            {s.label}
            {s.dirty && <span className="w-1.5 h-1.5 rounded-full bg-amber-400 shrink-0" title="Unsaved changes" />}
          </button>
        ))}
      </div>

      {/* ── Circuit Breaker ─────────────────────────────────────────────────── */}
      {sub === 'circuit-breaker' && (
        <div className="space-y-6">
          <InfoBanner variant="info">
            These defaults apply to all routes using the <code>CIRCUIT_BREAKER</code> filter that do not specify
            explicit overrides. The circuit opens when <strong>failure rate ≥ threshold</strong> and stays open for the{' '}
            <strong>wait duration</strong> before transitioning to HALF-OPEN.
          </InfoBanner>

          <div className="grid grid-cols-2 gap-4">
            <Field label="Sliding Window Type">
              <Select
                value={cb.slidingWindowType}
                onChange={(v) => setCb((p) => ({ ...p, slidingWindowType: v as 'COUNT_BASED' | 'TIME_BASED' }))}
                options={[
                  { value: 'COUNT_BASED', label: 'COUNT_BASED', description: 'Window defined by number of calls' },
                  {
                    value: 'TIME_BASED',
                    label: 'TIME_BASED',
                    description: 'Window defined by time duration (seconds)',
                  },
                ]}
              />
            </Field>
            <Field
              label="Sliding Window Size"
              hint={cb.slidingWindowType === 'COUNT_BASED' ? 'Number of calls' : 'Seconds'}
            >
              <input
                type="number"
                min={1}
                value={cb.slidingWindowSize}
                onChange={(e) => setCb((p) => ({ ...p, slidingWindowSize: Number(e.target.value) }))}
                className={inputCls}
              />
            </Field>
            <Field label="Failure Rate Threshold (%)" hint="Circuit opens when this percentage of calls fail">
              <input
                type="number"
                min={0}
                max={100}
                value={cb.failureRateThreshold}
                onChange={(e) => setCb((p) => ({ ...p, failureRateThreshold: Number(e.target.value) }))}
                className={inputCls}
              />
            </Field>
            <Field label="Minimum Number of Calls" hint="Circuit won't open below this count">
              <input
                type="number"
                min={1}
                value={cb.minimumNumberOfCalls}
                onChange={(e) => setCb((p) => ({ ...p, minimumNumberOfCalls: Number(e.target.value) }))}
                className={inputCls}
              />
            </Field>
            <Field label="Wait in Open State" hint="Duration before transitioning to HALF-OPEN (e.g. 10s, 1m)">
              <input
                value={cb.waitDurationInOpenState}
                onChange={(e) => setCb((p) => ({ ...p, waitDurationInOpenState: e.target.value }))}
                className={monoInputCls}
                placeholder="10s"
              />
            </Field>
            <Field label="Calls Permitted in Half-Open" hint="Number of test calls allowed before deciding to close">
              <input
                type="number"
                min={1}
                value={cb.permittedNumberOfCallsInHalfOpenState}
                onChange={(e) =>
                  setCb((p) => ({ ...p, permittedNumberOfCallsInHalfOpenState: Number(e.target.value) }))
                }
                className={inputCls}
              />
            </Field>
            <div className="col-span-2">
              <Field
                label="Default Fallback URI"
                optional
                hint="Forwarded to when circuit is OPEN (e.g. /fallback/circuit-open)"
              >
                <input
                  value={cb.fallbackUri}
                  onChange={(e) => setCb((p) => ({ ...p, fallbackUri: e.target.value }))}
                  className={monoInputCls}
                  placeholder="/fallback/circuit-open"
                />
              </Field>
            </div>
          </div>

          <div className="space-y-1">
            <ToggleRow
              label="Auto-transition from OPEN to HALF-OPEN"
              description="Automatically test recovery after the wait duration without requiring a manual probe request"
              checked={cb.automaticTransitionFromOpenToHalfOpen}
              onChange={(v) => setCb((p) => ({ ...p, automaticTransitionFromOpenToHalfOpen: v }))}
            />
          </div>

          <InlineSaveButton
            onSave={() => onSaveCb(cb)}
            isPending={isPendingCb}
            dirty={dirtyCb}
            label="Apply Circuit Breaker Defaults"
          />
        </div>
      )}

      {/* ── Retry & Timeout ─────────────────────────────────────────────────── */}
      {sub === 'retry-timeout' && (
        <div className="space-y-6">
          {/* Retry */}
          <Card>
            <div className="text-xs font-bold text-gray-500 uppercase tracking-widest mb-4">Retry Policy</div>
            <div className="grid grid-cols-2 gap-4">
              <Field label="Max Attempts">
                <input
                  type="number"
                  min={1}
                  value={rd.retryMaxAttempts}
                  onChange={(e) => setRd((p) => ({ ...p, retryMaxAttempts: Number(e.target.value) }))}
                  className={inputCls}
                />
              </Field>
              <Field label="Wait Duration" hint="e.g. 500ms, 1s">
                <input
                  value={rd.retryWaitDuration}
                  onChange={(e) => setRd((p) => ({ ...p, retryWaitDuration: e.target.value }))}
                  className={monoInputCls}
                  placeholder="500ms"
                />
              </Field>
              <Field label="Max Wait Duration" hint="Exponential backoff cap, e.g. 5s">
                <input
                  value={rd.retryMaxWaitDuration}
                  onChange={(e) => setRd((p) => ({ ...p, retryMaxWaitDuration: e.target.value }))}
                  className={monoInputCls}
                  placeholder="5s"
                />
              </Field>
              <Field label="Backoff Multiplier" hint="Applied per retry when exponential backoff is enabled">
                <input
                  type="number"
                  step={0.1}
                  min={1}
                  value={rd.retryExponentialMultiplier}
                  onChange={(e) => setRd((p) => ({ ...p, retryExponentialMultiplier: Number(e.target.value) }))}
                  className={inputCls}
                />
              </Field>
            </div>
            <div className="mt-3 space-y-1">
              <ToggleRow
                label="Exponential Backoff"
                description="Multiply wait duration by the backoff multiplier on each subsequent retry"
                checked={rd.retryExponentialBackoff}
                onChange={(v) => setRd((p) => ({ ...p, retryExponentialBackoff: v }))}
              />
            </div>
          </Card>

          {/* Timeout */}
          <Card>
            <div className="text-xs font-bold text-gray-500 uppercase tracking-widest mb-4">Request Timeout</div>
            <div className="grid grid-cols-2 gap-4">
              <Field label="Timeout Duration" hint="e.g. 10s, 30s — returns 504 if exceeded">
                <input
                  value={rd.timeoutDuration}
                  onChange={(e) => setRd((p) => ({ ...p, timeoutDuration: e.target.value }))}
                  className={monoInputCls}
                  placeholder="30s"
                />
              </Field>
            </div>
            <div className="mt-3 space-y-1">
              <ToggleRow
                label="Cancel Running Future on Timeout"
                description="Interrupt the running upstream call when timeout is exceeded (recommended)"
                checked={rd.timeoutCancelRunningFuture}
                onChange={(v) => setRd((p) => ({ ...p, timeoutCancelRunningFuture: v }))}
              />
            </div>
          </Card>

          {/* Bulkhead */}
          <Card>
            <div className="text-xs font-bold text-gray-500 uppercase tracking-widest mb-4">
              Bulkhead (Concurrency Limit)
            </div>
            <div className="space-y-1 mb-4">
              <ToggleRow
                label="Bulkhead Enabled"
                description="Limit the maximum number of concurrent calls per route to prevent upstream overload"
                checked={rd.bulkheadEnabled}
                onChange={(v) => setRd((p) => ({ ...p, bulkheadEnabled: v }))}
              />
            </div>
            {rd.bulkheadEnabled && (
              <Field
                label="Max Concurrent Calls"
                hint="Number of concurrent requests allowed through; excess are rejected immediately"
              >
                <input
                  type="number"
                  min={1}
                  value={rd.bulkheadMaxConcurrentCalls}
                  onChange={(e) => setRd((p) => ({ ...p, bulkheadMaxConcurrentCalls: Number(e.target.value) }))}
                  className={inputCls}
                />
              </Field>
            )}
          </Card>

          <InlineSaveButton
            onSave={() => onSaveRd(rd)}
            isPending={isPendingRd}
            dirty={dirtyRd}
            label="Apply Resilience Defaults"
          />
        </div>
      )}
    </div>
  )
}
