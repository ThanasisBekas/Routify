/**
 * AuthProviderPicker — lets the user bind an auth/downstream filter to a
 * Gateway Auth Provider configured in the API Gateway Configuration.
 *
 * ARCHITECTURAL RULE:
 *   This picker is ONLY rendered for filter types that reference an auth provider:
 *     - AUTH_JWT           → JWT_VERIFY providers
 *     - AUTH_OAUTH2        → OAUTH2_INTROSPECT providers
 *     - AUTH_BASIC         → BASIC providers  (optional — filter is inline by default)
 *     - DOWNSTREAM_BASIC_AUTH → BASIC providers
 *     - DOWNSTREAM_BEARER_CC  → OAUTH2_CLIENT_CREDENTIALS / OAUTH2_PASSWORD providers
 *
 * What this does:
 *   - Fetches Auth Providers from `gatewayApi.getAuthProviders()`.
 *   - Optionally filters by compatible provider types for the current filter.
 *   - Writes the selected provider's `id` into the filter config under `authProviderId`
 *     and the human-readable `name` under `authProviderName` (for display only).
 *   - The gateway factory resolves the provider at runtime via its `id`.
 */
import { useState, useRef } from 'react'
import { useQuery } from '@tanstack/react-query'
import { Lock, ChevronDown, X, AlertTriangle, CheckCircle, ExternalLink } from 'lucide-react'
import { gatewayApi } from '../../api/gatewayApi'
import type { FilterType, GatewayAuthProvider } from '../../types'
import { cn } from '../../lib/utils'
import { createPortal } from 'react-dom'

// ─── Filter type → compatible provider types ──────────────────────────────────

const COMPATIBLE_TYPES: Partial<Record<FilterType, GatewayAuthProvider['type'][]>> = {
  AUTH_JWT:              ['JWT_VERIFY'],
  AUTH_OAUTH2:           ['OAUTH2_INTROSPECT'],
  AUTH_BASIC:            ['BASIC'],
  DOWNSTREAM_BASIC_AUTH: ['BASIC'],
  DOWNSTREAM_BEARER_CC:  ['OAUTH2_CLIENT_CREDENTIALS', 'OAUTH2_PASSWORD'],
}

/** Filter types that CAN bind to a gateway auth provider */
export const FILTER_TYPES_WITH_AUTH_PROVIDER_REF: ReadonlySet<FilterType> = new Set(
  Object.keys(COMPATIBLE_TYPES) as FilterType[]
)

function filterTypeLabel(filterType: FilterType): string {
  switch (filterType) {
    case 'AUTH_JWT':              return 'JWT Auth'
    case 'AUTH_OAUTH2':           return 'OAuth2 Auth'
    case 'AUTH_BASIC':            return 'Basic Auth'
    case 'DOWNSTREAM_BASIC_AUTH': return 'Downstream Basic Auth'
    case 'DOWNSTREAM_BEARER_CC':  return 'Downstream Bearer (CC)'
    default:                      return filterType
  }
}

function filterTypeDescription(filterType: FilterType): string {
  switch (filterType) {
    case 'AUTH_JWT':
      return 'Bind this filter to a JWT Verify provider. The provider\'s JWKS URI, issuer, and audience settings override the inline config fields below.'
    case 'AUTH_OAUTH2':
      return 'Bind this filter to an OAuth2 Introspection provider. The provider\'s introspection endpoint and client credentials are used for token validation.'
    case 'AUTH_BASIC':
      return 'Optionally bind to a Basic Auth provider from the gateway config instead of storing credentials inline.'
    case 'DOWNSTREAM_BASIC_AUTH':
      return 'Bind to a Basic Auth provider to inject its credentials as the downstream Authorization header, avoiding inline credential storage.'
    case 'DOWNSTREAM_BEARER_CC':
      return 'Bind to an OAuth2 Client Credentials provider. The gateway will acquire a bearer token from the provider\'s token endpoint and inject it downstream.'
    default:
      return 'Bind to a Gateway Auth Provider.'
  }
}

// ─── Provider type label / style ─────────────────────────────────────────────

const TYPE_LABELS: Record<string, string> = {
  JWT_VERIFY:                'JWT (JWKS)',
  OAUTH2_CLIENT_CREDENTIALS: 'OAuth2 CC',
  OAUTH2_PASSWORD:           'OAuth2 Password',
  OAUTH2_INTROSPECT:         'OAuth2 Introspect',
  BASIC:                     'Basic Auth',
}

const TYPE_COLORS: Record<string, string> = {
  JWT_VERIFY:                'text-indigo-300 bg-indigo-500/10 border-indigo-500/20',
  OAUTH2_CLIENT_CREDENTIALS: 'text-teal-300 bg-teal-500/10 border-teal-500/20',
  OAUTH2_PASSWORD:           'text-violet-300 bg-violet-500/10 border-violet-500/20',
  OAUTH2_INTROSPECT:         'text-amber-300 bg-amber-500/10 border-amber-500/20',
  BASIC:                     'text-sky-300 bg-sky-500/10 border-sky-500/20',
}

// ─── Props ────────────────────────────────────────────────────────────────────

export interface AuthProviderRef {
  /** The gateway auth provider's `id` field */
  providerId: string
  /** Display name — sourced from provider.name */
  providerName: string
  /** Provider type — for display only */
  providerType: GatewayAuthProvider['type']
}

interface Props {
  filterType: FilterType
  value: AuthProviderRef | null
  onChange: (ref: AuthProviderRef | null) => void
  onNavigateToGatewayConfig?: () => void
}

// ─── Portal dropdown ──────────────────────────────────────────────────────────

function DropdownPortal({
  style,
  providers,
  search,
  selectedId,
  onSearch,
  onSelect,
  onClose,
  onNavigate,
}: {
  style: React.CSSProperties
  providers: GatewayAuthProvider[]
  search: string
  selectedId?: string
  onSearch: (s: string) => void
  onSelect: (p: GatewayAuthProvider) => void
  onClose: () => void
  onNavigate?: () => void
}) {
  const filtered = search
    ? providers.filter(
        p =>
          p.name.toLowerCase().includes(search.toLowerCase()) ||
          (TYPE_LABELS[p.type] ?? p.type).toLowerCase().includes(search.toLowerCase()),
      )
    : providers

  return createPortal(
    <>
      <div className="fixed inset-0 z-9998" onClick={onClose} />
      <div
        style={style}
        className="max-h-72 flex flex-col rounded-xl bg-[#111318] border border-white/10 shadow-2xl shadow-black/60 animate-fade-in overflow-hidden"
      >
        {/* Search */}
        <div className="p-2 border-b border-white/6 shrink-0">
          <input
            autoFocus
            value={search}
            onChange={e => onSearch(e.target.value)}
            placeholder="Search providers…"
            className="w-full bg-white/4 border border-white/8 rounded-lg px-3 py-1.5 text-xs text-white placeholder-gray-600 focus:outline-none focus:border-indigo-500 focus:ring-1 focus:ring-indigo-500/30 transition-all"
          />
        </div>

        <div className="overflow-y-auto flex-1">
          {filtered.length === 0 ? (
            <div className="px-4 py-5 text-center">
              <p className="text-sm text-gray-400 mb-1">
                {search ? `No providers match "${search}"` : 'No compatible providers configured'}
              </p>
              <p className="text-xs text-gray-600">
                Add a provider in{' '}
                <button
                  type="button"
                  onClick={() => { onClose(); onNavigate?.() }}
                  className="text-indigo-400 hover:text-indigo-300 underline transition-colors"
                >
                  Gateway Config → Auth Providers
                </button>
              </p>
            </div>
          ) : (
            filtered.map(p => (
              <button
                key={p.id}
                type="button"
                onClick={() => onSelect(p)}
                className={cn(
                  'w-full flex items-center justify-between gap-3 px-3 py-2.5 text-left hover:bg-white/5 transition-colors',
                  selectedId === p.id && 'bg-indigo-500/10',
                )}
              >
                <div className="min-w-0">
                  <div className={cn(
                    'text-sm font-medium truncate',
                    selectedId === p.id ? 'text-indigo-300' : 'text-gray-200',
                  )}>
                    {p.name}
                  </div>
                  <div className="text-[10px] text-gray-500 mt-0.5 font-mono truncate">
                    {p.jwksUri ?? p.uri ?? (p.username ? `user: ${p.username}` : p.id)}
                  </div>
                </div>
                <div className="flex items-center gap-2 shrink-0">
                  <span className={cn(
                    'text-[10px] px-1.5 py-0.5 rounded font-mono border',
                    TYPE_COLORS[p.type] ?? 'text-gray-400 bg-white/5 border-white/10',
                  )}>
                    {TYPE_LABELS[p.type] ?? p.type}
                  </span>
                  {!p.enabled && (
                    <span className="text-[10px] px-1.5 py-0.5 rounded bg-gray-700 text-gray-400 border border-white/10">
                      disabled
                    </span>
                  )}
                  {selectedId === p.id && <CheckCircle className="w-3.5 h-3.5 text-indigo-400" />}
                </div>
              </button>
            ))
          )}
        </div>
      </div>
    </>,
    document.body,
  )
}

// ─── Main component ───────────────────────────────────────────────────────────

export default function AuthProviderPicker({
  filterType,
  value,
  onChange,
  onNavigateToGatewayConfig,
}: Props) {
  if (!FILTER_TYPES_WITH_AUTH_PROVIDER_REF.has(filterType)) return null

  return (
    <AuthProviderPickerInner
      filterType={filterType}
      value={value}
      onChange={onChange}
      onNavigateToGatewayConfig={onNavigateToGatewayConfig}
    />
  )
}

function AuthProviderPickerInner({
  filterType,
  value,
  onChange,
  onNavigateToGatewayConfig,
}: Props) {
  const [open, setOpen] = useState(false)
  const [search, setSearch] = useState('')
  const triggerRef = useRef<HTMLButtonElement>(null)
  const [dropdownStyle, setDropdownStyle] = useState<React.CSSProperties>({})

  const compatibleTypes = COMPATIBLE_TYPES[filterType]

  const { data: allProviders = [], isLoading } = useQuery({
    queryKey: ['gateway-auth-providers'],
    queryFn: () => gatewayApi.getAuthProviders(),
    staleTime: 30_000,
  })

  // Only show providers of compatible types for this filter
  const providers = compatibleTypes
    ? allProviders.filter(p => compatibleTypes.includes(p.type))
    : allProviders

  const selectedProvider = value
    ? allProviders.find(p => p.id === value.providerId) ?? null
    : null

  const isLinked = !!value

  const handleOpen = () => {
    if (!open && triggerRef.current) {
      const rect = triggerRef.current.getBoundingClientRect()
      const spaceBelow = window.innerHeight - rect.bottom
      const spaceAbove = rect.top
      const dropdownH = Math.min(288, window.innerHeight * 0.45)
      if (spaceBelow >= dropdownH || spaceBelow >= spaceAbove) {
        setDropdownStyle({
          position: 'fixed',
          top: rect.bottom + 4,
          left: rect.left,
          width: rect.width,
          zIndex: 9999,
        })
      } else {
        setDropdownStyle({
          position: 'fixed',
          bottom: window.innerHeight - rect.top + 4,
          left: rect.left,
          width: rect.width,
          zIndex: 9999,
        })
      }
    }
    setOpen(o => !o)
  }

  const handleSelect = (p: GatewayAuthProvider) => {
    onChange({
      providerId:   p.id,
      providerName: p.name,
      providerType: p.type,
    })
    setOpen(false)
    setSearch('')
  }

  const handleUnlink = () => {
    onChange(null)
  }

  return (
    <div className="rounded-xl border border-dashed border-indigo-500/30 bg-indigo-500/5 p-4 space-y-3">
      {/* Header */}
      <div className="flex items-start justify-between gap-3">
        <div className="flex items-start gap-2.5">
          <div className="w-7 h-7 rounded-lg bg-indigo-500/20 border border-indigo-500/30 flex items-center justify-center shrink-0 mt-0.5">
            <Lock className="w-3.5 h-3.5 text-indigo-400" />
          </div>
          <div>
            <div className="flex items-center gap-2">
              <span className="text-xs font-bold text-indigo-300 uppercase tracking-wider">
                Gateway — Auth Provider
              </span>
              <span className="text-[10px] text-gray-500 font-normal">
                optional
              </span>
            </div>
            <p className="text-[11px] text-gray-500 mt-0.5 leading-relaxed max-w-sm">
              {filterTypeDescription(filterType)}
            </p>
          </div>
        </div>

        {isLinked && (
          <button
            type="button"
            onClick={handleUnlink}
            className="p-1.5 text-gray-500 hover:text-red-400 hover:bg-red-400/10 rounded-lg transition-colors shrink-0"
            title="Unlink from Auth Provider"
          >
            <X className="w-3.5 h-3.5" />
          </button>
        )}
      </div>

      {/* Architecture note */}
      <div className="flex items-start gap-2 px-3 py-2 rounded-lg bg-indigo-500/6 border border-indigo-500/15 text-[10px] text-indigo-400/80 leading-relaxed">
        <Lock className="w-3 h-3 shrink-0 mt-0.5 text-indigo-500" />
        <span>
          Auth Providers are managed centrally in{' '}
          <strong className="text-indigo-300">Gateway Configuration → Auth Providers</strong>.
          Linking a provider writes its <code className="font-mono text-indigo-300">id</code> into
          the filter config so the gateway factory resolves credentials at runtime.
        </span>
      </div>

      {/* Current link status */}
      {isLinked ? (
        <div className="flex items-center gap-2.5 px-3 py-2 rounded-lg bg-indigo-500/10 border border-indigo-500/20">
          <CheckCircle className="w-4 h-4 text-indigo-400 shrink-0" />
          <div className="min-w-0">
            <div className="text-sm font-medium text-indigo-200 truncate">
              {selectedProvider?.name ?? value.providerName}
            </div>
            <div className="text-[11px] text-indigo-400/70 truncate mt-0.5 font-mono">
              id: {value.providerId}
              {selectedProvider && (
                <span className="ml-2 not-italic">
                  · {TYPE_LABELS[selectedProvider.type] ?? selectedProvider.type}
                </span>
              )}
            </div>
          </div>
          {/* Provider disabled warning */}
          {selectedProvider && !selectedProvider.enabled && (
            <span className="ml-auto flex items-center gap-1 text-[10px] text-yellow-400 bg-yellow-400/10 px-1.5 py-0.5 rounded-full shrink-0">
              <AlertTriangle className="w-3 h-3" />
              disabled
            </span>
          )}
          {/* Provider no longer found */}
          {!selectedProvider && !isLoading && (
            <span className="ml-auto flex items-center gap-1 text-[10px] text-red-400 bg-red-400/10 px-1.5 py-0.5 rounded-full shrink-0">
              <AlertTriangle className="w-3 h-3" />
              Provider not found
            </span>
          )}
        </div>
      ) : (
        <div className="text-[11px] text-gray-600 px-1">
          No provider linked — the filter will use the credentials and settings entered inline
          in the configuration panel.
        </div>
      )}

      {/* Picker trigger + Navigate button */}
      <div className="flex items-stretch gap-2">
        <div className="relative flex-1">
          <button
            ref={triggerRef}
            type="button"
            onClick={handleOpen}
            disabled={isLoading}
            className={cn(
              'w-full h-full flex items-center justify-between gap-2 px-3 py-2 rounded-lg border text-sm transition-all',
              open
                ? 'bg-white/6 border-indigo-500 text-white ring-1 ring-indigo-500/30'
                : 'bg-white/3 border-white/8 text-gray-400 hover:text-white hover:border-white/20',
              isLoading && 'opacity-50 cursor-not-allowed',
            )}
          >
            <span className="truncate text-xs">
              {isLoading
                ? 'Loading providers…'
                : isLinked
                  ? 'Change linked Auth Provider…'
                  : providers.length === 0
                    ? 'No compatible providers — add one in Gateway Config'
                    : 'Link to an Auth Provider…'}
            </span>
            <ChevronDown className={cn('w-3.5 h-3.5 shrink-0 transition-transform', open && 'rotate-180')} />
          </button>

          {open && (
            <DropdownPortal
              style={dropdownStyle}
              providers={providers}
              search={search}
              selectedId={value?.providerId}
              onSearch={setSearch}
              onSelect={handleSelect}
              onClose={() => { setOpen(false); setSearch('') }}
              onNavigate={onNavigateToGatewayConfig}
            />
          )}
        </div>

        {/* Navigate to Gateway Config */}
        <button
          type="button"
          onClick={onNavigateToGatewayConfig}
          className="flex items-center gap-1.5 px-3 py-2 text-xs text-indigo-400 hover:text-indigo-300 bg-white/3 hover:bg-white/6 border border-white/8 hover:border-indigo-500/30 rounded-lg transition-all whitespace-nowrap shrink-0"
          title="Manage Auth Providers in Gateway Configuration"
        >
          <ExternalLink className="w-3 h-3" />
          Manage
        </button>
      </div>

      {/* Footer */}
      <p className="text-[10px] text-gray-600 leading-relaxed px-1">
        Linking a provider writes its <code className="font-mono text-gray-500">authProviderId</code>{' '}
        into the filter config. The gateway factory reads this at startup and runtime to resolve
        credentials — no inline secrets required.
        Filter type: <strong className="text-gray-500">{filterTypeLabel(filterType)}</strong>.
        {compatibleTypes && (
          <> Compatible provider types: {compatibleTypes.map(t => TYPE_LABELS[t] ?? t).join(', ')}.</>
        )}
      </p>
    </div>
  )
}

