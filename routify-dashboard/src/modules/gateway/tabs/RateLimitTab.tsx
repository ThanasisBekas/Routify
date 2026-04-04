/**
 * RateLimitTab — Global rate limit policy management.
 *
 * Redesigned with richer policy cards (algorithm chip, key-resolver badge,
 * linked-filter count), an extracted modal component, and an empty state.
 */
import { useState } from 'react'
import { Gauge, Plus, Edit, Trash2, X } from 'lucide-react'
import type { GatewayRateLimitPolicy, FilterSummary } from '../../../types'
import {
  SectionHeader, ToggleRow, Field, SaveBar, EmptyState,
  LinkedBadge, inputCls, textareaCls,
} from '../components/GatewayPrimitives'
import { cn } from '../../../lib/utils'
import { Select } from '../../../components/ui/Select'
import { filtersApi } from '../../../api/filtersApi'
import { useRealtimeQuery } from '../../../hooks/useRealtimeQuery'

// ─── Hook: linked filter counts ───────────────────────────────────────────────

function useLinkedFilterCounts() {
  const { data } = useRealtimeQuery({
    queryKey: ['filters'],
    queryFn: () => filtersApi.list({ page: 0, size: 200 }),
    wsEvents: ['filter'],
  })
  const filters: FilterSummary[] = (data as { content: FilterSummary[] } | undefined)?.content ?? []
  const countForRef = (refId: string) => filters.filter(f => f.gatewayConfigRef?.refId === refId).length
  const namesForRef = (refId: string) => filters.filter(f => f.gatewayConfigRef?.refId === refId).map(f => f.name)
  return { countForRef, namesForRef }
}

// ─── Algorithm / key-resolver badges ─────────────────────────────────────────

const ALGO_COLORS: Record<string, string> = {
  TOKEN_BUCKET:   'text-indigo-300 bg-indigo-500/10 border-indigo-500/20',
  FIXED_WINDOW:   'text-amber-300 bg-amber-500/10 border-amber-500/20',
  SLIDING_WINDOW: 'text-purple-300 bg-purple-500/10 border-purple-500/20',
}

const KEY_COLORS: Record<string, string> = {
  IP:      'text-sky-300 bg-sky-500/10 border-sky-500/20',
  USER:    'text-violet-300 bg-violet-500/10 border-violet-500/20',
  TENANT:  'text-teal-300 bg-teal-500/10 border-teal-500/20',
  API_KEY: 'text-orange-300 bg-orange-500/10 border-orange-500/20',
}

// ─── Policy editor modal ──────────────────────────────────────────────────────

const ALGORITHMS = ['TOKEN_BUCKET', 'FIXED_WINDOW', 'SLIDING_WINDOW'] as const
const KEY_RESOLVERS = ['IP', 'USER', 'TENANT', 'API_KEY'] as const

function PolicyModal({
  policy,
  isNew,
  onSave,
  onClose,
}: {
  policy: GatewayRateLimitPolicy
  isNew: boolean
  onSave: (p: GatewayRateLimitPolicy) => void
  onClose: () => void
}) {
  const [p, setP] = useState(policy)

  return (
    <div className="fixed inset-0 bg-black/60 backdrop-blur-sm flex items-center justify-center z-50 p-4">
      <div className="bg-[#13151a] border border-white/10 rounded-xl w-full max-w-lg shadow-2xl max-h-[90vh] flex flex-col">
        {/* Header */}
        <div className="flex items-center justify-between px-6 py-4 border-b border-white/[0.07] shrink-0">
          <div className="flex items-center gap-3">
            <div className="w-7 h-7 rounded-lg bg-amber-500/10 border border-amber-500/20 flex items-center justify-center">
              <Gauge className="w-3.5 h-3.5 text-amber-400" />
            </div>
            <h3 className="text-sm font-semibold text-white">
              {isNew ? 'New Rate Limit Policy' : 'Edit Policy'}
            </h3>
          </div>
          <button onClick={onClose} className="p-1.5 text-gray-400 hover:text-white hover:bg-white/[0.05] rounded-lg transition-colors">
            <X className="w-4 h-4" />
          </button>
        </div>

        {/* Body */}
        <div className="flex-1 overflow-y-auto p-6 space-y-4">
          <div className="grid grid-cols-2 gap-4">
            <div className="col-span-2">
              <Field label="Policy Name">
                <input
                  value={p.name}
                  onChange={e => setP(prev => ({ ...prev, name: e.target.value }))}
                  placeholder="e.g. public-api-limit"
                  className={inputCls}
                />
              </Field>
            </div>
            <Field label="Description" optional>
              <input
                value={p.description ?? ''}
                onChange={e => setP(prev => ({ ...prev, description: e.target.value }))}
                className={inputCls}
              />
            </Field>
            <div />
            <Field label="Algorithm">
              <Select
                value={p.algorithm}
                onChange={v => setP(prev => ({ ...prev, algorithm: v as GatewayRateLimitPolicy['algorithm'] }))}
                options={ALGORITHMS.map(a => ({
                  value: a,
                  label: a.replace(/_/g, ' '),
                  description: a === 'TOKEN_BUCKET'
                    ? 'Allows short bursts above rate'
                    : a === 'FIXED_WINDOW'
                    ? 'Simple counter, resets at interval'
                    : 'Accurate rolling window (more memory)',
                }))}
              />
            </Field>
            <Field label="Key Resolver" hint="How to identify the rate-limited subject">
              <Select
                value={p.keyResolver}
                onChange={v => setP(prev => ({ ...prev, keyResolver: v as GatewayRateLimitPolicy['keyResolver'] }))}
                options={KEY_RESOLVERS.map(k => ({
                  value: k,
                  label: k,
                  description: k === 'IP' ? 'Remote IP address' : k === 'USER' ? 'X-Auth-User-Id header' : k === 'TENANT' ? 'X-Tenant-Id header' : 'X-API-Key header',
                }))}
              />
            </Field>
            <Field label="Replenish Rate (req/s)">
              <input
                type="number"
                min={1}
                value={p.replenishRate}
                onChange={e => setP(prev => ({ ...prev, replenishRate: Number(e.target.value) }))}
                className={inputCls}
              />
            </Field>
            <Field label="Burst Capacity">
              <input
                type="number"
                min={1}
                value={p.burstCapacity}
                onChange={e => setP(prev => ({ ...prev, burstCapacity: Number(e.target.value) }))}
                className={inputCls}
              />
            </Field>
            <Field label="Requested Tokens">
              <input
                type="number"
                min={1}
                value={p.requestedTokens}
                onChange={e => setP(prev => ({ ...prev, requestedTokens: Number(e.target.value) }))}
                className={inputCls}
              />
            </Field>
            <Field label="Window (ms)" hint="For FIXED_WINDOW and SLIDING_WINDOW algorithms">
              <input
                type="number"
                min={100}
                value={p.windowMs}
                onChange={e => setP(prev => ({ ...prev, windowMs: Number(e.target.value) }))}
                className={inputCls}
              />
            </Field>
          </div>

          <Field
            label="Global Paths"
            optional
            hint="Apply to these paths globally (one per line). Leave empty to make this route-scoped only."
          >
            <textarea
              rows={2}
              value={(p.globalPaths ?? []).join('\n')}
              onChange={e => setP(prev => ({ ...prev, globalPaths: e.target.value.split('\n').map(s => s.trim()).filter(Boolean) }))}
              placeholder="/api/**"
              className={`${textareaCls} font-mono text-xs`}
            />
          </Field>

          <ToggleRow label="Policy Enabled" checked={p.enabled} onChange={v => setP(prev => ({ ...prev, enabled: v }))} />
        </div>

        {/* Footer */}
        <div className="flex items-center justify-end gap-3 px-6 py-4 border-t border-white/[0.07] shrink-0">
          <button onClick={onClose} className="px-4 py-2 text-sm text-gray-400 hover:text-white transition-colors">
            Cancel
          </button>
          <button
            onClick={() => { onSave(p); onClose() }}
            disabled={!p.name.trim()}
            className="px-4 py-2 bg-indigo-600 hover:bg-indigo-500 disabled:opacity-50 text-white text-sm font-medium rounded-lg transition-colors"
          >
            Save Policy
          </button>
        </div>
      </div>
    </div>
  )
}

// ─── Main tab ─────────────────────────────────────────────────────────────────

interface Props {
  initial: GatewayRateLimitPolicy[]
  onSave: (v: GatewayRateLimitPolicy[]) => void
  isPending: boolean
}

export default function RateLimitTab({ initial, onSave, isPending }: Props) {
  const [policies, setPolicies] = useState<GatewayRateLimitPolicy[]>(initial)
  const [editing, setEditing] = useState<{ policy: GatewayRateLimitPolicy; isNew: boolean } | null>(null)
  const dirty = JSON.stringify(policies) !== JSON.stringify(initial)
  const { countForRef, namesForRef } = useLinkedFilterCounts()

  const savePolicy = (p: GatewayRateLimitPolicy) => {
    setPolicies(prev => {
      const list = prev.filter(x => x.id !== p.id)
      return [...list, p]
    })
    setEditing(null)
  }

  const newPolicy = (): GatewayRateLimitPolicy => ({
    id: `rl-${Date.now()}`,
    name: '',
    algorithm: 'TOKEN_BUCKET',
    keyResolver: 'IP',
    replenishRate: 100,
    burstCapacity: 200,
    requestedTokens: 1,
    windowMs: 1000,
    enabled: true,
    globalPaths: [],
  })

  return (
    <div className="max-w-3xl">
      <SectionHeader
        icon={Gauge}
        title="Rate Limit Policies"
        description="Reusable token-bucket, fixed-window, or sliding-window policies. Policies are referenced by filter definitions and applied at request time by the gateway."
        actions={
          <button
            onClick={() => setEditing({ policy: newPolicy(), isNew: true })}
            className="flex items-center gap-2 px-3 py-2 bg-indigo-600 hover:bg-indigo-500 text-white text-sm font-medium rounded-lg transition-colors"
          >
            <Plus className="w-4 h-4" />
            New Policy
          </button>
        }
      />

      {policies.length === 0 ? (
        <EmptyState
          icon={Gauge}
          title="No rate limit policies defined"
          description="Create a policy to apply token-bucket or sliding-window rate limiting across your gateway routes."
          action={
            <button
              onClick={() => setEditing({ policy: newPolicy(), isNew: true })}
              className="flex items-center gap-2 px-3 py-2 bg-indigo-600/20 hover:bg-indigo-600/30 border border-indigo-500/30 text-indigo-400 text-xs font-medium rounded-lg transition-all"
            >
              <Plus className="w-3.5 h-3.5" /> Create first policy
            </button>
          }
        />
      ) : (
        <div className="space-y-3">
          {policies.map(p => {
            const linked = countForRef(p.id)
            const names  = namesForRef(p.id)
            return (
              <div
                key={p.id}
                className="bg-white/[0.03] rounded-xl border border-white/[0.06] p-4 hover:border-white/[0.09] transition-colors"
              >
                <div className="flex items-start justify-between gap-3">
                  {/* Left: identity */}
                  <div className="flex items-start gap-3 min-w-0">
                    <div className={`mt-1 w-2 h-2 rounded-full shrink-0 ${p.enabled ? 'bg-emerald-400' : 'bg-gray-600'}`} />
                    <div className="min-w-0">
                      <div className="flex items-center gap-2 flex-wrap">
                        <span className="text-sm font-semibold text-white">{p.name || '(unnamed)'}</span>
                        {/* Algorithm chip */}
                        <span className={`text-[10px] px-2 py-0.5 rounded font-mono font-medium border ${ALGO_COLORS[p.algorithm] ?? 'text-gray-400 bg-white/5 border-white/10'}`}>
                          {p.algorithm.replace(/_/g, ' ')}
                        </span>
                        {/* Key resolver chip */}
                        <span className={`text-[10px] px-2 py-0.5 rounded font-mono font-medium border ${KEY_COLORS[p.keyResolver] ?? 'text-gray-400 bg-white/5 border-white/10'}`}>
                          {p.keyResolver}
                        </span>
                        <LinkedBadge count={linked} names={names} />
                      </div>
                      <div className="text-xs text-gray-500 mt-1 font-mono">
                        {p.replenishRate} req/s · burst {p.burstCapacity}
                        {p.windowMs && ` · window ${p.windowMs}ms`}
                        {p.globalPaths && p.globalPaths.length > 0 && (
                          <span className="ml-2 text-gray-600">paths: {p.globalPaths.join(', ')}</span>
                        )}
                      </div>
                    </div>
                  </div>
                  {/* Right: actions */}
                  <div className="flex items-center gap-1 shrink-0">
                    <button
                      onClick={() => setEditing({ policy: p, isNew: false })}
                      className="p-1.5 text-gray-400 hover:text-white hover:bg-white/[0.05] rounded-lg transition-colors"
                      title="Edit policy"
                    >
                      <Edit className="w-3.5 h-3.5" />
                    </button>
                    <button
                      onClick={() => {
                        if (linked > 0 && !confirm(`This policy is used by ${linked} filter(s). Delete anyway?`)) return
                        setPolicies(prev => prev.filter(x => x.id !== p.id))
                      }}
                      className="p-1.5 text-red-400/70 hover:text-red-400 hover:bg-red-400/10 rounded-lg transition-colors"
                      title="Delete policy"
                    >
                      <Trash2 className="w-3.5 h-3.5" />
                    </button>
                  </div>
                </div>
              </div>
            )
          })}
        </div>
      )}

      {editing && (
        <PolicyModal
          policy={editing.policy}
          isNew={editing.isNew}
          onSave={savePolicy}
          onClose={() => setEditing(null)}
        />
      )}

      <SaveBar onSave={() => onSave(policies)} isPending={isPending} dirty={dirty} />
    </div>
  )
}

