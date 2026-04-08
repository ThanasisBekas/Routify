/**
 * GlobalFiltersTab — Select existing filters and mark them as globally applied
 * to every route in the gateway.
 *
 * Fetches the full filter catalogue via filtersApi.list(), lets the operator
 * search, toggle, and reorder filters, then persists the selection via
 * gatewayApi.updateGlobalFilters(). Changes are broadcast to all gateway pods
 * via Kafka.
 */
import { useState, useMemo } from 'react'
import { useQuery } from '@tanstack/react-query'
import { Layers, Search, GripVertical, Trash2, Plus, AlertTriangle, ChevronUp, ChevronDown } from 'lucide-react'
import { filtersApi } from '../../../api/filtersApi'
import type { GlobalFilterEntry, FilterType, FilterSummary } from '../../../types'
import { SectionHeader, SaveBar, InfoBanner, EmptyState, Card } from '../components/GatewayPrimitives'
import { cn } from '../../../lib/utils'

// ─── Filter type display helpers ──────────────────────────────────────────────

const FILTER_TYPE_LABELS: Record<string, string> = {
  AUTH_API_KEY: 'API Key Auth',
  AUTH_BASIC: 'Basic Auth',
  AUTH_JWT: 'JWT Auth',
  AUTH_MTLS: 'mTLS Auth',
  AUTH_OAUTH2: 'OAuth2 Introspect',
  AUTH_CLIENT_ID: 'Client ID Auth',
  AUTH_CERT_VAULT: 'Cert Vault Auth',
  DOWNSTREAM_BASIC_AUTH: 'Downstream Basic Auth',
  DOWNSTREAM_BEARER_CC: 'Downstream Bearer CC',
  RATE_LIMIT_FIXED_WINDOW: 'Rate Limit (Fixed)',
  RATE_LIMIT_SLIDING_WINDOW: 'Rate Limit (Sliding)',
  REQUEST_HEADER_MODIFY: 'Request Header Modify',
  RESPONSE_HEADER_MODIFY: 'Response Header Modify',
  BODY_JOLT_TRANSFORM: 'Jolt Transform',
  VALIDATE_JSON_SCHEMA: 'JSON Schema Validate',
  TIMEOUT: 'Timeout',
  CONDITIONAL_ROUTE: 'Conditional Route',
  USER_ID_PAYLOAD_ROUTING: 'User ID Routing',
  CERT_ROTATION: 'Cert Rotation',
  CERT_VAULT_EXPIRY_CHECK: 'Cert Vault Expiry',
  API_VERSIONING: 'API Versioning',
  CORRELATION_ID: 'Correlation ID',
  REQUEST_LOGGER: 'Request Logger',
  TENANT_CONTEXT: 'Tenant Context',
  SECURITY_HEADERS: 'Security Headers',
  CUSTOM_METRIC: 'Custom Metric',
  CUSTOM_SPEL: 'Custom SpEL',
  AI_FILTER: 'AI Filter',
  AI_MODIFIER: 'AI Modifier',
}

function filterTypeLabel(type: FilterType): string {
  return FILTER_TYPE_LABELS[type] ?? type
}

const CATEGORY_COLORS: Record<string, string> = {
  AUTH: 'bg-blue-500/10 text-blue-400 border-blue-500/20',
  RATE_LIMIT: 'bg-amber-500/10 text-amber-400 border-amber-500/20',
  OBSERVABILITY: 'bg-emerald-500/10 text-emerald-400 border-emerald-500/20',
  SECURITY: 'bg-red-500/10 text-red-400 border-red-500/20',
  AI: 'bg-purple-500/10 text-purple-400 border-purple-500/20',
  DEFAULT: 'bg-gray-500/10 text-gray-400 border-gray-500/20',
}

function categoryFor(type: FilterType): string {
  if (type.startsWith('AUTH_') || type.startsWith('DOWNSTREAM_')) return 'AUTH'
  if (type.startsWith('RATE_LIMIT_')) return 'RATE_LIMIT'
  if (['CORRELATION_ID', 'REQUEST_LOGGER', 'TENANT_CONTEXT', 'CUSTOM_METRIC'].includes(type)) return 'OBSERVABILITY'
  if (['SECURITY_HEADERS', 'CERT_ROTATION', 'CERT_VAULT_EXPIRY_CHECK'].includes(type)) return 'SECURITY'
  if (type.startsWith('AI_')) return 'AI'
  return 'DEFAULT'
}

function categoryBadge(type: FilterType) {
  const cat = categoryFor(type)
  const color = CATEGORY_COLORS[cat] ?? CATEGORY_COLORS.DEFAULT
  return (
    <span className={cn('text-[10px] px-1.5 py-0.5 rounded-full border font-medium', color)}>
      {filterTypeLabel(type)}
    </span>
  )
}

// ─── Props ────────────────────────────────────────────────────────────────────

interface Props {
  initial: GlobalFilterEntry[]
  onSave: (entries: GlobalFilterEntry[]) => void
  isPending: boolean
}

// ─── Component ────────────────────────────────────────────────────────────────

export default function GlobalFiltersTab({ initial, onSave, isPending }: Props) {
  const [entries, setEntries] = useState<GlobalFilterEntry[]>(initial ?? [])
  const [search, setSearch] = useState('')
  const [pickerOpen, setPickerOpen] = useState(false)

  const dirty = JSON.stringify(entries) !== JSON.stringify(initial ?? [])

  // Fetch all available filters for the picker
  const { data: filtersPage, isLoading: filtersLoading } = useQuery({
    queryKey: ['filters-for-global-picker'],
    queryFn: () => filtersApi.list({ page: 0, size: 200 }),
    staleTime: 30_000,
  })

  const allFilters = useMemo<FilterSummary[]>(() => filtersPage?.content ?? [], [filtersPage])

  // IDs currently marked as global
  const globalIds = useMemo(() => new Set(entries.map((e) => e.filterId)), [entries])

  // Available (not yet added)
  const available = useMemo(
    () =>
      allFilters
        .filter((f) => !globalIds.has(f.id))
        .filter((f) => {
          if (!search.trim()) return true
          const q = search.toLowerCase()
          return f.name.toLowerCase().includes(q) || f.filterType.toLowerCase().includes(q)
        }),
    [allFilters, globalIds, search],
  )

  // ── Handlers ─────────────────────────────────────────────────────────────

  const addFilter = (f: FilterSummary) => {
    const nextOrder = entries.length > 0 ? Math.max(...entries.map((e) => e.order)) + 1 : 1
    setEntries((prev) => [
      ...prev,
      {
        filterId: f.id,
        filterName: f.name,
        filterType: f.filterType,
        order: nextOrder,
        enabled: true,
      },
    ])
  }

  const removeFilter = (filterId: string) => {
    setEntries((prev) => {
      const next = prev.filter((e) => e.filterId !== filterId)
      return next.map((e, i) => ({ ...e, order: i + 1 }))
    })
  }

  const toggleFilter = (filterId: string) => {
    setEntries((prev) => prev.map((e) => (e.filterId === filterId ? { ...e, enabled: !e.enabled } : e)))
  }

  const moveUp = (idx: number) => {
    if (idx === 0) return
    setEntries((prev) => {
      const next = [...prev]
      ;[next[idx - 1], next[idx]] = [next[idx], next[idx - 1]]
      return next.map((e, i) => ({ ...e, order: i + 1 }))
    })
  }

  const moveDown = (idx: number) => {
    if (idx >= entries.length - 1) return
    setEntries((prev) => {
      const next = [...prev]
      ;[next[idx], next[idx + 1]] = [next[idx + 1], next[idx]]
      return next.map((e, i) => ({ ...e, order: i + 1 }))
    })
  }

  return (
    <div className="max-w-3xl">
      <SectionHeader
        icon={Layers}
        title="Global Filters"
        description="Select existing filters to apply globally across all routes. Global filters execute before per-route filters in the order defined below."
        badge={
          entries.length > 0 ? (
            <span className="text-[10px] bg-indigo-500/10 border border-indigo-500/20 text-indigo-400 px-2 py-0.5 rounded-full font-medium">
              {entries.length} active
            </span>
          ) : undefined
        }
        actions={
          <button
            onClick={() => setPickerOpen((p) => !p)}
            className="flex items-center gap-1.5 px-3 py-1.5 bg-indigo-600 hover:bg-indigo-500 text-white text-xs font-medium rounded-lg transition-colors"
          >
            <Plus className="w-3.5 h-3.5" />
            Add Filter
          </button>
        }
      />

      <InfoBanner variant="info">
        <p className="leading-relaxed">
          Global filters are applied to <strong className="text-indigo-300">every route</strong> in the gateway,
          regardless of the filters configured on individual routes. They execute in the order shown below,
          <strong className="text-indigo-300"> before</strong> any per-route filters. Use this for cross-cutting
          concerns like correlation IDs, request logging, tenant context, and security headers.
        </p>
        <p className="text-amber-400/80 mt-2 leading-relaxed">
          ⚠ Changes are persisted to PostgreSQL and broadcast to all gateway pods via Kafka. All active routes will be
          reloaded.
        </p>
      </InfoBanner>

      {/* ── Filter Picker Modal ─────────────────────────────────────────────── */}
      {pickerOpen && (
        <div className="fixed inset-0 bg-black/70 backdrop-blur-sm flex items-center justify-center z-50 p-4">
          <div className="bg-[#13151a] border border-white/10 rounded-xl w-full max-w-lg shadow-2xl flex flex-col max-h-[80vh]">
            {/* Header */}
            <div className="px-5 pt-5 pb-3 border-b border-white/[0.06]">
              <h3 className="text-base font-semibold text-white mb-3">Add Global Filter</h3>
              <div className="relative">
                <Search className="absolute left-3 top-1/2 -translate-y-1/2 w-4 h-4 text-gray-500" />
                <input
                  type="text"
                  value={search}
                  onChange={(e) => setSearch(e.target.value)}
                  placeholder="Search by name or type…"
                  // eslint-disable-next-line jsx-a11y/no-autofocus
                  autoFocus
                  className="w-full bg-white/[0.04] border border-white/10 rounded-lg pl-10 pr-3 py-2 text-sm text-white focus:outline-none focus:border-indigo-500 focus:ring-1 focus:ring-indigo-500/30 transition-all placeholder-gray-600"
                />
              </div>
            </div>

            {/* Filter list */}
            <div className="flex-1 overflow-auto px-2 py-2">
              {filtersLoading ? (
                <div className="flex items-center justify-center py-10">
                  <div className="w-5 h-5 border-2 border-indigo-500/30 border-t-indigo-500 rounded-full animate-spin" />
                </div>
              ) : available.length === 0 ? (
                <div className="py-10 text-center text-sm text-gray-500">
                  {allFilters.length === globalIds.size
                    ? 'All filters are already added as global filters.'
                    : 'No filters match your search.'}
                </div>
              ) : (
                <div className="space-y-1">
                  {available.map((f) => (
                    <button
                      key={f.id}
                      onClick={() => {
                        addFilter(f)
                        setSearch('')
                      }}
                      className="w-full flex items-center gap-3 px-3 py-2.5 rounded-lg text-left hover:bg-white/[0.04] transition-colors group"
                    >
                      <div className="flex-1 min-w-0">
                        <div className="flex items-center gap-2">
                          <span className="text-sm text-white font-medium truncate">{f.name}</span>
                          {categoryBadge(f.filterType)}
                        </div>
                        <div className="text-[11px] text-gray-500 mt-0.5">
                          Used in {f.usageCount} route{f.usageCount !== 1 ? 's' : ''}
                          {!f.enabled && <span className="text-amber-500 ml-1.5">• disabled</span>}
                        </div>
                      </div>
                      <Plus className="w-4 h-4 text-gray-600 group-hover:text-indigo-400 transition-colors shrink-0" />
                    </button>
                  ))}
                </div>
              )}
            </div>

            {/* Footer */}
            <div className="px-5 py-3 border-t border-white/[0.06] flex justify-end">
              <button
                onClick={() => {
                  setPickerOpen(false)
                  setSearch('')
                }}
                className="px-4 py-2 text-sm text-gray-400 hover:text-white transition-colors"
              >
                Close
              </button>
            </div>
          </div>
        </div>
      )}

      {/* ── Global filter list ──────────────────────────────────────────────── */}
      <div className="mt-6">
        {entries.length === 0 ? (
          <EmptyState
            icon={Layers}
            title="No global filters configured"
            description="Add filters that should execute on every route — correlation IDs, request logging, and security headers are common choices."
            action={
              <button
                onClick={() => setPickerOpen(true)}
                className="flex items-center gap-1.5 px-3 py-1.5 bg-indigo-600 hover:bg-indigo-500 text-white text-xs font-medium rounded-lg transition-colors mt-2"
              >
                <Plus className="w-3.5 h-3.5" />
                Add First Global Filter
              </button>
            }
          />
        ) : (
          <div className="space-y-1.5">
            {entries.map((entry, idx) => (
              <Card key={entry.filterId} padded={false} className="overflow-hidden">
                <div
                  className={cn('flex items-center gap-3 px-4 py-3 transition-colors', !entry.enabled && 'opacity-50')}
                >
                  {/* Order & reorder controls */}
                  <div className="flex flex-col items-center shrink-0 -my-1">
                    <button
                      onClick={() => moveUp(idx)}
                      disabled={idx === 0}
                      className="p-0.5 text-gray-600 hover:text-white disabled:opacity-25 disabled:cursor-not-allowed transition-colors"
                      title="Move up"
                    >
                      <ChevronUp className="w-3.5 h-3.5" />
                    </button>
                    <span className="text-[10px] font-bold text-gray-600 tabular-nums">{entry.order}</span>
                    <button
                      onClick={() => moveDown(idx)}
                      disabled={idx >= entries.length - 1}
                      className="p-0.5 text-gray-600 hover:text-white disabled:opacity-25 disabled:cursor-not-allowed transition-colors"
                      title="Move down"
                    >
                      <ChevronDown className="w-3.5 h-3.5" />
                    </button>
                  </div>

                  {/* Drag handle visual */}
                  <GripVertical className="w-4 h-4 text-gray-700 shrink-0" />

                  {/* Filter info */}
                  <div className="flex-1 min-w-0">
                    <div className="flex items-center gap-2">
                      <span className="text-sm text-white font-medium truncate">{entry.filterName}</span>
                      {categoryBadge(entry.filterType)}
                    </div>
                  </div>

                  {/* Enable/disable toggle */}
                  <button
                    type="button"
                    role="switch"
                    aria-checked={entry.enabled}
                    onClick={() => toggleFilter(entry.filterId)}
                    className={cn(
                      'relative shrink-0 w-8 h-[18px] rounded-full transition-colors focus:outline-none',
                      entry.enabled ? 'bg-indigo-600' : 'bg-gray-700',
                    )}
                    title={entry.enabled ? 'Enabled — click to disable' : 'Disabled — click to enable'}
                  >
                    <span
                      className={cn(
                        'absolute top-[2px] left-[2px] w-[14px] h-[14px] bg-white rounded-full shadow transition-transform duration-150',
                        entry.enabled ? 'translate-x-[14px]' : 'translate-x-0',
                      )}
                    />
                  </button>

                  {/* Remove */}
                  <button
                    onClick={() => removeFilter(entry.filterId)}
                    className="p-1.5 rounded-md text-gray-600 hover:text-red-400 hover:bg-red-400/10 transition-colors"
                    title="Remove from global filters"
                  >
                    <Trash2 className="w-3.5 h-3.5" />
                  </button>
                </div>
              </Card>
            ))}
          </div>
        )}
      </div>

      {/* ── Warning if any disabled filter is in the list ──────────────────── */}
      {entries.some((e) => !e.enabled) && (
        <div className="mt-4">
          <InfoBanner variant="warning">
            <div className="flex items-center gap-2">
              <AlertTriangle className="w-4 h-4 shrink-0" />
              <span>
                Some global filters are <strong>disabled</strong> — they will be skipped during request processing but
                remain in the configuration for easy re-enabling.
              </span>
            </div>
          </InfoBanner>
        </div>
      )}

      <SaveBar onSave={() => onSave(entries)} isPending={isPending} dirty={dirty} />
    </div>
  )
}
