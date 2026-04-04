/**
 * FilterDefinitionForm — create or edit a filter definition.
 * Each filter type renders its own dedicated configuration fields.
 *
 * Filter type catalogue and visual metadata are sourced from filterRegistry.ts
 * so this component no longer maintains its own duplicate lists.
 *
 * ARCHITECTURAL CONSTRAINT — External config sources:
 *   Standard filters are SELF-CONTAINED. They store all their configuration
 *   inline in the filter definition and MUST NOT import configuration from the
 *   API gateway config (no auth-provider refs, no rate-limit policy refs, etc.).
 *
 *   Two exceptions are allowed:
 *   1. Cert filters (`AUTH_CERT_VAULT`, `CERT_ROTATION`, `CERT_VAULT_EXPIRY_CHECK`)
 *      may bind to a Certificate Group via `CertVaultGroupPicker`.
 *   2. Auth filters (`AUTH_JWT`, `AUTH_OAUTH2`, `AUTH_BASIC`, `DOWNSTREAM_BASIC_AUTH`,
 *      `DOWNSTREAM_BEARER_CC`) may bind to a Gateway Auth Provider via `AuthProviderPicker`.
 *      The selected provider's `id` is written into the filter config as `authProviderId`.
 */
import { useState, useEffect, useRef } from 'react'
import { createPortal } from 'react-dom'
import { useMutation, useQuery } from '@tanstack/react-query'
import { X, AlertCircle, Filter, ChevronDown } from 'lucide-react'
import { filtersApi } from '../../api/filtersApi'
import type { FilterType, CreateFilterRequest, UpdateFilterRequest, GatewayConfigRef } from '../../types'
import { FILTER_TYPES_WITH_CERT_VAULT_REF } from '../../types'
import FilterConfigFields from './FilterConfigFields'
import { DEFAULT_CONFIGS, type FilterConfig, inputCls } from './filterConfigConstants'
import CertVaultGroupPicker from './CertVaultGroupPicker'
import AuthProviderPicker, { FILTER_TYPES_WITH_AUTH_PROVIDER_REF, type AuthProviderRef } from './AuthProviderPicker'
import { cn } from '../../lib/utils'
import { extractApiError } from '../../lib/errorUtils'
import {
  FILTER_REGISTRY,
  CATEGORY_ORDER,
  CATEGORY_COLORS,
  assertStandardFilterIsolation,
} from './filterRegistry'

// ─── Re-shape registry for the picker ────────────────────────────────────────

const FILTER_TYPES = FILTER_REGISTRY.map(e => ({
  value:       e.value,
  label:       e.label,
  category:    e.category as string,
  description: e.description,
}))

// ─── Portal dropdown for FilterTypePicker ────────────────────────────────────

function FilterTypeDropdownPortal({
  style,
  grouped,
  search,
  value,
  onSearch,
  onSelect,
  onClose,
}: {
  style: React.CSSProperties
  grouped: Record<string, Array<{ value: string; label: string; category: string; description: string }>>
  search: string
  value: string
  onSearch: (s: string) => void
  onSelect: (ft: FilterType) => void
  onClose: () => void
}) {
  return createPortal(
    <>
      {/* backdrop — closes dropdown on outside click */}
      <div className="fixed inset-0 z-9998" onClick={onClose} />
      <div
        style={style}
        className="max-h-80 overflow-y-auto rounded-xl bg-[#111318] border border-white/10 shadow-2xl shadow-black/60 animate-fade-in"
      >
        {/* Search */}
        <div className="sticky top-0 bg-[#111318] p-2 border-b border-white/6">
          <input
            autoFocus
            value={search}
            onChange={e => onSearch(e.target.value)}
            placeholder="Search filter types…"
            className="w-full bg-white/4 border border-white/8 rounded-lg px-3 py-1.5 text-sm text-white placeholder-gray-600 focus:outline-none focus:border-indigo-500 focus:ring-1 focus:ring-indigo-500/30 transition-all"
          />
        </div>
        {/* Groups */}
        {Object.entries(grouped).map(([cat, types]) => (
          <div key={cat}>
            <div className="px-3 py-1.5 text-[10px] font-bold text-gray-600 uppercase tracking-widest bg-white/2">
              {cat}
            </div>
            {types.map(ft => (
              <button
                key={ft.value}
                type="button"
                onClick={() => onSelect(ft.value as FilterType)}
                className={cn(
                  'w-full flex items-start gap-3 px-3 py-2.5 text-left hover:bg-white/5 transition-colors',
                  value === ft.value && 'bg-indigo-500/10',
                )}
              >
                <span className={cn('text-[10px] font-bold px-2 py-0.5 rounded-full border shrink-0 mt-0.5', CATEGORY_COLORS[ft.category as keyof typeof CATEGORY_COLORS] ?? '')}>
                  {ft.category.slice(0, 4)}
                </span>
                <div className="min-w-0">
                  <div className={cn('text-sm font-medium', value === ft.value ? 'text-indigo-300' : 'text-gray-200')}>
                    {ft.label}
                  </div>
                  <div className="text-[11px] text-gray-500 mt-0.5 leading-snug">{ft.description}</div>
                </div>
                {value === ft.value && <span className="ml-auto text-indigo-400 text-xs shrink-0">✓</span>}
              </button>
            ))}
          </div>
        ))}
        {Object.keys(grouped).length === 0 && (
          <div className="px-4 py-6 text-sm text-gray-500 text-center">No filter types match "{search}"</div>
        )}
      </div>
    </>,
    document.body,
  )
}

// ─── Type Picker ──────────────────────────────────────────────────────────────

function FilterTypePicker({
  value,
  onChange,
}: {
  value: FilterType
  onChange: (t: FilterType) => void
}) {
  const [open, setOpen] = useState(false)
  const [search, setSearch] = useState('')
  const triggerRef = useRef<HTMLButtonElement>(null)
  const [dropdownStyle, setDropdownStyle] = useState<React.CSSProperties>({})

  const selected = FILTER_TYPES.find(f => f.value === value)
  const filtered = search
    ? FILTER_TYPES.filter(f =>
        f.label.toLowerCase().includes(search.toLowerCase()) ||
        f.category.toLowerCase().includes(search.toLowerCase()) ||
        f.description.toLowerCase().includes(search.toLowerCase()),
      )
    : FILTER_TYPES

  const grouped = CATEGORY_ORDER.reduce<Record<string, typeof FILTER_TYPES>>((acc, cat) => {
    const items = filtered.filter(f => f.category === cat)
    if (items.length) acc[cat] = items
    return acc
  }, {})

  const handleOpen = () => {
    if (!open && triggerRef.current) {
      const rect = triggerRef.current.getBoundingClientRect()
      const spaceBelow = window.innerHeight - rect.bottom
      const spaceAbove = rect.top
      const dropdownH = Math.min(320, window.innerHeight * 0.5)
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

  return (
    <div className="relative">
      {/* Trigger */}
      <button
        ref={triggerRef}
        type="button"
        onClick={handleOpen}
        className={cn(
          'w-full flex items-center justify-between gap-3 px-3 py-2 rounded-lg border text-sm transition-all',
          open
            ? 'bg-white/6 border-indigo-500 text-white ring-1 ring-indigo-500/30'
            : 'bg-white/4 border-white/8 text-white hover:border-white/20',
        )}
      >
        <div className="flex items-center gap-2.5 min-w-0">
          {selected ? (
            <>
              <span className={cn('text-[10px] font-bold px-2 py-0.5 rounded-full border shrink-0', CATEGORY_COLORS[selected.category as keyof typeof CATEGORY_COLORS] ?? '')}>
                {selected.category}
              </span>
              <span className="font-medium truncate">{selected.label}</span>
              <span className="text-xs text-gray-500 truncate hidden sm:block">{selected.description}</span>
            </>
          ) : (
            <span className="text-gray-500">Select filter type…</span>
          )}
        </div>
        <ChevronDown className={cn('w-4 h-4 text-gray-500 shrink-0 transition-transform', open && 'rotate-180')} />
      </button>

      {/* Dropdown — rendered in a portal so it escapes overflow-hidden parents */}
      {open && (
        <FilterTypeDropdownPortal
          style={dropdownStyle}
          grouped={grouped}
          search={search}
          value={value}
          onSearch={setSearch}
          onSelect={(ft) => { onChange(ft); setOpen(false); setSearch('') }}
          onClose={() => setOpen(false)}
        />
      )}
    </div>
  )
}

// ─── Main form ────────────────────────────────────────────────────────────────

export default function FilterDefinitionForm({
  editingId,
  presetFilterType,
  onClose,
  onSaved,
}: {
  editingId?: string
  /** Pre-select a filter type when opening the form from the type browser */
  presetFilterType?: string
  onClose: () => void
  onSaved: () => void
}) {
  const isEdit = !!editingId

  const { data: existing, isLoading: loadingExisting } = useQuery({
    queryKey: ['filter', editingId],
    queryFn: () => filtersApi.get(editingId!),
    enabled: isEdit,
  })

  // ── Form state ──
  const [name,             setName]             = useState('')
  const [description,      setDescription]      = useState('')
  const [filterType,       setFilterType]       = useState<FilterType>(
    (presetFilterType as FilterType) ?? 'AUTH_JWT'
  )
  const [config,           setConfig]           = useState<FilterConfig>(
    DEFAULT_CONFIGS[(presetFilterType as FilterType) ?? 'AUTH_JWT'] ?? {}
  )
  const [gatewayConfigRef, setGatewayConfigRef] = useState<GatewayConfigRef | null>(null)
  const [authProviderRef,  setAuthProviderRef]  = useState<AuthProviderRef | null>(null)

  // Populate from existing when editing
  useEffect(() => {
    if (existing) {
      setName(existing.name)
      setDescription(existing.description ?? '')
      setFilterType(existing.filterType)
      const existingConfig = existing.config ?? {}
      setConfig(existingConfig)

      // Enforce isolation rule: standard filters must not have a gateway config ref.
      // If an existing record has one (legacy data), strip it and warn.
      const existingRef = existing.gatewayConfigRef ?? null
      assertStandardFilterIsolation(existing.filterType, !!existingRef)
      setGatewayConfigRef(
        FILTER_TYPES_WITH_CERT_VAULT_REF.has(existing.filterType) ? existingRef : null,
      )

      // Restore auth provider ref from stored config fields (authProviderId + authProviderName)
      if (
        FILTER_TYPES_WITH_AUTH_PROVIDER_REF.has(existing.filterType) &&
        existingConfig.authProviderId
      ) {
        setAuthProviderRef({
          providerId:   existingConfig.authProviderId as string,
          providerName: (existingConfig.authProviderName as string) ?? existingConfig.authProviderId as string,
          providerType: (existingConfig.authProviderType as AuthProviderRef['providerType']) ?? 'JWT_VERIFY',
        })
      }
    }
  }, [existing])

  // Reset config to defaults when type changes (create mode)
  const handleTypeChange = (t: FilterType) => {
    setFilterType(t)
    setConfig(DEFAULT_CONFIGS[t] ?? {})
    setGatewayConfigRef(null)
    setAuthProviderRef(null)
  }

  /**
   * Cert Vault group selection handler.
   *
   * Only cert filter types reach this handler (the picker is not rendered for
   * standard filters). When the user picks a group, we sync its `logicalId`
   * into the filter's inline config so the gateway registry filter binds
   * correctly at runtime — no gateway restart required.
   */
  const handleCertVaultRefChange = (ref: GatewayConfigRef | null) => {
    setGatewayConfigRef(ref)
    if (ref?.refType === 'VAULT_CERT') {
      // Auto-fill the logicalId config field from the selected group
      setConfig(prev => ({ ...prev, logicalId: ref.refId }))
    } else if (ref === null && FILTER_TYPES_WITH_CERT_VAULT_REF.has(filterType)) {
      // Clear logicalId when unlinking a cert group
      setConfig(prev => ({ ...prev, logicalId: '' }))
    }
  }

  /**
   * Auth Provider selection handler.
   *
   * When a provider is selected we write its `id`, `name`, and `type` into
   * the filter's inline config as `authProviderId`, `authProviderName`,
   * `authProviderType`. The gateway factory reads `authProviderId` to resolve
   * the full provider config at runtime.
   */
  const handleAuthProviderRefChange = (ref: AuthProviderRef | null) => {
    setAuthProviderRef(ref)
    if (ref) {
      setConfig(prev => ({
        ...prev,
        authProviderId:   ref.providerId,
        authProviderName: ref.providerName,
        authProviderType: ref.providerType,
      }))
    } else {
      // Clear auth provider fields from config when unlinking
      setConfig(prev => {
        const next = { ...prev }
        delete next.authProviderId
        delete next.authProviderName
        delete next.authProviderType
        return next
      })
    }
  }

  const createMutation = useMutation({
    mutationFn: (req: CreateFilterRequest) => filtersApi.create(req),
    onSuccess: onSaved,
  })
  const updateMutation = useMutation({
    mutationFn: (req: UpdateFilterRequest) => filtersApi.update(editingId!, req),
    onSuccess: onSaved,
  })

  const isPending = createMutation.isPending || updateMutation.isPending
  const error     = createMutation.error ?? updateMutation.error

  const handleSubmit = (e: React.FormEvent) => {
    e.preventDefault()
    if (!name.trim()) return

    // Cert Vault ref is only valid for cert filter types — strip it for standard filters
    // to enforce the architectural rule that standard filters are self-contained.
    const certRef = supportsCertVaultRef ? gatewayConfigRef : null

    if (isEdit) {
      updateMutation.mutate({
        name: name.trim(),
        description: description.trim() || undefined,
        config,
        gatewayConfigRef: certRef ?? null,
      })
    } else {
      createMutation.mutate({
        name: name.trim(),
        description: description.trim() || undefined,
        filterType,
        config,
        gatewayConfigRef: certRef ?? undefined,
      })
    }
  }

  const selectedMeta = FILTER_TYPES.find(f => f.value === filterType)
  /**
   * Whether this filter type may reference a Cert Vault group.
   * Standard filter types always return false — they are self-contained.
   */
  const supportsCertVaultRef    = FILTER_TYPES_WITH_CERT_VAULT_REF.has(filterType)
  /**
   * Whether this filter type may reference a Gateway Auth Provider.
   */
  const supportsAuthProviderRef = FILTER_TYPES_WITH_AUTH_PROVIDER_REF.has(filterType)

  return (
    <div className="fixed inset-0 bg-black/70 backdrop-blur-sm flex items-center justify-center z-50 p-4 sm:p-6 animate-fade-in">
      <div className="bg-[#111318] border border-white/8 rounded-2xl w-full max-w-4xl shadow-2xl shadow-black/60 flex flex-col max-h-[94vh] animate-fade-in-up">

        {/* ── Header ───────────────────────────────────────────────────────────── */}
        <div className="flex items-center justify-between px-6 py-4 border-b border-white/6 shrink-0">
          <div className="flex items-center gap-3">
            <div className="w-8 h-8 rounded-xl bg-indigo-500/20 border border-indigo-500/30 flex items-center justify-center shrink-0">
              <Filter className="w-4 h-4 text-indigo-400" />
            </div>
            <div>
              <h2 className="text-sm font-bold text-white leading-tight">
                {isEdit ? 'Edit Filter' : 'Create Filter Definition'}
              </h2>
              {selectedMeta && (
                <p className="text-[11px] text-gray-500 leading-tight mt-0.5">{selectedMeta.description}</p>
              )}
            </div>
          </div>
          <button
            onClick={onClose}
            className="p-1.5 rounded-lg text-gray-500 hover:text-white hover:bg-white/5 transition-colors"
          >
            <X className="w-4 h-4" />
          </button>
        </div>

        {/* ── Body ─────────────────────────────────────────────────────────────── */}
        <div className="flex-1 overflow-y-auto min-h-0">
          {isEdit && loadingExisting ? (
            <div className="flex items-center justify-center py-20 gap-3">
              <div className="w-5 h-5 border-2 border-indigo-500/30 border-t-indigo-500 rounded-full animate-spin" />
              <p className="text-sm text-gray-500">Loading filter…</p>
            </div>
          ) : (
            <form id="filter-form" onSubmit={handleSubmit}>
              {/* Two-column layout on md+ screens */}
              <div className="grid grid-cols-1 md:grid-cols-[300px_1fr] divide-y md:divide-y-0 md:divide-x divide-white/6">

                {/* ── Left panel — identity & type ─────────────────────────── */}
                <div className="p-6 space-y-5">
                  {/* Name */}
                  <div className="space-y-1.5">
                    <label className="text-xs font-semibold text-gray-400 uppercase tracking-wider">Name *</label>
                    <input
                      required
                      value={name}
                      onChange={e => setName(e.target.value)}
                      placeholder="e.g. jwt-auth-prod"
                      className={inputCls}
                    />
                  </div>

                  {/* Description */}
                  <div className="space-y-1.5">
                    <div className="flex items-center gap-2">
                      <label className="text-xs font-semibold text-gray-400 uppercase tracking-wider">Description</label>
                      <span className="text-[10px] text-gray-600">optional</span>
                    </div>
                    <textarea
                      value={description}
                      onChange={e => setDescription(e.target.value)}
                      placeholder="What does this filter do?"
                      rows={3}
                      className={`${inputCls} resize-none`}
                    />
                  </div>

                  {/* Filter Type Picker — create mode only */}
                  {!isEdit && (
                    <div className="space-y-1.5">
                      <label className="text-xs font-semibold text-gray-400 uppercase tracking-wider">Filter Type *</label>
                      <FilterTypePicker value={filterType} onChange={handleTypeChange} />
                    </div>
                  )}

                  {/* Edit mode — type badge */}
                  {isEdit && selectedMeta && (
                    <div className="space-y-1.5">
                      <label className="text-xs font-semibold text-gray-400 uppercase tracking-wider">Filter Type</label>
                      <div className="flex items-center gap-2 px-3 py-2 rounded-lg bg-white/3 border border-white/7">
                        <span className={cn('text-[10px] font-bold px-2 py-0.5 rounded-full border shrink-0', CATEGORY_COLORS[selectedMeta.category as keyof typeof CATEGORY_COLORS] ?? '')}>
                          {selectedMeta.category}
                        </span>
                        <span className="text-sm text-gray-300 font-medium truncate">{selectedMeta.label}</span>
                      </div>
                    </div>
                  )}

                  {/* Cert Vault Group binding — ONLY for cert filter types */}
                  {supportsCertVaultRef && (
                    <CertVaultGroupPicker
                      filterType={filterType}
                      value={gatewayConfigRef}
                      onChange={handleCertVaultRefChange}
                      onNavigateToCertVault={() => {
                        window.open('/certificates', '_blank')
                      }}
                    />
                  )}

                  {/* Auth Provider binding — for auth / downstream-auth filter types */}
                  {supportsAuthProviderRef && (
                    <AuthProviderPicker
                      filterType={filterType}
                      value={authProviderRef}
                      onChange={handleAuthProviderRefChange}
                      onNavigateToGatewayConfig={() => {
                        window.open('/gateway?tab=auth-providers', '_blank')
                      }}
                    />
                  )}
                </div>

                {/* ── Right panel — type-specific configuration ─────────────── */}
                <div className="p-6 space-y-5">
                  {/* Config section header */}
                  <div className="flex items-center gap-2">
                    {selectedMeta && (
                      <span className={cn('text-[10px] font-bold px-2 py-0.5 rounded-full border shrink-0', CATEGORY_COLORS[selectedMeta.category as keyof typeof CATEGORY_COLORS] ?? '')}>
                        {selectedMeta.category}
                      </span>
                    )}
                    <span className="text-xs font-semibold text-gray-400">
                      {isEdit ? filterType.replace(/_/g, ' ') : (selectedMeta?.label ?? filterType)} Configuration
                    </span>
                    {gatewayConfigRef && supportsCertVaultRef && (
                      <span className="ml-auto text-[10px] text-sky-400 bg-sky-400/10 px-2 py-0.5 rounded-full border border-sky-400/20 whitespace-nowrap">
                        ↑ logicalId synced
                      </span>
                    )}
                    {authProviderRef && supportsAuthProviderRef && (
                      <span className="ml-auto text-[10px] text-indigo-400 bg-indigo-400/10 px-2 py-0.5 rounded-full border border-indigo-400/20 whitespace-nowrap">
                        ↑ provider synced
                      </span>
                    )}
                  </div>

                  {/* Cert Vault sync notice */}
                  {gatewayConfigRef && supportsCertVaultRef && (
                    <div className="px-3 py-2.5 rounded-lg bg-sky-500/5 border border-sky-500/20 text-xs text-sky-300/70 leading-relaxed">
                      The <code className="font-mono text-sky-200">logicalId</code> field below has been
                      auto-filled from the linked Cert Vault group{' '}
                      <span className="font-mono text-sky-200">
                        {gatewayConfigRef.refName ?? gatewayConfigRef.refId}
                      </span>.
                      At runtime the gateway certificate registry uses this ID to resolve active certificates.
                    </div>
                  )}

                  {/* Auth Provider sync notice */}
                  {authProviderRef && supportsAuthProviderRef && (
                    <div className="px-3 py-2.5 rounded-lg bg-indigo-500/5 border border-indigo-500/20 text-xs text-indigo-300/70 leading-relaxed">
                      <code className="font-mono text-indigo-200">authProviderId</code> has been set
                      to the linked provider{' '}
                      <span className="font-mono text-indigo-200">{authProviderRef.providerName}</span>{' '}
                      (<code className="font-mono text-indigo-300/80">{authProviderRef.providerId}</code>).
                      The gateway factory resolves this provider's credentials at runtime — inline
                      credential fields below are superseded when a provider is linked.
                    </div>
                  )}

                  {/* Type-specific fields */}
                  <FilterConfigFields
                    filterType={filterType}
                    config={config}
                    onChange={setConfig}
                  />

                  {/* Error banner */}
                  {error && (
                    <div className="flex items-start gap-2.5 p-3.5 bg-red-500/8 border border-red-500/20 rounded-xl">
                      <AlertCircle className="w-4 h-4 text-red-400 mt-0.5 shrink-0" />
                      <span className="text-sm text-red-300">
                        {extractApiError(error, 'Failed to save filter')}
                      </span>
                    </div>
                  )}
                </div>
              </div>
            </form>
          )}
        </div>

        {/* ── Footer actions ────────────────────────────────────────────────────── */}
        <div className="flex items-center justify-end gap-2 px-6 py-4 border-t border-white/6 shrink-0 bg-white/1">
          <button
            type="button"
            onClick={onClose}
            className="px-4 py-2 text-sm text-gray-400 hover:text-white hover:bg-white/5 rounded-lg transition-colors"
          >
            Cancel
          </button>
          <button
            type="submit"
            form="filter-form"
            disabled={isPending || (isEdit && loadingExisting)}
            className="px-5 py-2 bg-indigo-600 hover:bg-indigo-500 disabled:opacity-50 text-white text-sm font-semibold rounded-lg transition-all shadow-lg shadow-indigo-500/20"
          >
            {isPending ? 'Saving…' : isEdit ? 'Update Filter' : 'Create Filter'}
          </button>
        </div>
      </div>
    </div>
  )
}
