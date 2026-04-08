/**
 * GatewayConfigRefPanel — unified "Link to Gateway Config" toggle + ref-type picker
 * for filter definition forms.
 *
 * When a filter type supports gateway config references, this panel provides:
 *  1. A toggle to enable/disable the link
 *  2. A ref-type-specific picker (auth provider, rate-limit policy, cert vault, etc.)
 *  3. A summary of the resolved config entry with its key fields
 *
 * The panel writes a `gatewayConfigRef` object (`{ refType, refId, refName }`) that
 * the admin-api forwards to the route-service Kafka command. At gateway build time,
 * `GatewayConfigRefResolver` merges the referenced entry's fields into the filter config.
 *
 * Created by P-27: Filter Config Form Gateway Config Ref UX.
 */
import { useQuery } from '@tanstack/react-query'
import { Link2, Link2Off, Shield, Gauge, KeyRound, ShieldCheck, Zap, ServerCog } from 'lucide-react'
import { cn } from '../../lib/utils'
import { gatewayApi } from '../../api/gatewayApi'
import { certVaultApi } from '../../api/certVaultApi'
import { Select } from '../../components/ui/Select'
import type { FilterType, GatewayConfigRefDto } from '../../types'

// ─── Ref type mapping per filter type ─────────────────────────────────────────

interface RefTypeSpec {
  refType: string
  label: string
  description: string
  icon: React.ElementType
  /** Whether this ref type targets a single global entry (no picker needed). */
  singleton?: boolean
}

/**
 * Maps each filter type to the gateway config ref type(s) it supports.
 * Filter types not in this map do not support gateway config refs.
 */
const FILTER_REF_MAP: Partial<Record<FilterType, RefTypeSpec>> = {
  // Auth providers
  AUTH_BASIC: {
    refType: 'AUTH_PROVIDER',
    label: 'Auth Provider',
    description: 'Import credentials from a BASIC auth provider in Gateway Config',
    icon: Shield,
  },
  AUTH_OAUTH2: {
    refType: 'AUTH_PROVIDER',
    label: 'Auth Provider',
    description: 'Import OAuth2 introspection config from a gateway auth provider',
    icon: Shield,
  },
  AUTH_MTLS: {
    refType: 'MTLS_CLIENT_MAPPING',
    label: 'mTLS Auth Provider',
    description: 'Import client-certificate mappings from an MTLS auth provider',
    icon: ShieldCheck,
  },
  AUTH_CLIENT_ID: {
    refType: 'CLIENT_ID_MAPPING',
    label: 'Client ID Auth Provider',
    description: 'Import client-ID entries from a CLIENT_ID auth provider',
    icon: ShieldCheck,
  },
  // Cert vault
  AUTH_CERT_VAULT: {
    refType: 'VAULT_CERT',
    label: 'Certificate Group',
    description: 'Link to a Certificate Group logical ID from the Cert Vault',
    icon: KeyRound,
  },
  CERT_ROTATION: {
    refType: 'VAULT_CERT',
    label: 'Certificate Group',
    description: 'Link to a Certificate Group logical ID from the Cert Vault',
    icon: KeyRound,
  },
  CERT_VAULT_EXPIRY_CHECK: {
    refType: 'VAULT_CERT',
    label: 'Certificate Group',
    description: 'Link to a Certificate Group logical ID from the Cert Vault',
    icon: KeyRound,
  },
  // Rate limiting
  RATE_LIMIT_FIXED_WINDOW: {
    refType: 'RATE_LIMIT_POLICY',
    label: 'Rate Limit Policy',
    description: 'Import rate-limit values from a policy in Gateway Config',
    icon: Gauge,
  },
  RATE_LIMIT_SLIDING_WINDOW: {
    refType: 'RATE_LIMIT_POLICY',
    label: 'Rate Limit Policy',
    description: 'Import rate-limit values from a policy in Gateway Config',
    icon: Gauge,
  },
  // Downstream auth
  DOWNSTREAM_BASIC_AUTH: {
    refType: 'DOWNSTREAM_CREDENTIAL',
    label: 'Downstream Credential',
    description: 'Import credentials from a downstream credential entry in Gateway Config',
    icon: KeyRound,
  },
  DOWNSTREAM_BEARER_CC: {
    refType: 'DOWNSTREAM_OAUTH2_PROVIDER',
    label: 'Downstream OAuth2 Provider',
    description: 'Import OAuth2 client-credentials config from a downstream provider',
    icon: KeyRound,
  },
  // Resilience (singletons — no picker, auto-link to global defaults)
  CIRCUIT_BREAKER_V2: {
    refType: 'CIRCUIT_BREAKER_DEFAULTS',
    label: 'Circuit Breaker Defaults',
    description: 'Import threshold and window defaults from Gateway Config',
    icon: Zap,
    singleton: true,
  },
  RETRY_V2: {
    refType: 'RESILIENCE_DEFAULTS',
    label: 'Resilience Defaults',
    description: 'Import retry/timeout defaults from Gateway Config',
    icon: ServerCog,
    singleton: true,
  },
  TIMEOUT: {
    refType: 'RESILIENCE_DEFAULTS',
    label: 'Resilience Defaults',
    description: 'Import timeout defaults from Gateway Config',
    icon: ServerCog,
    singleton: true,
  },
}

// eslint-disable-next-line react-refresh/only-export-components
/** Returns whether a filter type supports gateway config refs. */
export function supportsConfigRef(filterType: FilterType): boolean {
  return filterType in FILTER_REF_MAP
}

// eslint-disable-next-line react-refresh/only-export-components
/** Returns the ref type spec for a filter type, or undefined if not supported. */
export function getRefSpec(filterType: FilterType): RefTypeSpec | undefined {
  return FILTER_REF_MAP[filterType]
}

// ─── Panel component ──────────────────────────────────────────────────────────

interface GatewayConfigRefPanelProps {
  filterType: FilterType
  value: GatewayConfigRefDto | null | undefined
  onChange: (ref: GatewayConfigRefDto | null) => void
}

export function GatewayConfigRefPanel({ filterType, value, onChange }: GatewayConfigRefPanelProps) {
  const spec = FILTER_REF_MAP[filterType]
  if (!spec) return null

  const isLinked = !!value?.refType

  const handleToggle = () => {
    if (isLinked) {
      onChange(null)
    } else if (spec.singleton) {
      // Auto-link singletons immediately (no picker needed)
      onChange({ refType: spec.refType, refId: '_global', refName: spec.label })
    }
    // For non-singletons, the toggle enables the picker; the actual ref is set when a value is selected
    if (!isLinked && !spec.singleton) {
      // Set refType but leave refId empty — picker will fill it in
      onChange({ refType: spec.refType, refId: '', refName: '' })
    }
  }

  return (
    <div className="space-y-3">
      {/* Toggle button */}
      <button
        type="button"
        onClick={handleToggle}
        className={cn(
          'w-full flex items-center gap-3 px-4 py-3 rounded-xl border text-left transition-all',
          isLinked
            ? 'bg-indigo-500/8 border-indigo-500/25 hover:border-indigo-500/40'
            : 'bg-white/2 border-white/8 hover:border-white/15',
        )}
      >
        <div
          className={cn(
            'w-8 h-8 rounded-lg flex items-center justify-center shrink-0 transition-colors',
            isLinked ? 'bg-indigo-500/20 text-indigo-400' : 'bg-white/5 text-gray-500',
          )}
        >
          {isLinked ? <Link2 className="w-4 h-4" /> : <Link2Off className="w-4 h-4" />}
        </div>
        <div className="flex-1 min-w-0">
          <div className={cn('text-sm font-medium', isLinked ? 'text-indigo-300' : 'text-gray-300')}>
            {isLinked ? `Linked to ${spec.label}` : `Link to ${spec.label}`}
          </div>
          <div className="text-[11px] text-gray-500 mt-0.5">{spec.description}</div>
        </div>
        <div
          className={cn(
            'w-9 h-5 rounded-full flex items-center transition-colors shrink-0',
            isLinked ? 'bg-indigo-500 justify-end' : 'bg-white/10 justify-start',
          )}
        >
          <div className="w-4 h-4 rounded-full bg-white mx-0.5 shadow-sm" />
        </div>
      </button>

      {/* Picker — shown when linked and not a singleton */}
      {isLinked && !spec.singleton && (
        <RefPicker
          filterType={filterType}
          spec={spec}
          refId={value?.refId ?? ''}
          onSelect={(refId, refName) => onChange({ refType: spec.refType, refId, refName: refName ?? '' })}
        />
      )}

      {/* Singleton confirmation */}
      {isLinked && spec.singleton && (
        <div className="flex items-start gap-2 rounded-lg border px-3 py-2.5 text-[11px] leading-relaxed bg-indigo-500/6 border-indigo-500/20 text-indigo-300/80">
          <span className="mt-0.5 shrink-0">✓</span>
          <span>
            Linked to <strong>{spec.label}</strong> — the gateway will merge the global{' '}
            {spec.refType === 'CIRCUIT_BREAKER_DEFAULTS' ? 'circuit breaker' : 'resilience'} defaults from Gateway Config
            into this filter's configuration. Per-filter values override the defaults.
          </span>
        </div>
      )}
    </div>
  )
}

// ─── Ref-type-specific pickers ────────────────────────────────────────────────

function RefPicker({
  filterType,
  spec,
  refId,
  onSelect,
}: {
  filterType: FilterType
  spec: RefTypeSpec
  refId: string
  onSelect: (refId: string, refName?: string) => void
}) {
  switch (spec.refType) {
    case 'AUTH_PROVIDER':
      return <AuthProviderRefPicker filterType={filterType} refId={refId} onSelect={onSelect} />
    case 'MTLS_CLIENT_MAPPING':
      return <AuthProviderRefPicker filterType={filterType} refId={refId} onSelect={onSelect} typeFilter="MTLS" />
    case 'CLIENT_ID_MAPPING':
      return <AuthProviderRefPicker filterType={filterType} refId={refId} onSelect={onSelect} typeFilter="CLIENT_ID" />
    case 'RATE_LIMIT_POLICY':
      return <RateLimitPolicyRefPicker filterType={filterType} refId={refId} onSelect={onSelect} />
    case 'VAULT_CERT':
      return <VaultCertRefPicker refId={refId} onSelect={onSelect} />
    case 'DOWNSTREAM_CREDENTIAL':
      return <DownstreamCredentialRefPicker refId={refId} onSelect={onSelect} />
    case 'DOWNSTREAM_OAUTH2_PROVIDER':
      return <DownstreamOAuth2ProviderRefPicker refId={refId} onSelect={onSelect} />
    default:
      return null
  }
}

// ── Auth Provider picker ──────────────────────────────────────────────────────

function AuthProviderRefPicker({
  filterType,
  refId,
  onSelect,
  typeFilter,
}: {
  filterType: FilterType
  refId: string
  onSelect: (refId: string, refName?: string) => void
  typeFilter?: string
}) {
  const { data: providers = [], isLoading } = useQuery({
    queryKey: ['gateway', 'auth-providers'],
    queryFn: () => gatewayApi.getAuthProviders(),
    staleTime: 30_000,
  })

  // Determine the compatible auth provider types for this filter
  const compatibleTypes = typeFilter
    ? [typeFilter]
    : filterType === 'AUTH_BASIC'
      ? ['BASIC']
      : filterType === 'AUTH_OAUTH2'
        ? ['OAUTH2_CLIENT_CREDENTIALS', 'OAUTH2_PASSWORD', 'OAUTH2_INTROSPECT']
        : []

  const filtered = providers.filter(
    (p) => p.enabled && compatibleTypes.some((t) => p.type === t || p.type.startsWith(t)),
  )

  const options = [
    { value: '', label: '— Select a provider…', description: 'Choose from gateway-managed auth providers' },
    ...filtered.map((p) => ({
      value: p.id,
      label: p.name,
      description: `${p.type}${p.uri ? ` · ${p.uri}` : ''}`,
    })),
  ]

  const resolved = filtered.some((p) => p.id === refId)

  return (
    <div className="space-y-2">
      <Select
        value={refId}
        onChange={(id) => {
          const provider = providers.find((p) => p.id === id)
          onSelect(id, provider?.name)
        }}
        options={
          refId && !resolved
            ? [{ value: refId, label: refId, description: '(provider not found)' }, ...options]
            : options
        }
        placeholder={isLoading ? 'Loading providers…' : 'Select an Auth Provider…'}
        disabled={isLoading}
        searchable
      />
      <RefResolutionStatus resolved={resolved} refId={refId} isLoading={isLoading} entityName="provider" />
    </div>
  )
}

// ── Rate Limit Policy picker ──────────────────────────────────────────────────

function RateLimitPolicyRefPicker({
  filterType,
  refId,
  onSelect,
}: {
  filterType: FilterType
  refId: string
  onSelect: (refId: string, refName?: string) => void
}) {
  const { data: policies = [], isLoading } = useQuery({
    queryKey: ['gateway', 'rate-limit-policies'],
    queryFn: () => gatewayApi.getRateLimitPolicies(),
    staleTime: 30_000,
  })

  const algoFilter = filterType === 'RATE_LIMIT_FIXED_WINDOW' ? 'FIXED_WINDOW' : 'SLIDING_WINDOW'
  const filtered = policies.filter((p) => p.enabled && p.algorithm === algoFilter)

  const formatWindow = (ms: number) => {
    if (ms >= 3_600_000) return `${ms / 3_600_000}h`
    if (ms >= 60_000) return `${ms / 60_000}min`
    if (ms >= 1_000) return `${ms / 1_000}s`
    return `${ms}ms`
  }

  const options = [
    { value: '', label: '— Select a policy…', description: 'Choose from gateway-managed rate limit policies' },
    ...filtered.map((p) => ({
      value: p.id,
      label: p.name,
      description: `${p.algorithm} · ${p.replenishRate} req/${formatWindow(p.windowMs)} · key: ${p.keyResolver}`,
    })),
  ]

  const resolved = filtered.some((p) => p.id === refId)

  return (
    <div className="space-y-2">
      <Select
        value={refId}
        onChange={(id) => {
          const policy = policies.find((p) => p.id === id)
          onSelect(id, policy?.name)
        }}
        options={
          refId && !resolved
            ? [{ value: refId, label: refId, description: '(policy not found)' }, ...options]
            : options
        }
        placeholder={isLoading ? 'Loading policies…' : 'Select a Rate Limit Policy…'}
        disabled={isLoading}
        searchable
      />
      <RefResolutionStatus resolved={resolved} refId={refId} isLoading={isLoading} entityName="policy" />
    </div>
  )
}

// ── Vault Cert picker ─────────────────────────────────────────────────────────

function VaultCertRefPicker({
  refId,
  onSelect,
}: {
  refId: string
  onSelect: (refId: string, refName?: string) => void
}) {
  const { data: logicalIds = [], isLoading } = useQuery({
    queryKey: ['certs', 'logical-ids'],
    queryFn: () => certVaultApi.listLogicalIds(),
    staleTime: 30_000,
  })

  const activeEntries = logicalIds.filter((e) => e.status === 'ACTIVE')

  const options = [
    { value: '', label: '— Select a certificate group…', description: 'Choose from active cert vault groups' },
    ...activeEntries.map((e) => ({
      value: e.logicalId,
      label: e.logicalId,
      description: e.alias ?? '',
    })),
  ]

  const resolved = activeEntries.some((e) => e.logicalId === refId)

  return (
    <div className="space-y-2">
      <Select
        value={refId}
        onChange={(id) => {
          const entry = logicalIds.find((e) => e.logicalId === id)
          onSelect(id, entry?.alias ?? id)
        }}
        options={
          refId && !resolved
            ? [{ value: refId, label: refId, description: '(not found in cert vault)' }, ...options]
            : options
        }
        placeholder={isLoading ? 'Loading cert groups…' : 'Select a Certificate Group…'}
        disabled={isLoading}
        searchable
      />
      <RefResolutionStatus resolved={resolved} refId={refId} isLoading={isLoading} entityName="cert group" />
    </div>
  )
}

// ── Downstream Credential picker ──────────────────────────────────────────────

function DownstreamCredentialRefPicker({
  refId,
  onSelect,
}: {
  refId: string
  onSelect: (refId: string, refName?: string) => void
}) {
  const { data: credentials = [], isLoading } = useQuery({
    queryKey: ['gateway', 'downstream-credentials'],
    queryFn: () => gatewayApi.getDownstreamCredentials(),
    staleTime: 30_000,
  })

  const filtered = credentials.filter((c) => c.enabled && c.type === 'BASIC')

  const options = [
    { value: '', label: '— Select a credential…', description: 'Choose from gateway-managed downstream credentials' },
    ...filtered.map((c) => ({
      value: c.id,
      label: c.name,
      description: `${c.type}${c.description ? ` · ${c.description}` : ''}`,
    })),
  ]

  const resolved = filtered.some((c) => c.id === refId)

  return (
    <div className="space-y-2">
      <Select
        value={refId}
        onChange={(id) => {
          const cred = credentials.find((c) => c.id === id)
          onSelect(id, cred?.name)
        }}
        options={
          refId && !resolved
            ? [{ value: refId, label: refId, description: '(credential not found)' }, ...options]
            : options
        }
        placeholder={isLoading ? 'Loading credentials…' : 'Select a Downstream Credential…'}
        disabled={isLoading}
        searchable
      />
      <RefResolutionStatus resolved={resolved} refId={refId} isLoading={isLoading} entityName="credential" />
    </div>
  )
}

// ── Downstream OAuth2 Provider picker ─────────────────────────────────────────

function DownstreamOAuth2ProviderRefPicker({
  refId,
  onSelect,
}: {
  refId: string
  onSelect: (refId: string, refName?: string) => void
}) {
  const { data: providers = [], isLoading } = useQuery({
    queryKey: ['gateway', 'auth-providers'],
    queryFn: () => gatewayApi.getAuthProviders(),
    staleTime: 30_000,
  })

  // For DOWNSTREAM_BEARER_CC, show OAuth2 client-credentials providers
  const filtered = providers.filter(
    (p) => p.enabled && (p.type === 'OAUTH2_CLIENT_CREDENTIALS' || p.type.startsWith('OAUTH2')),
  )

  const options = [
    { value: '', label: '— Select a provider…', description: 'Choose from gateway-managed OAuth2 providers' },
    ...filtered.map((p) => ({
      value: p.id,
      label: p.name,
      description: `${p.type}${p.uri ? ` · ${p.uri}` : ''}`,
    })),
  ]

  const resolved = filtered.some((p) => p.id === refId)

  return (
    <div className="space-y-2">
      <Select
        value={refId}
        onChange={(id) => {
          const provider = providers.find((p) => p.id === id)
          onSelect(id, provider?.name)
        }}
        options={
          refId && !resolved
            ? [{ value: refId, label: refId, description: '(provider not found)' }, ...options]
            : options
        }
        placeholder={isLoading ? 'Loading providers…' : 'Select an OAuth2 Provider…'}
        disabled={isLoading}
        searchable
      />
      <RefResolutionStatus resolved={resolved} refId={refId} isLoading={isLoading} entityName="provider" />
    </div>
  )
}

// ── Shared resolution status indicator ────────────────────────────────────────

function RefResolutionStatus({
  resolved,
  refId,
  isLoading,
  entityName,
}: {
  resolved: boolean
  refId: string
  isLoading: boolean
  entityName: string
}) {
  if (!refId || isLoading) return null

  return (
    <div className="flex items-center gap-1.5">
      {resolved ? (
        <span className="text-[10px] text-emerald-400/80 flex items-center gap-1">
          <span>✓</span> Resolved — gateway will merge {entityName} values into filter config
        </span>
      ) : (
        <span className="text-[10px] text-amber-400/80 flex items-center gap-1">
          <span>⚠</span> {entityName.charAt(0).toUpperCase() + entityName.slice(1)} not found — may be created later
          (eventual consistency)
        </span>
      )}
    </div>
  )
}

