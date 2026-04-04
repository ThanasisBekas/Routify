/**
 * CertVaultGroupPicker — lets the user bind a cert filter definition to a
 * Certificate Group stored in the Cert Vault.
 *
 * ARCHITECTURAL RULE:
 *   This component is ONLY rendered for cert filter types:
 *     `AUTH_CERT_VAULT` | `CERT_ROTATION` | `CERT_VAULT_EXPIRY_CHECK`
 *
 *   Standard filters are self-contained and must NOT import configuration
 *   from the API gateway config. The Cert Vault is the SOLE allowed external
 *   source, and only for the three cert filter types above.
 *
 * What this does:
 *   - Fetches active Certificate Groups directly from `certVaultApi` (never
 *     through `gatewayApi` or any gateway config endpoint).
 *   - Renders a searchable dropdown of groups keyed by their stable `logicalId`.
 *   - Syncs the selected `logicalId` back into the filter's inline config so the
 *     gateway registry filter binds correctly at runtime without a gateway restart.
 */
import { useState } from 'react'
import { useQuery } from '@tanstack/react-query'
import { ShieldCheck, ChevronDown, X, AlertTriangle, CheckCircle, Plus } from 'lucide-react'
import { certVaultApi } from '../../api/certVaultApi'
import { useAuthStore } from '../../store/authStore'
import type { FilterType, GatewayConfigRef, CertGroupDto } from '../../types'
import { FILTER_TYPES_WITH_CERT_VAULT_REF } from '../../types'
import { cn } from '../../lib/utils'

// ─── Cert-filter-type human labels ────────────────────────────────────────────

function certFilterLabel(filterType: FilterType): string {
  switch (filterType) {
    case 'AUTH_CERT_VAULT':         return 'Cert Vault Auth'
    case 'CERT_ROTATION':           return 'Cert Rotation'
    case 'CERT_VAULT_EXPIRY_CHECK': return 'Cert Vault Expiry Check'
    default:                        return filterType
  }
}

function certFilterDescription(filterType: FilterType): string {
  switch (filterType) {
    case 'AUTH_CERT_VAULT':
      return 'Bind this auth filter to a Certificate Group. The group\'s stable logicalId is used by the gateway registry to look up active PEM certificates at request time. Only ACTIVE groups are shown.'
    case 'CERT_ROTATION':
      return 'Bind this rotation filter to a Certificate Group. Requests carrying a certificate that is revoked or unknown within the group are rejected with 401.'
    case 'CERT_VAULT_EXPIRY_CHECK':
      return 'Bind this expiry-check filter to a Certificate Group. The filter evaluates every active member against the configured warning window on every request.'
    default:
      return 'Bind this filter to an active Certificate Group from the Cert Vault.'
  }
}

// ─── Main component ───────────────────────────────────────────────────────────

interface Props {
  filterType: FilterType
  value: GatewayConfigRef | null
  onChange: (ref: GatewayConfigRef | null) => void
  /** Called when the user clicks "Manage in Cert Vault" */
  onNavigateToCertVault?: () => void
}

export default function CertVaultGroupPicker({
  filterType,
  value,
  onChange,
  onNavigateToCertVault,
}: Props) {
  // This picker is only meaningful for cert filter types — bail out early for all others.
  if (!FILTER_TYPES_WITH_CERT_VAULT_REF.has(filterType)) return null

  return (
    <CertVaultGroupPickerInner
      filterType={filterType}
      value={value}
      onChange={onChange}
      onNavigateToCertVault={onNavigateToCertVault}
    />
  )
}

/**
 * Inner component — the guard above ensures this never renders for standard filters.
 * Separated so the hook call is not conditional.
 */
function CertVaultGroupPickerInner({
  filterType,
  value,
  onChange,
  onNavigateToCertVault,
}: Props) {
  const [open, setOpen] = useState(false)
  const [search, setSearch] = useState('')

  const tenantId = useAuthStore(s => s.user?.tenantId) ?? ''

  // ── Fetch active cert groups directly from the Cert Vault API ────────────────
  // NOTE: We intentionally use `certVaultApi`, NOT `gatewayApi`. Cert Vault is the
  //       sole allowed external config source for cert filters.
  const { data: groups, isLoading } = useQuery({
    queryKey: ['cert-vault-groups-active', tenantId],
    queryFn: () =>
      certVaultApi.listGroups({
        tenantId,
        status:  'ACTIVE',
        page:    0,
        size:    200,
        sortBy:  'logicalId',
        sortDir: 'ASC',
      }),
    enabled: !!tenantId,
    staleTime: 30_000,
  })

  const allGroups: CertGroupDto[] = groups?.content ?? []
  const filteredGroups = search
    ? allGroups.filter(
        g =>
          g.logicalId.toLowerCase().includes(search.toLowerCase()) ||
          (g.alias ?? '').toLowerCase().includes(search.toLowerCase()),
      )
    : allGroups

  const selectedGroup = value ? allGroups.find(g => g.logicalId === value.refId) : null
  const isLinked = !!value

  const handleSelect = (group: CertGroupDto) => {
    onChange({
      refType: 'VAULT_CERT',
      refId:   group.logicalId,
      refName: group.alias ?? group.logicalId,
    })
    setOpen(false)
    setSearch('')
  }

  const handleUnlink = () => {
    onChange(null)
  }

  return (
    <div className="rounded-xl border border-dashed border-sky-500/30 bg-sky-500/5 p-4 space-y-3">
      {/* Header */}
      <div className="flex items-start justify-between gap-3">
        <div className="flex items-start gap-2.5">
          <div className="w-7 h-7 rounded-lg bg-sky-500/20 border border-sky-500/30 flex items-center justify-center shrink-0 mt-0.5">
            <ShieldCheck className="w-3.5 h-3.5 text-sky-400" />
          </div>
          <div>
            <div className="flex items-center gap-2">
              <span className="text-xs font-bold text-sky-300 uppercase tracking-wider">
                Cert Vault — Certificate Group
              </span>
              <span className="text-[10px] text-gray-500 lowercase font-normal normal-case">
                optional
              </span>
            </div>
            <p className="text-[11px] text-gray-500 mt-0.5 leading-relaxed max-w-sm">
              {certFilterDescription(filterType)}
            </p>
          </div>
        </div>

        {isLinked && (
          <button
            type="button"
            onClick={handleUnlink}
            className="p-1.5 text-gray-500 hover:text-red-400 hover:bg-red-400/10 rounded-lg transition-colors shrink-0"
            title="Unlink from Certificate Group"
          >
            <X className="w-3.5 h-3.5" />
          </button>
        )}
      </div>

      {/* Architecture note — reassures that this is cert-vault-only */}
      <div className="flex items-start gap-2 px-3 py-2 rounded-lg bg-sky-500/[0.06] border border-sky-500/[0.15] text-[10px] text-sky-400/80 leading-relaxed">
        <ShieldCheck className="w-3 h-3 shrink-0 mt-0.5 text-sky-500" />
        <span>
          Configuration sourced exclusively from the{' '}
          <strong className="text-sky-300">Cert Vault</strong> — no gateway config dependency.
          The linked group's <code className="font-mono text-sky-300">logicalId</code> is
          synced into the filter config and resolved at runtime by the gateway registry.
        </span>
      </div>

      {/* Current link status */}
      {isLinked ? (
        <div className="flex items-center gap-2.5 px-3 py-2 rounded-lg bg-sky-500/10 border border-sky-500/20">
          <CheckCircle className="w-4 h-4 text-sky-400 shrink-0" />
          <div className="min-w-0">
            <div className="text-sm font-medium text-sky-200 truncate">
              {selectedGroup?.alias ?? value.refName ?? value.refId}
            </div>
            {selectedGroup && (
              <div className="text-[11px] text-sky-400/70 truncate mt-0.5 font-mono">
                logicalId: {selectedGroup.logicalId} · {selectedGroup.memberCount} cert{selectedGroup.memberCount !== 1 ? 's' : ''} · {selectedGroup.expiryHealthStatus}
              </div>
            )}
          </div>
          {selectedGroup && selectedGroup.status !== 'ACTIVE' && (
            <span className="ml-auto flex items-center gap-1 text-[10px] text-yellow-400 bg-yellow-400/10 px-1.5 py-0.5 rounded-full shrink-0">
              <AlertTriangle className="w-3 h-3" />
              {selectedGroup.status}
            </span>
          )}
          {!selectedGroup && !isLoading && (
            <span className="ml-auto flex items-center gap-1 text-[10px] text-red-400 bg-red-400/10 px-1.5 py-0.5 rounded-full shrink-0">
              <AlertTriangle className="w-3 h-3" />
              Group not found
            </span>
          )}
        </div>
      ) : (
        <div className="text-[11px] text-gray-600 px-1">
          No Certificate Group linked — the filter uses the <code className="font-mono">logicalId</code> entered
          in the configuration fields above. Linking a group syncs the logicalId automatically.
        </div>
      )}

      {/* Picker dropdown */}
      <div className="flex items-stretch gap-2">
        <div className="relative flex-1">
          <button
            type="button"
            onClick={() => setOpen(o => !o)}
            disabled={isLoading || !tenantId}
            className={cn(
              'w-full h-full flex items-center justify-between gap-2 px-3 py-2 rounded-lg border text-sm transition-all',
              open
                ? 'bg-white/[0.06] border-sky-500 text-white ring-1 ring-sky-500/30'
                : 'bg-white/[0.03] border-white/[0.08] text-gray-400 hover:text-white hover:border-white/20',
              (isLoading || !tenantId) && 'opacity-50 cursor-not-allowed',
            )}
          >
            <span className="truncate text-xs">
              {isLoading
                ? 'Loading cert groups…'
                : !tenantId
                  ? 'No tenant context'
                  : isLinked
                    ? 'Change linked Certificate Group…'
                    : 'Link to a Certificate Group…'}
            </span>
            <ChevronDown className={cn('w-3.5 h-3.5 shrink-0 transition-transform', open && 'rotate-180')} />
          </button>

          {open && (
            <>
              <div className="fixed inset-0 z-10" onClick={() => { setOpen(false); setSearch('') }} />
              <div className="absolute z-20 mt-1 w-full max-h-72 flex flex-col rounded-xl bg-[#111318] border border-white/10 shadow-2xl shadow-black/60 animate-fade-in overflow-hidden">
                {/* Inline search */}
                <div className="p-2 border-b border-white/[0.06] shrink-0">
                  <input
                    autoFocus
                    value={search}
                    onChange={e => setSearch(e.target.value)}
                    placeholder="Search by logicalId or alias…"
                    className="w-full bg-white/[0.04] border border-white/[0.08] rounded-lg px-3 py-1.5 text-xs text-white placeholder-gray-600 focus:outline-none focus:border-sky-500 focus:ring-1 focus:ring-sky-500/30 transition-all"
                  />
                </div>

                <div className="overflow-y-auto flex-1">
                  {filteredGroups.length === 0 ? (
                    <div className="px-4 py-5 text-center">
                      <p className="text-sm text-gray-400 mb-1">
                        {search ? `No groups match "${search}"` : 'No active Certificate Groups found'}
                      </p>
                      <p className="text-xs text-gray-600">
                        Create a Certificate Group in{' '}
                        <button
                          type="button"
                          onClick={() => { setOpen(false); onNavigateToCertVault?.() }}
                          className="text-sky-400 hover:text-sky-300 underline transition-colors"
                        >
                          Cert Vault → Groups
                        </button>
                      </p>
                    </div>
                  ) : (
                    filteredGroups.map(group => (
                      <button
                        key={group.logicalId}
                        type="button"
                        onClick={() => handleSelect(group)}
                        className={cn(
                          'w-full flex items-center justify-between gap-3 px-3 py-2.5 text-left hover:bg-white/[0.05] transition-colors',
                          value?.refId === group.logicalId && 'bg-sky-500/10',
                        )}
                      >
                        <div className="min-w-0">
                          <div className={cn(
                            'text-sm font-medium truncate font-mono',
                            value?.refId === group.logicalId ? 'text-sky-300' : 'text-gray-200',
                          )}>
                            {group.logicalId}
                          </div>
                          {group.alias && group.alias !== group.logicalId && (
                            <div className="text-[11px] text-gray-500 truncate mt-0.5">
                              {group.alias}
                            </div>
                          )}
                          <div className="text-[10px] text-gray-600 font-mono mt-0.5">
                            {group.memberCount} cert{group.memberCount !== 1 ? 's' : ''} · {group.expiryHealthStatus}
                          </div>
                        </div>
                        <div className="flex items-center gap-2 shrink-0">
                          <span className={cn(
                            'text-[10px] px-1.5 py-0.5 rounded font-mono',
                            group.expiryHealthStatus === 'VALID'
                              ? 'bg-emerald-500/10 text-emerald-400'
                              : group.expiryHealthStatus === 'EXPIRING_SOON'
                                ? 'bg-amber-500/10 text-amber-400'
                                : 'bg-red-500/10 text-red-400',
                          )}>
                            {group.expiryHealthStatus}
                          </span>
                          {value?.refId === group.logicalId && (
                            <CheckCircle className="w-3.5 h-3.5 text-sky-400" />
                          )}
                        </div>
                      </button>
                    ))
                  )}
                </div>
              </div>
            </>
          )}
        </div>

        {/* Navigate to Cert Vault */}
        <button
          type="button"
          onClick={onNavigateToCertVault}
          className="flex items-center gap-1.5 px-3 py-2 text-xs text-sky-400 hover:text-sky-300 bg-white/[0.03] hover:bg-white/[0.06] border border-white/8 hover:border-sky-500/30 rounded-lg transition-all whitespace-nowrap shrink-0"
          title="Manage Certificate Groups in Cert Vault"
        >
          <Plus className="w-3 h-3" />
          New Group
        </button>
      </div>

      {/* Footer: reassure that logicalId is synced into the config field */}
      <p className="text-[10px] text-gray-600 leading-relaxed px-1">
        Selecting a group auto-fills the <code className="font-mono text-gray-500">logicalId</code> field
        in the filter configuration above. The cert filter type{' '}
        <strong className="text-gray-500">{certFilterLabel(filterType)}</strong>{' '}
        is the only filter category permitted to reference an external configuration source.
      </p>
    </div>
  )
}

