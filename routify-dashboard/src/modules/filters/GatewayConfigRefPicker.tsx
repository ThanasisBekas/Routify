/**
 * GatewayConfigRefPicker — lets the user link a filter definition to a gateway config entry.
 *
 * When a filter type is known to need gateway-level configuration (e.g. AUTH_BASIC links to
 * an Auth Provider, RATE_LIMIT_TOKEN_BUCKET links to a Rate Limit Policy), this component
 * renders a dropdown of matching gateway config entries plus a "Configure in Gateway" link.
 */
import { useState } from 'react'
import { useQuery } from '@tanstack/react-query'
import {
  Link, ChevronDown, X, ExternalLink, CheckCircle, AlertTriangle, Plus,
} from 'lucide-react'
import { gatewayApi } from '../../api/gatewayApi'
import { useAuthStore } from '../../store/authStore'
import type {
  FilterType, GatewayConfig, GatewayConfigRef, GatewayAuthProvider,
  GatewayRateLimitPolicy, CertGroupDto, GatewayCertificateSource,
} from '../../types'
import { FILTER_TYPES_WITH_GATEWAY_REF } from '../../types'
import { cn } from '../../lib/utils'

// ─── Gateway entry types understood by this picker ────────────────────────────

type PickerEntry = {
  id: string
  name: string
  subtitle?: string
  type?: string
  enabled: boolean
}

function extractEntries(
  refType: string,
  config: GatewayConfig,
  vaultCertGroups?: CertGroupDto[],
): PickerEntry[] {
  switch (refType) {
    case 'AUTH_PROVIDER':
      return (config.authProviders ?? []).map((p: GatewayAuthProvider) => ({
        id: p.id,
        name: p.name,
        subtitle: p.type,
        type: p.type,
        enabled: p.enabled,
      }))
    case 'RATE_LIMIT_POLICY':
      return (config.rateLimitPolicies ?? []).map((p: GatewayRateLimitPolicy) => ({
        id: p.id,
        name: p.name,
        subtitle: `${p.algorithm} · ${p.keyResolver} · ${p.replenishRate} req/s`,
        type: p.algorithm,
        enabled: p.enabled,
      }))
    case 'CIRCUIT_BREAKER_DEFAULTS':
      // Only one global set of defaults
      return [{
        id: '__circuit_breaker_defaults__',
        name: 'Circuit Breaker Defaults',
        subtitle: `Failure rate: ${config.circuitBreakerDefaults?.failureRateThreshold ?? '?'}% · Window: ${config.circuitBreakerDefaults?.slidingWindowSize ?? '?'}`,
        enabled: true,
      }]
    case 'RESILIENCE_DEFAULTS':
      return [{
        id: '__resilience_defaults__',
        name: 'Resilience Defaults',
        subtitle: `Retry: ${config.resilienceDefaults?.retryMaxAttempts ?? '?'} attempts · Timeout: ${config.resilienceDefaults?.timeoutDuration ?? '?'}`,
        enabled: true,
      }]
    case 'TLS_SOURCE':
      return (config.tlsConfig?.fileSources ?? []).map((s: GatewayCertificateSource) => ({
        id: s.logicalId,
        name: s.logicalId,
        subtitle: s.certificatePath,
        type: s.status ?? 'UNKNOWN',
        enabled: true,
      }))
    case 'VAULT_CERT':
      return (vaultCertGroups ?? []).map((g: CertGroupDto) => ({
        id: g.logicalId,
        name: g.alias ?? g.logicalId,
        subtitle: `logicalId: ${g.logicalId} · ${g.memberCount} cert${g.memberCount !== 1 ? 's' : ''} · ${g.expiryHealthStatus}`,
        type: g.expiryHealthStatus,
        enabled: g.status === 'ACTIVE',
      }))
    case 'DOWNSTREAM_CREDENTIAL':
      return ((config as unknown as Record<string, unknown>).downstreamCredentials as Record<string, unknown>[] ?? []).map((c: Record<string, unknown>) => ({
        id: c.id as string,
        name: c.name as string,
        subtitle: `${c.type}${c.username ? ` · ${c.username}` : ''}`,
        type: c.type as string,
        enabled: c.enabled as boolean,
      }))
    default:
      return []
  }
}

function refTypeLabel(refType: string): string {
  switch (refType) {
    case 'AUTH_PROVIDER':           return 'Auth Provider'
    case 'RATE_LIMIT_POLICY':       return 'Rate Limit Policy'
    case 'CIRCUIT_BREAKER_DEFAULTS': return 'Circuit Breaker Defaults'
    case 'RESILIENCE_DEFAULTS':     return 'Resilience Defaults'
    case 'TLS_SOURCE':              return 'TLS Certificate Source'
    case 'VAULT_CERT':              return 'Cert Group'
    case 'DOWNSTREAM_CREDENTIAL':   return 'Downstream Credential'
    default:                        return refType
  }
}

function filterTypeDescription(filterType: FilterType, refType: string): string {
  switch (refType) {
    case 'AUTH_PROVIDER':
      return `Link this ${filterType.replace(/_/g, ' ')} filter to a configured Auth Provider in the gateway. The provider's credentials and settings will be used at runtime.`
    case 'RATE_LIMIT_POLICY':
      return 'Link this rate limiter to a gateway-level Rate Limit Policy. The policy defines the algorithm, thresholds, and key resolver used at runtime.'
    case 'CIRCUIT_BREAKER_DEFAULTS':
      return 'Link this circuit breaker to the gateway-level defaults. When linked, the gateway defaults apply unless overridden in the filter config above.'
    case 'RESILIENCE_DEFAULTS':
      return 'Link this filter to the gateway-level resilience defaults. The retry/timeout settings defined there will be applied at runtime.'
    case 'TLS_SOURCE':
      return 'Link this cert rotation filter to a TLS certificate source configured in the gateway.'
    case 'VAULT_CERT':
      return 'Link this filter to a Certificate Group. The group\'s stable logicalId is what the gateway registry and cert-vault filters bind to at runtime. Only active groups are shown.'
    case 'DOWNSTREAM_CREDENTIAL':
      return 'Link this filter to a downstream credential stored in the gateway. Credentials are injected into upstream requests at runtime.'
    default:
      return 'Link this filter to a gateway configuration entry.'
  }
}

// ─── Main component ───────────────────────────────────────────────────────────

interface Props {
  filterType: FilterType
  value: GatewayConfigRef | null
  onChange: (ref: GatewayConfigRef | null) => void
  /** Called when user clicks "Go to Gateway → Auth Providers" etc. */
  onNavigateToGateway?: () => void
}

export default function GatewayConfigRefPicker({
  filterType, value, onChange, onNavigateToGateway,
}: Props) {
  const refType = FILTER_TYPES_WITH_GATEWAY_REF[filterType]
  const [open, setOpen] = useState(false)
  const tenantId = useAuthStore(s => s.user?.tenantId) ?? ''

  const { data: gatewayConfig, isLoading: isLoadingConfig } = useQuery({
    queryKey: ['gateway-config'],
    queryFn: gatewayApi.getConfig,
  })

  const { data: vaultCertGroups, isLoading: isLoadingVault } = useQuery({
    queryKey: ['gateway-vault-cert-groups', tenantId],
    queryFn: () => gatewayApi.getVaultCertGroups(tenantId),
    enabled: refType === 'VAULT_CERT' && !!tenantId,
  })

  // This filter type doesn't need a gateway config ref
  if (!refType) return null

  const isLoading = isLoadingConfig || isLoadingVault
  const entries = gatewayConfig ? extractEntries(refType, gatewayConfig, vaultCertGroups?.content) : []
  const selectedEntry = value ? entries.find(e => e.id === value.refId) : null
  const isLinked = !!value

  return (
    <div className="rounded-xl border border-dashed border-indigo-500/30 bg-indigo-500/5 p-4 space-y-3">
      {/* Header */}
      <div className="flex items-start justify-between gap-3">
        <div className="flex items-start gap-2.5">
          <div className="w-7 h-7 rounded-lg bg-indigo-500/20 border border-indigo-500/30 flex items-center justify-center shrink-0 mt-0.5">
            <Link className="w-3.5 h-3.5 text-indigo-400" />
          </div>
          <div>
            <div className="flex items-center gap-2">
              <span className="text-xs font-bold text-indigo-300 uppercase tracking-wider">
                Gateway Config Link
              </span>
              <span className="text-[10px] text-gray-500 lowercase font-normal normal-case">
                optional
              </span>
            </div>
            <p className="text-[11px] text-gray-500 mt-0.5 leading-relaxed max-w-sm">
              {filterTypeDescription(filterType, refType)}
            </p>
          </div>
        </div>

        {isLinked && (
          <button
            type="button"
            onClick={() => onChange(null)}
            className="p-1.5 text-gray-500 hover:text-red-400 hover:bg-red-400/10 rounded-lg transition-colors shrink-0"
            title="Unlink from gateway config"
          >
            <X className="w-3.5 h-3.5" />
          </button>
        )}
      </div>

      {/* Current link status */}
      {isLinked ? (
        <div className="flex items-center gap-2.5 px-3 py-2 rounded-lg bg-indigo-500/10 border border-indigo-500/20">
          <CheckCircle className="w-4 h-4 text-indigo-400 shrink-0" />
          <div className="min-w-0">
            <div className="text-sm font-medium text-indigo-200 truncate">
              {selectedEntry?.name ?? value.refName ?? value.refId}
            </div>
            {selectedEntry?.subtitle && (
              <div className="text-[11px] text-indigo-400/70 truncate mt-0.5 font-mono">
                {selectedEntry.subtitle}
              </div>
            )}
          </div>
          {selectedEntry && !selectedEntry.enabled && (
            <span className="ml-auto flex items-center gap-1 text-[10px] text-yellow-400 bg-yellow-400/10 px-1.5 py-0.5 rounded-full shrink-0">
              <AlertTriangle className="w-3 h-3" />
              Disabled
            </span>
          )}
          {!selectedEntry && !isLoading && (
            <span className="ml-auto flex items-center gap-1 text-[10px] text-red-400 bg-red-400/10 px-1.5 py-0.5 rounded-full shrink-0">
              <AlertTriangle className="w-3 h-3" />
              Not found
            </span>
          )}
        </div>
      ) : (
        <div className="text-[11px] text-gray-600 px-1">
          No gateway config linked — the filter uses only the configuration fields above.
        </div>
      )}

      {/* Picker dropdown */}
      <div className="flex items-center gap-2">
        <div className="relative flex-1">
          <button
            type="button"
            onClick={() => setOpen(o => !o)}
            disabled={isLoading}
            className={cn(
              'w-full flex items-center justify-between gap-2 px-3 py-2 rounded-lg border text-sm transition-all',
              open
                ? 'bg-white/[0.06] border-indigo-500 text-white ring-1 ring-indigo-500/30'
                : 'bg-white/[0.03] border-white/[0.08] text-gray-400 hover:text-white hover:border-white/20',
            )}
          >
            <span className="truncate text-xs">
              {isLoading
                ? 'Loading gateway config…'
                : isLinked
                  ? `Change linked ${refTypeLabel(refType)}`
                  : `Link to a ${refTypeLabel(refType)}…`}
            </span>
            <ChevronDown className={cn('w-3.5 h-3.5 shrink-0 transition-transform', open && 'rotate-180')} />
          </button>

          {open && (
            <>
              <div className="fixed inset-0 z-10" onClick={() => setOpen(false)} />
              <div className="absolute z-20 mt-1 w-full max-h-64 overflow-y-auto rounded-xl bg-[#111318] border border-white/10 shadow-2xl shadow-black/60 animate-fade-in">
                {entries.length === 0 ? (
                  <div className="px-4 py-5 text-center">
                    <p className="text-sm text-gray-400 mb-1">
                      No {refTypeLabel(refType)} entries found
                    </p>
                    <p className="text-xs text-gray-600">
                      Create one in Gateway → {refTypeLabel(refType)}
                    </p>
                  </div>
                ) : (
                  entries.map(entry => (
                    <button
                      key={entry.id}
                      type="button"
                      onClick={() => {
                        onChange({ refType, refId: entry.id, refName: entry.name })
                        setOpen(false)
                      }}
                      className={cn(
                        'w-full flex items-center justify-between gap-3 px-3 py-2.5 text-left hover:bg-white/[0.05] transition-colors',
                        value?.refId === entry.id && 'bg-indigo-500/10',
                      )}
                    >
                      <div className="min-w-0">
                        <div className={cn(
                          'text-sm font-medium truncate',
                          value?.refId === entry.id ? 'text-indigo-300' : 'text-gray-200',
                        )}>
                          {entry.name}
                        </div>
                        {entry.subtitle && (
                          <div className="text-[11px] text-gray-500 font-mono truncate mt-0.5">
                            {entry.subtitle}
                          </div>
                        )}
                      </div>
                      <div className="flex items-center gap-2 shrink-0">
                        {entry.type && (
                          <span className="text-[10px] bg-white/[0.06] text-gray-400 px-1.5 py-0.5 rounded font-mono">
                            {entry.type}
                          </span>
                        )}
                        {!entry.enabled && (
                          <span className="text-[10px] text-yellow-500">disabled</span>
                        )}
                        {value?.refId === entry.id && (
                          <CheckCircle className="w-3.5 h-3.5 text-indigo-400" />
                        )}
                      </div>
                    </button>
                  ))
                )}
              </div>
            </>
          )}
        </div>

        {/* Navigate to gateway config button */}
        <button
          type="button"
          onClick={onNavigateToGateway}
          className="flex items-center gap-1.5 px-3 py-2 text-xs text-indigo-400 hover:text-indigo-300 bg-white/[0.03] hover:bg-white/[0.06] border border-white/8 hover:border-indigo-500/30 rounded-lg transition-all whitespace-nowrap"
          title={`Manage ${refTypeLabel(refType)} in Gateway settings`}
        >
          <Plus className="w-3 h-3" />
          New
          <ExternalLink className="w-3 h-3 opacity-60" />
        </button>
      </div>
    </div>
  )
}

