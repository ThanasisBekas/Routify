/**
 * AuthProviderPicker — reusable dropdown that fetches the gateway's configured
 * auth providers and lets the operator pick one.
 *
 * Props:
 *  - `value`          current provider ID (or empty string for "none")
 *  - `onChange`        callback when a provider is selected
 *  - `typeFilter`      optional predicate on `GatewayAuthProvider.type` to narrow options
 *                      (e.g. `t => t.startsWith('OAUTH2')` for AUTH_OAUTH2 filters)
 *  - `label` / `hint` / `optional`  forwarded to the wrapping <Field>
 *  - `noneLabel`       label for the "None" placeholder option (defaults to generic text)
 *
 * Created by P-07: Auth Provider Picker for Filter Forms.
 */
import { useQuery } from '@tanstack/react-query'
import { gatewayApi } from '../../api/gatewayApi'
import { Select } from '../../components/ui/Select'
import type { GatewayAuthProvider } from '../../types'

interface AuthProviderPickerProps {
  value: string
  onChange: (providerId: string, provider?: GatewayAuthProvider) => void
  /** Filter the provider list by type. Receives the provider `type` string and returns true to include. */
  typeFilter?: (type: GatewayAuthProvider['type']) => boolean
  label: string
  hint?: string
  optional?: boolean
  noneLabel?: string
  noneDescription?: string
  placeholder?: string
}

export function AuthProviderPicker({
  value,
  onChange,
  typeFilter,
  label,
  hint,
  optional,
  noneLabel = 'None — use manual config below',
  noneDescription = 'Enter provider details manually',
  placeholder,
}: AuthProviderPickerProps) {
  const { data: authProviders = [], isLoading } = useQuery({
    queryKey: ['gateway', 'auth-providers'],
    queryFn: () => gatewayApi.getAuthProviders(),
    staleTime: 30_000,
  })

  const filtered = authProviders.filter((p) => {
    if (!p.enabled) return false
    if (typeFilter && !typeFilter(p.type)) return false
    return true
  })

  const options = [
    { value: '', label: noneLabel, description: noneDescription },
    ...filtered.map((p) => ({
      value: p.id,
      label: p.name,
      description: `${p.type}${p.uri ? ` · ${p.uri}` : ''}`,
    })),
  ]

  // If the current value isn't in the available options (e.g. removed/disabled provider), show it
  const hasCurrentValue = value && !filtered.some((p) => p.id === value)

  const handleChange = (providerId: string) => {
    const provider = authProviders.find((p) => p.id === providerId)
    onChange(providerId, provider)
  }

  return (
    <Field label={label} hint={hint} optional={optional}>
      <Select
        value={value}
        onChange={handleChange}
        options={
          hasCurrentValue
            ? [{ value, label: value, description: '(provider not found — may have been removed)' }, ...options]
            : options
        }
        placeholder={isLoading ? 'Loading providers…' : placeholder ?? 'Select an Auth Provider…'}
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
              <span>⚠</span> Provider not found in current gateway config
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

