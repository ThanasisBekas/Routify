/**
 * RateLimitPolicyPicker — reusable dropdown that fetches the gateway's configured
 * rate-limit policies and lets the operator pick one.
 *
 * Props:
 *  - `value`          current policy ID (or empty string for "none")
 *  - `onChange`        callback when a policy is selected
 *  - `algorithmFilter` optional predicate on `GatewayRateLimitPolicy.algorithm`
 *                      (e.g. `a => a === 'FIXED_WINDOW'` for fixed-window filters)
 *  - `label` / `hint` / `optional`  forwarded to the wrapping <Field>
 *  - `noneLabel`       label for the "None" placeholder option
 *
 * Created by P-09: Rate Limit Policy Picker for Filter Forms.
 */
import { useQuery } from '@tanstack/react-query'
import { gatewayApi } from '../../api/gatewayApi'
import { Select } from '../../components/ui/Select'
import type { GatewayRateLimitPolicy } from '../../types'

interface RateLimitPolicyPickerProps {
  value: string
  onChange: (policyId: string, policy?: GatewayRateLimitPolicy) => void
  /** Filter the policy list by algorithm. Receives the `algorithm` string and returns true to include. */
  algorithmFilter?: (algorithm: GatewayRateLimitPolicy['algorithm']) => boolean
  label: string
  hint?: string
  optional?: boolean
  noneLabel?: string
  noneDescription?: string
  placeholder?: string
}

export function RateLimitPolicyPicker({
  value,
  onChange,
  algorithmFilter,
  label,
  hint,
  optional,
  noneLabel = 'None — configure manually',
  noneDescription = 'Enter rate limit values in the fields below',
  placeholder,
}: RateLimitPolicyPickerProps) {
  const { data: policies = [], isLoading } = useQuery({
    queryKey: ['gateway', 'rate-limit-policies'],
    queryFn: () => gatewayApi.getRateLimitPolicies(),
    staleTime: 30_000,
  })

  const filtered = policies.filter((p) => {
    if (!p.enabled) return false
    if (algorithmFilter && !algorithmFilter(p.algorithm)) return false
    return true
  })

  const formatWindow = (ms: number) => {
    if (ms >= 3_600_000) return `${ms / 3_600_000}h`
    if (ms >= 60_000) return `${ms / 60_000}min`
    if (ms >= 1_000) return `${ms / 1_000}s`
    return `${ms}ms`
  }

  const options = [
    { value: '', label: noneLabel, description: noneDescription },
    ...filtered.map((p) => ({
      value: p.id,
      label: p.name,
      description: `${p.algorithm} · ${p.replenishRate} req/${formatWindow(p.windowMs)} · key: ${p.keyResolver}${p.description ? ` — ${p.description}` : ''}`,
    })),
  ]

  // If the current value isn't in the available options (e.g. removed/disabled policy), show it
  const hasCurrentValue = value && !filtered.some((p) => p.id === value)

  const handleChange = (policyId: string) => {
    const policy = policies.find((p) => p.id === policyId)
    onChange(policyId, policy)
  }

  return (
    <Field label={label} hint={hint} optional={optional}>
      <Select
        value={value}
        onChange={handleChange}
        options={
          hasCurrentValue
            ? [{ value, label: value, description: '(policy not found — may have been removed)' }, ...options]
            : options
        }
        placeholder={isLoading ? 'Loading policies…' : placeholder ?? 'Select a Rate Limit Policy…'}
        disabled={isLoading}
        searchable
      />
      {value && !isLoading && (
        <div className="flex items-center gap-1.5 mt-1.5">
          {filtered.some((p) => p.id === value) ? (
            <span className="text-[10px] text-emerald-400/80 flex items-center gap-1">
              <span>✓</span> Resolved from gateway config
            </span>
          ) : (
            <span className="text-[10px] text-amber-400/80 flex items-center gap-1">
              <span>⚠</span> Policy not found in current gateway config
            </span>
          )}
        </div>
      )}
    </Field>
  )
}

/* ── Inline Field wrapper (mirrors the one in FilterConfigFields) ────────── */
function Field({
  label,
  hint,
  children,
  optional,
}: {
  label: string
  hint?: string
  children: React.ReactNode
  optional?: boolean
}) {
  return (
    <div className="space-y-1.5">
      <div className="flex items-center gap-2">
        <label className="text-xs font-semibold text-gray-400 uppercase tracking-wider">{label}</label>
        {optional && <span className="text-[10px] text-gray-600 normal-case font-normal">optional</span>}
      </div>
      {children}
      {hint && <p className="text-[11px] text-gray-600">{hint}</p>}
    </div>
  )
}

