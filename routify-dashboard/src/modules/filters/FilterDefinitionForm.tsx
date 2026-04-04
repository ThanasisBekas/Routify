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
 *   The ONLY exception is cert filters (`AUTH_CERT_VAULT`, `CERT_ROTATION`,
 *   `CERT_VAULT_EXPIRY_CHECK`), which may bind to a Certificate Group in the
 *   Cert Vault via `CertVaultGroupPicker`. This binding is enforced here —
 *   non-cert filter types never see the picker.
 */
import { useState, useEffect } from 'react'
import { useMutation, useQuery } from '@tanstack/react-query'
import { X, AlertCircle, Filter, ChevronDown } from 'lucide-react'
import { filtersApi } from '../../api/filtersApi'
import type { FilterType, CreateFilterRequest, UpdateFilterRequest, GatewayConfigRef } from '../../types'
import { FILTER_TYPES_WITH_CERT_VAULT_REF } from '../../types'
import FilterConfigFields from './FilterConfigFields'
import { DEFAULT_CONFIGS, type FilterConfig, inputCls } from './filterConfigConstants'
import CertVaultGroupPicker from './CertVaultGroupPicker'
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

  return (
    <div className="relative">
      {/* Trigger */}
      <button
        type="button"
        onClick={() => setOpen(o => !o)}
        className={cn(
          'w-full flex items-center justify-between gap-3 px-3 py-2 rounded-lg border text-sm transition-all',
          open
            ? 'bg-white/[0.06] border-indigo-500 text-white ring-1 ring-indigo-500/30'
            : 'bg-white/[0.04] border-white/[0.08] text-white hover:border-white/20',
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

          {/* Dropdown */}
          {open && (
            <>
              <div className="fixed inset-0 z-10" onClick={() => setOpen(false)} />
              <div className="absolute z-20 mt-1 w-full max-h-80 overflow-y-auto rounded-xl bg-[#111318] border border-white/10 shadow-2xl shadow-black/60 animate-fade-in">
            {/* Search */}
            <div className="sticky top-0 bg-[#111318] p-2 border-b border-white/[0.06]">
                <input
                  autoFocus
                  value={search}
                  onChange={e => setSearch(e.target.value)}
                  placeholder="Search filter types…"
                  className="w-full bg-white/[0.04] border border-white/[0.08] rounded-lg px-3 py-1.5 text-sm text-white placeholder-gray-600 focus:outline-none focus:border-indigo-500 focus:ring-1 focus:ring-indigo-500/30 transition-all"
                />
            </div>
            {/* Groups */}
            {Object.entries(grouped).map(([cat, types]) => (
              <div key={cat}>
                <div className="px-3 py-1.5 text-[10px] font-bold text-gray-600 uppercase tracking-widest bg-white/[0.02]">
                  {cat}
                </div>
                {types.map(ft => (
                  <button
                    key={ft.value}
                    type="button"
                    onClick={() => { onChange(ft.value); setOpen(false); setSearch('') }}
                    className={cn(
                      'w-full flex items-start gap-3 px-3 py-2.5 text-left hover:bg-white/[0.05] transition-colors',
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
        </>
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

  // Populate from existing when editing
  useEffect(() => {
    if (existing) {
      setName(existing.name)
      setDescription(existing.description ?? '')
      setFilterType(existing.filterType)
      setConfig(existing.config ?? {})

      // Enforce isolation rule: standard filters must not have a gateway config ref.
      // If an existing record has one (legacy data), strip it and warn.
      const existingRef = existing.gatewayConfigRef ?? null
      assertStandardFilterIsolation(existing.filterType, !!existingRef)
      setGatewayConfigRef(
        FILTER_TYPES_WITH_CERT_VAULT_REF.has(existing.filterType) ? existingRef : null,
      )
    }
  }, [existing])

  // Reset config to defaults when type changes (create mode)
  const handleTypeChange = (t: FilterType) => {
    setFilterType(t)
    setConfig(DEFAULT_CONFIGS[t] ?? {})
    setGatewayConfigRef(null)
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
  const supportsCertVaultRef = FILTER_TYPES_WITH_CERT_VAULT_REF.has(filterType)

  return (
    <div className="fixed inset-0 bg-black/70 backdrop-blur-sm flex items-center justify-center z-50 p-4 animate-fade-in">
      <div className="bg-[#111318] border border-white/8 rounded-2xl w-full max-w-xl shadow-2xl flex flex-col max-h-[92vh] animate-fade-in-up">

        {/* Header */}
        <div className="flex items-center justify-between px-6 py-4 border-b border-white/[0.06] shrink-0">
          <div className="flex items-center gap-2.5">
            <div className="w-7 h-7 rounded-lg bg-indigo-500/20 border border-indigo-500/30 flex items-center justify-center">
              <Filter className="w-3.5 h-3.5 text-indigo-400" />
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
          <button onClick={onClose} className="p-1.5 rounded-lg text-gray-500 hover:text-white hover:bg-white/[0.05] transition-colors">
            <X className="w-4 h-4" />
          </button>
        </div>

        {/* Body */}
        <div className="flex-1 overflow-y-auto">
          {isEdit && loadingExisting ? (
            <div className="flex items-center justify-center py-16 gap-3">
              <div className="w-5 h-5 border-2 border-indigo-500/30 border-t-indigo-500 rounded-full animate-spin" />
              <p className="text-sm text-gray-500">Loading filter…</p>
            </div>
          ) : (
            <form id="filter-form" onSubmit={handleSubmit} className="p-6 space-y-5">

              {/* Name & Description */}
              <div className="grid grid-cols-1 gap-4">
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
                <div className="space-y-1.5">
                  <div className="flex items-center gap-2">
                    <label className="text-xs font-semibold text-gray-400 uppercase tracking-wider">Description</label>
                    <span className="text-[10px] text-gray-600">optional</span>
                  </div>
                  <input
                    value={description}
                    onChange={e => setDescription(e.target.value)}
                    placeholder="What does this filter do?"
                    className={inputCls}
                  />
                </div>
              </div>

              {/* Filter Type Picker */}
              {!isEdit && (
                <div className="space-y-1.5">
                  <label className="text-xs font-semibold text-gray-400 uppercase tracking-wider">Filter Type *</label>
                  <FilterTypePicker value={filterType} onChange={handleTypeChange} />
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

              {/* Type-specific config */}
              <div className="rounded-xl bg-white/[0.02] border border-white/[0.05] p-4">
                <div className="flex items-center gap-2 mb-4">
                  {selectedMeta && (
                    <span className={cn('text-[10px] font-bold px-2 py-0.5 rounded-full border', CATEGORY_COLORS[selectedMeta.category as keyof typeof CATEGORY_COLORS] ?? '')}>
                      {selectedMeta.category}
                    </span>
                  )}
                  <span className="text-xs font-semibold text-gray-400">
                    {isEdit ? filterType.replace(/_/g, ' ') : (selectedMeta?.label ?? filterType)} Configuration
                  </span>
                  {gatewayConfigRef && supportsCertVaultRef && (
                    <span className="ml-auto text-[10px] text-sky-400 bg-sky-400/10 px-2 py-0.5 rounded-full border border-sky-400/20">
                      ↑ logicalId synced from Cert Vault group
                    </span>
                  )}
                </div>

                {gatewayConfigRef && supportsCertVaultRef && (
                  <div className="mb-4 px-3 py-2.5 rounded-lg bg-sky-500/5 border border-sky-500/20 text-xs text-sky-300/70 leading-relaxed">
                    The <code className="font-mono text-sky-200">logicalId</code> field below has been
                    auto-filled from the linked Cert Vault group{' '}
                    <span className="font-mono text-sky-200">
                      {gatewayConfigRef.refName ?? gatewayConfigRef.refId}
                    </span>.
                    At runtime the gateway certificate registry uses this ID to resolve active certificates.
                  </div>
                )}

                <FilterConfigFields
                  filterType={filterType}
                  config={config}
                  onChange={setConfig}
                />
              </div>

              {/* Error */}
              {error && (
                <div className="flex items-start gap-2.5 p-3.5 bg-red-500/[0.08] border border-red-500/20 rounded-xl">
                  <AlertCircle className="w-4 h-4 text-red-400 mt-0.5 shrink-0" />
                  <span className="text-sm text-red-300">
                    {extractApiError(error, 'Failed to save filter')}
                  </span>
                </div>
              )}
            </form>
          )}
        </div>

        {/* Footer actions */}
        <div className="flex items-center justify-end gap-2 px-6 py-4 border-t border-white/[0.06] shrink-0">
          <button type="button" onClick={onClose}
            className="px-4 py-2 text-sm text-gray-400 hover:text-white hover:bg-white/[0.04] rounded-lg transition-colors">
            Cancel
          </button>
          <button
            type="submit"
            form="filter-form"
            disabled={isPending || (isEdit && loadingExisting)}
            className="px-4 py-2 bg-indigo-600 hover:bg-indigo-500 disabled:opacity-50 text-white text-sm font-semibold rounded-lg transition-all shadow-lg shadow-indigo-500/20"
          >
            {isPending ? 'Saving…' : isEdit ? 'Update Filter' : 'Create Filter'}
          </button>
        </div>
      </div>
    </div>
  )
}
