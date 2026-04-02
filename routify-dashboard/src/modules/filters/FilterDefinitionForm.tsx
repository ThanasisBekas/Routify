/**
 * FilterDefinitionForm — create or edit a filter definition.
 * Each filter type renders its own dedicated configuration fields.
 */
import { useState, useEffect } from 'react'
import { useMutation, useQuery } from '@tanstack/react-query'
import { X, AlertCircle, Filter, ChevronDown } from 'lucide-react'
import { filtersApi } from '../../api/filtersApi'
import type { FilterType, CreateFilterRequest, UpdateFilterRequest, GatewayConfigRef } from '../../types'
import { FILTER_TYPES_WITH_GATEWAY_REF } from '../../types'
import FilterConfigFields, { DEFAULT_CONFIGS, type FilterConfig, inputCls } from './FilterConfigFields'
import GatewayConfigRefPicker from './GatewayConfigRefPicker'
import { cn } from '../../lib/utils'
import { extractApiError } from '../../lib/errorUtils'

// ─── Filter type catalogue ────────────────────────────────────────────────────

const FILTER_TYPES: { value: FilterType; label: string; category: string; description: string }[] = [
  // Authentication
  { value: 'AUTH_JWT',        label: 'JWT',              category: 'Authentication', description: 'Validate JWT bearer tokens (RS256/HS256) with optional issuer & audience check' },
  { value: 'AUTH_API_KEY',    label: 'API Key',          category: 'Authentication', description: 'Validate API keys from a configurable header or query parameter' },
  { value: 'AUTH_BASIC',      label: 'Basic Auth',       category: 'Authentication', description: 'Inbound HTTP Basic Authentication against configured credentials' },
  { value: 'AUTH_OAUTH2',     label: 'OAuth2',           category: 'Authentication', description: 'Verify bearer tokens via an OAuth2 introspection endpoint' },
  { value: 'AUTH_MTLS',       label: 'mTLS',             category: 'Authentication', description: 'Mutual TLS — validate client certificate against the certificate registry' },
  { value: 'AUTH_CLIENT_ID',  label: 'Client ID',        category: 'Authentication', description: 'Validate client identity via X-Client-Id and certificate mapping' },
  { value: 'AUTH_CERT_VAULT', label: 'Cert Vault Auth',  category: 'Authentication', description: 'Authenticate caller by verifying their certificate against a Certificate Group in the Cert Vault registry — injects rich X.509 identity headers downstream' },
  // Downstream Auth Injection
  { value: 'DOWNSTREAM_BASIC_AUTH', label: 'Downstream Basic Auth',    category: 'Downstream Auth', description: 'Inject Basic Auth credentials into outbound downstream requests' },
  { value: 'DOWNSTREAM_BEARER_CC',  label: 'Downstream Bearer (CC)',   category: 'Downstream Auth', description: 'Acquire an OAuth2 client-credentials token and inject it as Bearer downstream' },
  // Rate Limiting
  { value: 'RATE_LIMIT_FIXED_WINDOW',   label: 'Fixed Window',   category: 'Rate Limiting', description: 'Redis fixed-window counter (INCR + PEXPIRE)' },
  { value: 'RATE_LIMIT_SLIDING_WINDOW', label: 'Sliding Window', category: 'Rate Limiting', description: 'Redis sliding-window rate limiter (sorted-set algorithm)' },
  // Modification
  { value: 'REQUEST_HEADER_MODIFY',  label: 'Request Headers',  category: 'Modification', description: 'Add, set or remove request headers before forwarding upstream' },
  { value: 'RESPONSE_HEADER_MODIFY', label: 'Response Headers', category: 'Modification', description: 'Add, set or remove response headers after upstream replies' },
  // Transformation
  { value: 'BODY_JOLT_TRANSFORM', label: 'Jolt Transform', category: 'Transformation', description: 'Transform JSON request body with a Jolt Chainr spec' },
  // Validation
  { value: 'VALIDATE_JSON_SCHEMA', label: 'JSON Schema', category: 'Validation', description: 'Validate JSON request body against a JSON Schema (Draft-07 by default)' },
  // Resilience
  { value: 'TIMEOUT', label: 'Timeout', category: 'Resilience', description: 'Enforce a per-route maximum request duration (504 on exceed)' },
  // Observability
  { value: 'CORRELATION_ID',  label: 'Correlation ID',  category: 'Observability', description: 'Inject or propagate X-Correlation-Id header (generate UUID if absent)' },
  { value: 'REQUEST_LOGGER',  label: 'Request Logger',  category: 'Observability', description: 'Log requests/responses and publish telemetry to Kafka' },
  { value: 'TENANT_CONTEXT',  label: 'Tenant Context',  category: 'Observability', description: 'Propagate & validate tenant ID (X-Tenant-Id) for downstream services' },
  { value: 'CUSTOM_METRIC',   label: 'Custom Metric',   category: 'Observability', description: 'Increment a custom Micrometer counter with optional dynamic tags' },
  // Security
  { value: 'SECURITY_HEADERS',        label: 'Security Headers',       category: 'Security', description: 'Inject OWASP security response headers (driven by gateway config)' },
  { value: 'CERT_ROTATION',           label: 'Cert Rotation',          category: 'Security', description: 'Enforce certificate rotation — rejects revoked or unknown client certificates in a Certificate Group' },
  { value: 'CERT_VAULT_EXPIRY_CHECK', label: 'Cert Vault Expiry Check',category: 'Security', description: 'Block or warn when all certificates in a Cert Group are expired, revoked, or approaching expiry' },
  // Versioning
  { value: 'API_VERSIONING', label: 'API Versioning', category: 'Versioning', description: 'Inject API version via header, query param, or path prefix rewrite' },
  // Routing
  { value: 'CONDITIONAL_ROUTE',       label: 'Conditional Route',      category: 'Routing', description: 'Rewrite upstream URI when a header or query param matches a pattern' },
  { value: 'USER_ID_PAYLOAD_ROUTING', label: 'User ID Payload Routing', category: 'Routing', description: 'Route to an alternative upstream when userId in request body is in an allowlist' },
  // Custom
  { value: 'CUSTOM_SPEL', label: 'Custom (SpEL)', category: 'Custom', description: 'Evaluate a Spring Expression Language expression — returning false rejects with 403' },
]

const CATEGORY_ORDER = [
  'Authentication', 'Downstream Auth', 'Rate Limiting', 'Modification', 'Transformation',
  'Validation', 'Resilience', 'Observability', 'Security', 'Versioning', 'Routing', 'Custom',
]

const CATEGORY_COLORS: Record<string, string> = {
  Authentication:    'text-emerald-400 bg-emerald-400/10 border-emerald-400/20',
  'Downstream Auth': 'text-violet-400 bg-violet-400/10 border-violet-400/20',
  'Rate Limiting':   'text-amber-400 bg-amber-400/10 border-amber-400/20',
  Modification:      'text-blue-400 bg-blue-400/10 border-blue-400/20',
  Transformation:    'text-purple-400 bg-purple-400/10 border-purple-400/20',
  Validation:        'text-cyan-400 bg-cyan-400/10 border-cyan-400/20',
  Resilience:        'text-orange-400 bg-orange-400/10 border-orange-400/20',
  Observability:     'text-indigo-400 bg-indigo-400/10 border-indigo-400/20',
  Security:          'text-red-400 bg-red-400/10 border-red-400/20',
  Versioning:        'text-teal-400 bg-teal-400/10 border-teal-400/20',
  Routing:           'text-pink-400 bg-pink-400/10 border-pink-400/20',
  Custom:            'text-gray-400 bg-gray-400/10 border-gray-400/20',
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
              <span className={cn('text-[10px] font-bold px-2 py-0.5 rounded-full border shrink-0', CATEGORY_COLORS[selected.category] ?? '')}>
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
                    <span className={cn('text-[10px] font-bold px-2 py-0.5 rounded-full border shrink-0 mt-0.5', CATEGORY_COLORS[ft.category] ?? '')}>
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
  onClose,
  onSaved,
}: {
  editingId?: string
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
  const [filterType,       setFilterType]       = useState<FilterType>('AUTH_JWT')
  const [config,           setConfig]           = useState<FilterConfig>(DEFAULT_CONFIGS['AUTH_JWT'] ?? {})
  const [gatewayConfigRef, setGatewayConfigRef] = useState<GatewayConfigRef | null>(null)

  // Populate from existing when editing
  useEffect(() => {
    if (existing) {
      setName(existing.name)
      setDescription(existing.description ?? '')
      setFilterType(existing.filterType)
      setConfig(existing.config ?? {})
      setGatewayConfigRef(existing.gatewayConfigRef ?? null)
    }
  }, [existing])

  // Reset config to defaults when type changes (create mode)
  const handleTypeChange = (t: FilterType) => {
    setFilterType(t)
    setConfig(DEFAULT_CONFIGS[t] ?? {})
    setGatewayConfigRef(null)
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
    if (isEdit) {
      updateMutation.mutate({
        name: name.trim(),
        description: description.trim() || undefined,
        config,
        gatewayConfigRef: gatewayConfigRef ?? null,
      })
    } else {
      createMutation.mutate({
        name: name.trim(),
        description: description.trim() || undefined,
        filterType,
        config,
        gatewayConfigRef: gatewayConfigRef ?? undefined,
      })
    }
  }

  const selectedMeta = FILTER_TYPES.find(f => f.value === filterType)
  const supportsGatewayRef = !!FILTER_TYPES_WITH_GATEWAY_REF[filterType]

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

              {/* Gateway Config Link — for filter types that can reference gateway config */}
              {supportsGatewayRef && (
                <GatewayConfigRefPicker
                  filterType={filterType}
                  value={gatewayConfigRef}
                  onChange={setGatewayConfigRef}
                  onNavigateToGateway={() => {
                    // Open gateway page in a new tab — users can also navigate via the sidebar
                    window.open('/gateway', '_blank')
                  }}
                />
              )}

              {/* Type-specific config */}
              <div className="rounded-xl bg-white/[0.02] border border-white/[0.05] p-4">
                <div className="flex items-center gap-2 mb-4">
                  {selectedMeta && (
                    <span className={cn('text-[10px] font-bold px-2 py-0.5 rounded-full border', CATEGORY_COLORS[selectedMeta.category] ?? '')}>
                      {selectedMeta.category}
                    </span>
                  )}
                  <span className="text-xs font-semibold text-gray-400">
                    {isEdit ? filterType.replace(/_/g, ' ') : (selectedMeta?.label ?? filterType)} Configuration
                  </span>
                  {gatewayConfigRef && (
                    <span className="ml-auto text-[10px] text-indigo-400 bg-indigo-400/10 px-2 py-0.5 rounded-full border border-indigo-400/20">
                      ↑ overridden by gateway link
                    </span>
                  )}
                </div>

                {gatewayConfigRef && (
                  <div className="mb-4 px-3 py-2.5 rounded-lg bg-indigo-500/5 border border-indigo-500/20 text-xs text-indigo-300/70 leading-relaxed">
                    These local fields act as <strong className="text-indigo-200">fallback defaults</strong> only.
                    At runtime the gateway will resolve the linked <span className="font-mono text-indigo-200">
                    {gatewayConfigRef.refName ?? gatewayConfigRef.refId}</span> configuration
                    and use it as the authoritative source.
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
