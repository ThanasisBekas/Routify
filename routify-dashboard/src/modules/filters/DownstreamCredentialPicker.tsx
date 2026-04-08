/**
 * DownstreamCredentialPicker — reusable dropdown that fetches the gateway's
 * configured downstream credentials and lets the operator pick one.
 *
 * Props:
 *  - `value`          current credential ID (or empty string for "none")
 *  - `onChange`        callback when a credential is selected
 *  - `typeFilter`      optional predicate on `GatewayDownstreamCredential.type` to narrow options
 *                      (e.g. `t => t === 'BASIC'` for DOWNSTREAM_BASIC_AUTH filters)
 *  - `label` / `hint` / `optional`  forwarded to the wrapping <Field>
 *  - `noneLabel`       label for the "None" placeholder option
 *
 * Created by P-08: Downstream Credential Picker for Filter Forms.
 */
import { useQuery } from '@tanstack/react-query'
import { gatewayApi } from '../../api/gatewayApi'
import { Select } from '../../components/ui/Select'
import type { GatewayDownstreamCredential } from '../../types'

interface DownstreamCredentialPickerProps {
  value: string
  onChange: (credentialId: string, credential?: GatewayDownstreamCredential) => void
  /** Filter the credential list by type. Receives the credential `type` string and returns true to include. */
  typeFilter?: (type: GatewayDownstreamCredential['type']) => boolean
  label: string
  hint?: string
  optional?: boolean
  noneLabel?: string
  noneDescription?: string
  placeholder?: string
}

export function DownstreamCredentialPicker({
  value,
  onChange,
  typeFilter,
  label,
  hint,
  optional,
  noneLabel = 'None — enter credentials manually',
  noneDescription = 'Type credentials in the fields below',
  placeholder,
}: DownstreamCredentialPickerProps) {
  const { data: credentials = [], isLoading } = useQuery({
    queryKey: ['gateway', 'downstream-credentials'],
    queryFn: () => gatewayApi.getDownstreamCredentials(),
    staleTime: 30_000,
  })

  const filtered = credentials.filter((c) => {
    if (!c.enabled) return false
    if (typeFilter && !typeFilter(c.type)) return false
    return true
  })

  const options = [
    { value: '', label: noneLabel, description: noneDescription },
    ...filtered.map((c) => ({
      value: c.id,
      label: c.name,
      description: `${c.type}${c.description ? ` · ${c.description}` : ''}`,
    })),
  ]

  // If the current value isn't in the available options (e.g. removed/disabled credential), show it
  const hasCurrentValue = value && !filtered.some((c) => c.id === value)

  const handleChange = (credentialId: string) => {
    const credential = credentials.find((c) => c.id === credentialId)
    onChange(credentialId, credential)
  }

  return (
    <Field label={label} hint={hint} optional={optional}>
      <Select
        value={value}
        onChange={handleChange}
        options={
          hasCurrentValue
            ? [{ value, label: value, description: '(credential not found — may have been removed)' }, ...options]
            : options
        }
        placeholder={isLoading ? 'Loading credentials…' : (placeholder ?? 'Select a Downstream Credential…')}
        disabled={isLoading}
        searchable
      />
      {value && !isLoading && (
        <div className="flex items-center gap-1.5 mt-1.5">
          {filtered.some((c) => c.id === value) ? (
            <span className="text-[10px] text-emerald-400/80 flex items-center gap-1">
              <span>✓</span> Resolved from gateway config
            </span>
          ) : (
            <span className="text-[10px] text-amber-400/80 flex items-center gap-1">
              <span>⚠</span> Credential not found in current gateway config
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
