/**
 * RouteFormModal — unified create / edit modal for routes.
 *
 * Two tabs:
 *   1. "Route"   — name, description, path, methods, upstream, strip-prefix
 *   2. "Filters" — filter-chain management (attach / detach / reorder)
 *
 * On create the Filters tab is unlocked automatically once the route is saved
 * and the API returns the new route id.
 */
import { useState, useEffect } from 'react'
import { useForm } from 'react-hook-form'
import { zodResolver } from '@hookform/resolvers/zod'
import { z } from 'zod'
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import {
  X, Route, Filter, AlertCircle, Play, Pause,
  Shield, Zap, RefreshCw, GitBranch, Gauge, Code2,
  ToggleLeft, Plus, Trash2, ChevronRight, Search,
} from 'lucide-react'
import { routesApi } from '../../api/routesApi'
import { filtersApi } from '../../api/filtersApi'
import type {
  CreateRouteRequest, UpdateRouteRequest, RouteFilterRef,
  FilterSummary, AttachFilterRequest,
} from '../../types'
import { cn } from '../../lib/utils'
import { extractApiError } from '../../lib/errorUtils'
import { METHOD_OPTIONS, METHOD_COLORS_MODAL as METHOD_COLORS } from './routeConstants'

// ─── Schema ────────────────────────────────────────────────────────────────────

const schema = z.object({
  name:        z.string().min(3).max(255),
  description: z.string().max(1000).optional(),
  pathPattern: z.string().min(1).startsWith('/'),
  methods:     z.string().min(1),
  upstreamUri: z.string().regex(/^(https?:\/\/.+|lb:\/\/.+)/, 'Must be http(s):// or lb://'),
  stripPrefix: z.string().optional(),
})
type FormData = z.infer<typeof schema>

// ─── Shared style tokens ───────────────────────────────────────────────────────

const inputCls =
  'w-full bg-white/[0.04] border border-white/[0.08] rounded-lg px-3 py-2.5 text-sm text-white placeholder-gray-600 focus:outline-none focus:border-indigo-500 focus:ring-1 focus:ring-indigo-500/30 transition-all'
const monoInputCls = `${inputCls} font-mono`

// ─── Filter type → icon map (same as RouteDetailDrawer) ───────────────────────

const FILTER_ICONS: Record<string, React.ReactNode> = {
  AUTH_JWT:                <Shield className="w-3.5 h-3.5 text-green-400" />,
  AUTH_API_KEY:            <Shield className="w-3.5 h-3.5 text-blue-400" />,
  RATE_LIMIT_TOKEN_BUCKET: <Gauge className="w-3.5 h-3.5 text-yellow-400" />,
  RATE_LIMIT_FIXED_WINDOW: <Gauge className="w-3.5 h-3.5 text-amber-400" />,
  RATE_LIMIT_SLIDING_WINDOW:<Gauge className="w-3.5 h-3.5 text-orange-400" />,
  CIRCUIT_BREAKER:         <RefreshCw className="w-3.5 h-3.5 text-orange-400" />,
  BODY_JOLT_TRANSFORM:     <Code2 className="w-3.5 h-3.5 text-purple-400" />,
  BODY_JSONATA_TRANSFORM:  <Code2 className="w-3.5 h-3.5 text-fuchsia-400" />,
  CONDITIONAL_ROUTE:       <GitBranch className="w-3.5 h-3.5 text-indigo-400" />,
  API_VERSIONING:          <ToggleLeft className="w-3.5 h-3.5 text-cyan-400" />,
}

const FILTER_CAT_COLORS: Record<string, string> = {
  Authentication: 'text-emerald-400 bg-emerald-400/10 border-emerald-400/20',
  'Rate Limiting': 'text-amber-400 bg-amber-400/10 border-amber-400/20',
  Modification:   'text-blue-400 bg-blue-400/10 border-blue-400/20',
  Transformation: 'text-purple-400 bg-purple-400/10 border-purple-400/20',
  Validation:     'text-cyan-400 bg-cyan-400/10 border-cyan-400/20',
  Resilience:     'text-orange-400 bg-orange-400/10 border-orange-400/20',
  Observability:  'text-indigo-400 bg-indigo-400/10 border-indigo-400/20',
  Security:       'text-red-400 bg-red-400/10 border-red-400/20',
  Versioning:     'text-teal-400 bg-teal-400/10 border-teal-400/20',
  Routing:        'text-pink-400 bg-pink-400/10 border-pink-400/20',
  Custom:         'text-gray-400 bg-gray-400/10 border-gray-400/20',
}

/** Derive a rough category label from a FilterType string */
function filterCategory(type: string): string {
  if (type.startsWith('AUTH_'))              return 'Authentication'
  if (type.startsWith('RATE_LIMIT_'))        return 'Rate Limiting'
  if (type.startsWith('REQUEST_HEADER_') || type.startsWith('RESPONSE_HEADER_') ||
      type.startsWith('PATH_') || type.startsWith('QUERY_')) return 'Modification'
  if (type.startsWith('BODY_'))             return 'Transformation'
  if (type.startsWith('VALIDATE_'))         return 'Validation'
  if (type === 'CIRCUIT_BREAKER' || type === 'RETRY' || type === 'TIMEOUT') return 'Resilience'
  if (type === 'SECURITY_HEADERS' || type === 'CERT_ROTATION') return 'Security'
  if (type === 'API_VERSIONING')            return 'Versioning'
  if (type === 'CONDITIONAL_ROUTE')         return 'Routing'
  if (type === 'CORRELATION_ID' || type === 'REQUEST_LOGGER' ||
      type === 'TENANT_CONTEXT' || type === 'CUSTOM_METRIC') return 'Observability'
  return 'Custom'
}

// ─── Props ─────────────────────────────────────────────────────────────────────

interface Props {
  /** Present → edit mode; absent → create mode */
  editingId?: string
  onClose: () => void
  onSaved: () => void
}

// ═══════════════════════════════════════════════════════════════════════════════
// RouteFormModal
// ═══════════════════════════════════════════════════════════════════════════════

export default function RouteFormModal({ editingId, onClose, onSaved }: Props) {
  const qc = useQueryClient()
  const isEdit = !!editingId

  const [tab, setTab] = useState<'route' | 'filters'>('route')
  // After a successful create, the new route id is stored here to unlock the Filters tab
  const [createdRouteId, setCreatedRouteId] = useState<string | undefined>(editingId)
  const activeRouteId = createdRouteId ?? editingId

  // ── Load existing route when editing ───────────────────────────────────────
  const { data: existing, isLoading: loadingExisting } = useQuery({
    queryKey: ['route', editingId],
    queryFn: () => routesApi.get(editingId!),
    enabled: isEdit,
  })

  // ── Form ───────────────────────────────────────────────────────────────────
  const {
    register,
    handleSubmit,
    watch,
    setValue,
    reset,
    formState: { errors },
  } = useForm<FormData>({
    resolver: zodResolver(schema),
    defaultValues: { methods: 'GET', pathPattern: '/' },
  })

  useEffect(() => {
    if (existing) {
      reset({
        name:        existing.name,
        description: existing.description ?? '',
        pathPattern: existing.pathPattern,
        methods:     existing.methods,
        upstreamUri: existing.upstreamUri,
        stripPrefix: existing.stripPrefix ?? '',
      })
    }
  }, [existing, reset])

  const selectedMethods = watch('methods').split(',').map(m => m.trim()).filter(Boolean)

  const toggleMethod = (m: string) => {
    if (m === '*') { setValue('methods', '*'); return }
    let current = watch('methods').split(',').map(x => x.trim()).filter(x => x && x !== '*')
    if (current.includes(m)) current = current.filter(x => x !== m)
    else current.push(m)
    setValue('methods', current.join(',') || 'GET')
  }

  // ── Create mutation ─────────────────────────────────────────────────────────
  const createMutation = useMutation({
    mutationFn: (data: CreateRouteRequest) => routesApi.create(data),
    onSuccess: (route) => {
      qc.invalidateQueries({ queryKey: ['routes'] })
      setCreatedRouteId(route.id)
      setTab('filters')   // auto-advance to filters tab
    },
  })

  // ── Update mutation ─────────────────────────────────────────────────────────
  const updateMutation = useMutation({
    mutationFn: (data: UpdateRouteRequest) => routesApi.update(editingId!, data),
    onSuccess: () => {
      qc.invalidateQueries({ queryKey: ['routes'] })
      qc.invalidateQueries({ queryKey: ['route', editingId] })
      setTab('filters')
    },
  })

  const isPending    = createMutation.isPending || updateMutation.isPending
  const submitError  = createMutation.error ?? updateMutation.error
  const routeSaved   = !!createdRouteId || (isEdit && !loadingExisting)

  const onSubmit = (data: FormData) => {
    if (isEdit) {
      updateMutation.mutate(data)
    } else {
      createMutation.mutate(data)
    }
  }

  return (
    <div className="fixed inset-0 bg-black/70 backdrop-blur-sm flex items-center justify-center z-50 p-4 animate-fade-in">
      <div className="bg-[#111318] border border-white/[0.08] rounded-2xl w-full max-w-2xl shadow-2xl flex flex-col max-h-[92vh] animate-fade-in-up">

        {/* ── Header ────────────────────────────────────────────────────────── */}
        <div className="flex items-center justify-between px-6 py-4 border-b border-white/[0.06] shrink-0">
          <div className="flex items-center gap-2.5">
            <div className="w-7 h-7 rounded-lg bg-indigo-500/20 border border-indigo-500/30 flex items-center justify-center shrink-0">
              <Route className="w-3.5 h-3.5 text-indigo-400" />
            </div>
            <div>
              <h2 className="text-sm font-bold text-white leading-tight">
                {isEdit ? `Edit Route` : 'Create New Route'}
              </h2>
              {existing && (
                <p className="text-[11px] text-gray-500 mt-0.5 leading-tight">{existing.name}</p>
              )}
            </div>
          </div>
          <button
            onClick={onClose}
            className="p-1.5 rounded-lg text-gray-500 hover:text-white hover:bg-white/[0.05] transition-colors"
          >
            <X className="w-4 h-4" />
          </button>
        </div>

        {/* ── Tab Bar ───────────────────────────────────────────────────────── */}
        <div className="flex items-center gap-1 px-6 pt-3 shrink-0 border-b border-white/[0.06] pb-0">
          <TabButton
            active={tab === 'route'}
            onClick={() => setTab('route')}
            icon={<Route className="w-3.5 h-3.5" />}
            label="Route"
          />
          <TabButton
            active={tab === 'filters'}
            onClick={() => setTab('filters')}
            icon={<Filter className="w-3.5 h-3.5" />}
            label="Filters"
            disabled={!routeSaved}
            badge={existing?.filters?.length ?? 0}
          />
          {!routeSaved && (
            <span className="ml-auto text-[10px] text-gray-600 pb-3">
              Save the route first to manage filters
            </span>
          )}
        </div>

        {/* ── Body ──────────────────────────────────────────────────────────── */}
        <div className="flex-1 overflow-y-auto">

          {/* ── TAB: Route ─────────────────────────────────────────────────── */}
          {tab === 'route' && (
            <form id="route-form" onSubmit={handleSubmit(onSubmit)} className="p-6 space-y-4">

              {/* Live route warning */}
              {existing?.status === 'ACTIVE' && (
                <div className="flex items-start gap-2.5 p-3.5 bg-amber-500/[0.07] border border-amber-500/20 rounded-xl text-xs text-amber-300">
                  <Zap className="w-4 h-4 mt-0.5 shrink-0 text-amber-400" />
                  <span>
                    This route is <strong>live</strong>. Changes to path, methods and upstream apply
                    instantly via Kafka hot-reload — no restart needed.
                  </span>
                </div>
              )}

              {/* Name */}
              <div className="space-y-1.5">
                <label className="block text-xs font-semibold text-gray-400 uppercase tracking-wider">
                  Route Name *
                </label>
                <input {...register('name')} placeholder="e.g. orders-api-v1" className={inputCls} />
                {errors.name && <p className="text-xs text-red-400">{errors.name.message}</p>}
              </div>

              {/* Description */}
              <div className="space-y-1.5">
                <label className="block text-xs font-semibold text-gray-400 uppercase tracking-wider">
                  Description <span className="normal-case font-normal text-gray-600">optional</span>
                </label>
                <textarea
                  {...register('description')}
                  rows={2}
                  placeholder="What does this route handle?"
                  className={`${inputCls} resize-none`}
                />
              </div>

              {/* Path Pattern */}
              <div className="space-y-1.5">
                <label className="block text-xs font-semibold text-gray-400 uppercase tracking-wider">
                  Path Pattern *
                </label>
                <input {...register('pathPattern')} placeholder="/api/v1/orders/**" className={monoInputCls} />
                <p className="text-[11px] text-gray-600 pl-0.5">
                  Wildcards: <code className="font-mono">/api/**</code> · Path vars: <code className="font-mono">/users/&#123;id&#125;</code>
                </p>
                {errors.pathPattern && <p className="text-xs text-red-400">{errors.pathPattern.message}</p>}
              </div>

              {/* HTTP Methods */}
              <div className="space-y-2">
                <label className="block text-xs font-semibold text-gray-400 uppercase tracking-wider">
                  HTTP Methods *
                </label>
                <div className="flex gap-1.5 flex-wrap">
                  {METHOD_OPTIONS.map((m) => {
                    const active = m === '*' ? selectedMethods.includes('*') : selectedMethods.includes(m)
                    return (
                      <button
                        key={m}
                        type="button"
                        onClick={() => toggleMethod(m)}
                        className={cn(
                          'px-3 py-1.5 rounded-lg text-xs font-mono font-bold transition-all border',
                          active
                            ? METHOD_COLORS[m] ?? 'bg-indigo-600 text-white border-indigo-500/50'
                            : 'bg-white/[0.04] text-gray-500 border-white/[0.08] hover:border-white/20 hover:text-gray-300',
                        )}
                      >
                        {m}
                      </button>
                    )
                  })}
                </div>
                {errors.methods && <p className="text-xs text-red-400">{errors.methods.message}</p>}
              </div>

              {/* Upstream URI */}
              <div className="space-y-1.5">
                <label className="block text-xs font-semibold text-gray-400 uppercase tracking-wider">
                  Upstream URI *
                </label>
                <input
                  {...register('upstreamUri')}
                  placeholder="http://orders-service:8080 or lb://orders-service"
                  className={monoInputCls}
                />
                <p className="text-[11px] text-gray-600 pl-0.5">
                  Use <code className="font-mono">lb://</code> for load-balanced service discovery
                </p>
                {errors.upstreamUri && <p className="text-xs text-red-400">{errors.upstreamUri.message}</p>}
              </div>

              {/* Strip Prefix */}
              <div className="space-y-1.5">
                <label className="block text-xs font-semibold text-gray-400 uppercase tracking-wider">
                  Strip Prefix <span className="normal-case font-normal text-gray-600">optional</span>
                </label>
                <input
                  {...register('stripPrefix')}
                  placeholder="/api/v1 (stripped before forwarding)"
                  className={monoInputCls}
                />
              </div>

              {/* Submit error */}
              {submitError && (
                <div className="flex items-start gap-2.5 p-3.5 bg-red-500/[0.08] border border-red-500/20 rounded-xl text-sm text-red-300">
                  <AlertCircle className="w-4 h-4 mt-0.5 shrink-0 text-red-400" />
                  <span>{extractApiError(submitError, 'Failed to save route')}</span>
                </div>
              )}

              {/* Draft info (create only) */}
              {!isEdit && (
                <div className="flex items-start gap-2.5 p-3.5 bg-indigo-500/[0.06] border border-indigo-500/20 rounded-xl text-xs text-indigo-300">
                  <span className="mt-0.5 shrink-0">💡</span>
                  <span>
                    The route starts in <strong>DRAFT</strong> status. After saving you can attach
                    filters, then activate when ready.
                  </span>
                </div>
              )}
            </form>
          )}

          {/* ── TAB: Filters ───────────────────────────────────────────────── */}
          {tab === 'filters' && activeRouteId && (
            <FilterChainTab routeId={activeRouteId} />
          )}

          {tab === 'filters' && !activeRouteId && (
            <div className="flex flex-col items-center justify-center py-20 gap-3">
              <Filter className="w-10 h-10 text-gray-700" />
              <p className="text-sm text-gray-500">Save the route first to manage its filter chain.</p>
            </div>
          )}
        </div>

        {/* ── Footer ────────────────────────────────────────────────────────── */}
        <div className="flex items-center justify-between gap-3 px-6 py-4 border-t border-white/[0.06] shrink-0">
          {/* Left: "Done" shortcut shown after route saved */}
          {routeSaved && tab === 'filters' ? (
            <button
              onClick={onSaved}
              className="flex items-center gap-1.5 text-sm text-indigo-400 hover:text-indigo-300 transition-colors"
            >
              <ChevronRight className="w-4 h-4" />
              Done
            </button>
          ) : (
            <div />
          )}

          <div className="flex items-center gap-2">
            <button
              type="button"
              onClick={onClose}
              className="px-4 py-2 text-sm text-gray-400 hover:text-white hover:bg-white/[0.04] rounded-lg transition-colors"
            >
              Cancel
            </button>

            {tab === 'route' && (
              <button
                type="submit"
                form="route-form"
                disabled={isPending || (isEdit && loadingExisting)}
                className="px-4 py-2 bg-indigo-600 hover:bg-indigo-500 disabled:opacity-50 text-white text-sm font-semibold rounded-lg transition-all shadow-lg shadow-indigo-500/20"
              >
                {isPending
                  ? (isEdit ? 'Saving…' : 'Creating…')
                  : (isEdit ? 'Save Changes' : 'Create Route')}
              </button>
            )}

            {tab === 'filters' && (
              <button
                onClick={onSaved}
                className="px-4 py-2 bg-indigo-600 hover:bg-indigo-500 text-white text-sm font-semibold rounded-lg transition-all shadow-lg shadow-indigo-500/20"
              >
                Finish
              </button>
            )}
          </div>
        </div>
      </div>
    </div>
  )
}

// ─── TabButton ─────────────────────────────────────────────────────────────────

function TabButton({
  active, onClick, icon, label, disabled, badge,
}: {
  active: boolean
  onClick: () => void
  icon: React.ReactNode
  label: string
  disabled?: boolean
  badge?: number
}) {
  return (
    <button
      type="button"
      disabled={disabled}
      onClick={onClick}
      className={cn(
        'flex items-center gap-1.5 px-3 py-2.5 text-xs font-semibold border-b-2 transition-all relative',
        active
          ? 'text-white border-indigo-500'
          : disabled
            ? 'text-gray-700 border-transparent cursor-not-allowed'
            : 'text-gray-500 border-transparent hover:text-gray-300 hover:border-white/20',
      )}
    >
      {icon}
      {label}
      {badge !== undefined && badge > 0 && (
        <span className="ml-0.5 text-[10px] px-1.5 py-0.5 rounded-full bg-indigo-500/20 text-indigo-300 font-bold">
          {badge}
        </span>
      )}
    </button>
  )
}

// ═══════════════════════════════════════════════════════════════════════════════
// FilterChainTab — full filter-chain management embedded in the modal
// ═══════════════════════════════════════════════════════════════════════════════

function FilterChainTab({ routeId }: { routeId: string }) {
  const qc = useQueryClient()

  const { data: route, isLoading } = useQuery({
    queryKey: ['route', routeId],
    queryFn: () => routesApi.get(routeId),
  })

  const invalidate = () => qc.invalidateQueries({ queryKey: ['route', routeId] })

  // Attach state
  const [attachOpen, setAttachOpen]   = useState(false)
  const [filterSearch, setFilterSearch] = useState('')
  const [selectedId, setSelectedId]   = useState('')
  const [order, setOrder]             = useState(10)
  const [phase, setPhase]             = useState<'PRE' | 'POST'>('PRE')

  const { data: availableData } = useQuery({
    queryKey: ['filters-list'],
    queryFn: () => filtersApi.list({ size: 100 }),
    enabled: attachOpen,
  })

  const available: FilterSummary[] = availableData?.content ?? []

  const filtered = filterSearch
    ? available.filter(f =>
        f.name.toLowerCase().includes(filterSearch.toLowerCase()) ||
        f.filterType.toLowerCase().includes(filterSearch.toLowerCase()),
      )
    : available

  // Group by category
  const grouped = filtered.reduce<Record<string, FilterSummary[]>>((acc, f) => {
    const cat = filterCategory(f.filterType)
    ;(acc[cat] ??= []).push(f)
    return acc
  }, {})

  const attachMutation = useMutation({
    mutationFn: (req: AttachFilterRequest) => routesApi.attachFilter(routeId, req),
    onSuccess: () => {
      invalidate()
      setAttachOpen(false)
      setSelectedId('')
    },
  })

  const detachMutation = useMutation({
    mutationFn: (filterId: string) => routesApi.detachFilter(routeId, filterId),
    onSuccess: invalidate,
  })

  const statusMutateActivate = useMutation({
    mutationFn: () => routesApi.activate(routeId),
    onSuccess: () => { invalidate(); qc.invalidateQueries({ queryKey: ['routes'] }) },
  })
  const statusMutateDeactivate = useMutation({
    mutationFn: () => routesApi.deactivate(routeId),
    onSuccess: () => { invalidate(); qc.invalidateQueries({ queryKey: ['routes'] }) },
  })

  if (isLoading) {
    return (
      <div className="flex items-center justify-center py-20 gap-3">
        <div className="w-5 h-5 border-2 border-indigo-500/30 border-t-indigo-500 rounded-full animate-spin" />
        <p className="text-sm text-gray-500">Loading filter chain…</p>
      </div>
    )
  }

  if (!route) return null

  const filters = route.filters ?? []
  const preFilters  = [...filters].filter(f => f.phase === 'PRE').sort((a, b) => a.order - b.order)
  const postFilters = [...filters].filter(f => f.phase === 'POST').sort((a, b) => a.order - b.order)

  return (
    <div className="p-6 space-y-5">
      {/* Status bar */}
      <div className="flex items-center justify-between p-3.5 bg-white/[0.02] border border-white/[0.06] rounded-xl">
        <div className="flex items-center gap-3 text-sm">
          <div className={cn('w-2 h-2 rounded-full shrink-0', {
            'bg-emerald-400 shadow-[0_0_6px_rgba(52,211,153,0.6)]': route.status === 'ACTIVE',
            'bg-amber-400': route.status === 'DRAFT',
            'bg-gray-500':  route.status === 'DISABLED',
            'bg-red-500':   route.status === 'ARCHIVED',
          })} />
          <span className="text-gray-400">
            Status: <span className="text-white font-semibold">{route.status}</span>
            <span className="text-gray-600 mx-1.5">·</span>
            <span className="text-gray-500">v{route.version}</span>
            <span className="text-gray-600 mx-1.5">·</span>
            <span className="text-gray-500">{filters.length} filter{filters.length !== 1 ? 's' : ''}</span>
          </span>
        </div>
        <div className="flex items-center gap-2">
          {(route.status === 'DRAFT' || route.status === 'DISABLED') && (
            <button
              onClick={() => statusMutateActivate.mutate()}
              disabled={statusMutateActivate.isPending}
              className="flex items-center gap-1.5 px-3 py-1.5 bg-emerald-600/80 hover:bg-emerald-600 disabled:opacity-50 text-white text-xs font-semibold rounded-lg transition-colors"
            >
              <Play className="w-3 h-3" />
              {statusMutateActivate.isPending ? 'Activating…' : 'Activate'}
            </button>
          )}
          {route.status === 'ACTIVE' && (
            <button
              onClick={() => statusMutateDeactivate.mutate()}
              disabled={statusMutateDeactivate.isPending}
              className="flex items-center gap-1.5 px-3 py-1.5 bg-amber-600/70 hover:bg-amber-600 disabled:opacity-50 text-white text-xs font-semibold rounded-lg transition-colors"
            >
              <Pause className="w-3 h-3" />
              Deactivate
            </button>
          )}
        </div>
      </div>

      {/* Live banner */}
      {route.status === 'ACTIVE' && (
        <div className="flex items-center gap-2 p-3 bg-emerald-500/5 border border-emerald-500/20 rounded-lg text-xs text-emerald-400">
          <Zap className="w-4 h-4 shrink-0" />
          <span>Route is <strong>live</strong>. Filter chain changes apply instantly via Kafka hot-reload.</span>
        </div>
      )}

      {/* Filter chain header */}
      <div className="flex items-center justify-between">
        <h3 className="text-sm font-semibold text-white flex items-center gap-2">
          <Filter className="w-4 h-4 text-indigo-400" />
          Filter Chain
        </h3>
        <button
          onClick={() => setAttachOpen(v => !v)}
          className="flex items-center gap-1.5 text-xs font-semibold text-indigo-400 hover:text-indigo-300 transition-colors px-2.5 py-1.5 rounded-lg hover:bg-indigo-500/10 border border-transparent hover:border-indigo-500/20"
        >
          <Plus className="w-3.5 h-3.5" />
          Attach Filter
        </button>
      </div>

      {/* Filter list */}
      {filters.length === 0 && !attachOpen ? (
        <div className="text-center py-10 text-gray-600 text-sm bg-white/[0.02] rounded-xl border border-dashed border-white/[0.08] space-y-2">
          <Filter className="w-7 h-7 text-gray-700 mx-auto" />
          <p>No filters attached yet</p>
          <p className="text-xs text-gray-700">Routes work without filters — attach any to add auth, rate-limiting, transformations and more.</p>
        </div>
      ) : (
        <div className="space-y-1.5">
          {preFilters.length > 0 && (
            <>
              <div className="text-[9px] text-blue-400/70 font-bold uppercase tracking-widest px-1 mb-1">
                PRE — Before Upstream
              </div>
              {preFilters.map(f => (
                <FilterItem key={f.filterId} f={f} onDetach={() => detachMutation.mutate(f.filterId)} />
              ))}
            </>
          )}

          {preFilters.length > 0 && postFilters.length > 0 && (
            <div className="flex items-center gap-2 py-2 px-1">
              <div className="flex-1 h-px bg-white/[0.05]" />
              <span className="text-[9px] text-gray-600 uppercase tracking-widest font-medium">↕ Upstream</span>
              <div className="flex-1 h-px bg-white/[0.05]" />
            </div>
          )}

          {postFilters.length > 0 && (
            <>
              <div className="text-[9px] text-purple-400/70 font-bold uppercase tracking-widest px-1 mb-1">
                POST — After Upstream
              </div>
              {postFilters.map(f => (
                <FilterItem key={f.filterId} f={f} onDetach={() => detachMutation.mutate(f.filterId)} />
              ))}
            </>
          )}
        </div>
      )}

      {/* ── Attach panel ──────────────────────────────────────────────────────── */}
      {attachOpen && (
        <div className="rounded-xl border border-indigo-500/20 bg-indigo-500/[0.03] overflow-hidden">
          <div className="px-4 py-3 border-b border-white/[0.06] flex items-center justify-between">
            <h4 className="text-sm font-semibold text-white flex items-center gap-2">
              <Plus className="w-4 h-4 text-indigo-400" />
              Attach a Filter
            </h4>
            <button
              onClick={() => { setAttachOpen(false); setSelectedId(''); setFilterSearch('') }}
              className="p-1 rounded-md text-gray-500 hover:text-white hover:bg-white/[0.05] transition-colors"
            >
              <X className="w-3.5 h-3.5" />
            </button>
          </div>

          <div className="p-4 space-y-4">
            {/* Searchable filter picker */}
            <div className="space-y-1.5">
              <label className="text-xs font-semibold text-gray-400 uppercase tracking-wider">
                Filter Definition *
              </label>

              {/* Search box */}
              <div className="relative">
                <Search className="absolute left-3 top-1/2 -translate-y-1/2 w-3.5 h-3.5 text-gray-600 pointer-events-none" />
                <input
                  value={filterSearch}
                  onChange={e => setFilterSearch(e.target.value)}
                  placeholder="Search by name or type…"
                  className="w-full bg-white/[0.04] border border-white/[0.08] rounded-lg pl-9 pr-3 py-2 text-sm text-white placeholder-gray-600 focus:outline-none focus:border-indigo-500 focus:ring-1 focus:ring-indigo-500/30 transition-all"
                />
              </div>

              {/* Filter option list */}
              <div className="max-h-52 overflow-y-auto rounded-lg border border-white/[0.08] bg-[#0d0f14] divide-y divide-white/[0.04]">
                {Object.keys(grouped).length === 0 && (
                  <p className="text-xs text-gray-600 text-center py-6">
                    {filterSearch ? `No filters match "${filterSearch}"` : 'No filters defined yet — create one in the Filters page.'}
                  </p>
                )}
                {Object.entries(grouped).map(([cat, items]) => (
                  <div key={cat}>
                    <div className="px-3 py-1 text-[9px] font-bold text-gray-600 uppercase tracking-widest bg-white/[0.02]">
                      {cat}
                    </div>
                    {items.map(f => {
                      const catColor = FILTER_CAT_COLORS[filterCategory(f.filterType)] ?? ''
                      const alreadyAttached = filters.some(rf => rf.filterId === f.id)
                      return (
                        <button
                          key={f.id}
                          type="button"
                          disabled={alreadyAttached}
                          onClick={() => setSelectedId(f.id)}
                          className={cn(
                            'w-full flex items-center gap-3 px-3 py-2.5 text-left transition-colors',
                            selectedId === f.id
                              ? 'bg-indigo-500/15'
                              : alreadyAttached
                                ? 'opacity-40 cursor-not-allowed'
                                : 'hover:bg-white/[0.04]',
                          )}
                        >
                          <div className="shrink-0">
                            {FILTER_ICONS[f.filterType] ?? <Filter className="w-3.5 h-3.5 text-gray-400" />}
                          </div>
                          <div className="flex-1 min-w-0">
                            <div className={cn('text-sm font-medium truncate', selectedId === f.id ? 'text-indigo-200' : 'text-gray-200')}>
                              {f.name}
                            </div>
                            <div className="flex items-center gap-1.5 mt-0.5">
                              <span className={cn('text-[9px] font-bold px-1.5 py-0.5 rounded-full border', catColor)}>
                                {f.filterType.replace(/_/g, ' ')}
                              </span>
                              {f.usageCount > 0 && (
                                <span className="text-[10px] text-gray-600">
                                  used on {f.usageCount} route{f.usageCount !== 1 ? 's' : ''}
                                </span>
                              )}
                              {alreadyAttached && (
                                <span className="text-[10px] text-indigo-400">already attached</span>
                              )}
                            </div>
                          </div>
                          {selectedId === f.id && (
                            <span className="text-indigo-400 text-xs shrink-0">✓</span>
                          )}
                        </button>
                      )
                    })}
                  </div>
                ))}
              </div>
            </div>

            {/* Phase & Order */}
            <div className="grid grid-cols-2 gap-3">
              <div className="space-y-1.5">
                <label className="text-xs font-semibold text-gray-400 uppercase tracking-wider">Phase</label>
                <div className="flex rounded-lg border border-white/[0.08] overflow-hidden">
                  {(['PRE', 'POST'] as const).map(p => (
                    <button
                      key={p}
                      type="button"
                      onClick={() => setPhase(p)}
                      className={cn(
                        'flex-1 py-2 text-xs font-semibold transition-all',
                        phase === p
                          ? p === 'PRE'
                            ? 'bg-blue-600/70 text-blue-100'
                            : 'bg-purple-600/70 text-purple-100'
                          : 'text-gray-500 hover:text-gray-300 bg-white/[0.02] hover:bg-white/[0.04]',
                      )}
                    >
                      {p === 'PRE' ? '↑ PRE' : '↓ POST'}
                    </button>
                  ))}
                </div>
                <p className="text-[10px] text-gray-600">
                  {phase === 'PRE' ? 'Runs before forwarding to upstream' : 'Runs after upstream responds'}
                </p>
              </div>

              <div className="space-y-1.5">
                <label className="text-xs font-semibold text-gray-400 uppercase tracking-wider">
                  Execution Order
                </label>
                <input
                  type="number"
                  min={0}
                  value={order}
                  onChange={e => setOrder(Number(e.target.value))}
                  className="w-full bg-white/[0.04] border border-white/[0.08] rounded-lg px-3 py-2.5 text-sm text-white font-mono focus:outline-none focus:border-indigo-500 transition-all"
                />
                <p className="text-[10px] text-gray-600">Lower = runs first within the phase</p>
              </div>
            </div>

            {/* Attach action */}
            <div className="flex justify-end gap-2 pt-1">
              <button
                type="button"
                onClick={() => { setAttachOpen(false); setSelectedId(''); setFilterSearch('') }}
                className="px-3 py-1.5 text-sm text-gray-400 hover:text-white transition-colors"
              >
                Cancel
              </button>
              <button
                type="button"
                disabled={!selectedId || attachMutation.isPending}
                onClick={() => attachMutation.mutate({ filterId: selectedId, order, phase })}
                className="flex items-center gap-1.5 px-3 py-1.5 bg-indigo-600 hover:bg-indigo-500 disabled:opacity-50 text-white text-sm font-semibold rounded-lg transition-colors"
              >
                {attachMutation.isPending ? (
                  <RefreshCw className="w-3.5 h-3.5 animate-spin" />
                ) : (
                  <Plus className="w-3.5 h-3.5" />
                )}
                Attach
              </button>
            </div>
          </div>
        </div>
      )}
    </div>
  )
}

// ─── FilterItem ────────────────────────────────────────────────────────────────

function FilterItem({ f, onDetach }: { f: RouteFilterRef; onDetach: () => void }) {
  const cat = filterCategory(f.filterType)
  const catColor = FILTER_CAT_COLORS[cat] ?? ''

  return (
    <div className="flex items-center gap-3 p-3 bg-white/[0.03] rounded-xl border border-white/[0.05] group hover:border-white/[0.10] transition-colors">
      {/* Order badge */}
      <div className="text-gray-600 text-xs font-mono w-5 text-center shrink-0">{f.order}</div>

      {/* Icon */}
      <div className="shrink-0">
        {FILTER_ICONS[f.filterType] ?? <Filter className="w-3.5 h-3.5 text-gray-400" />}
      </div>

      {/* Info */}
      <div className="flex-1 min-w-0">
        <div className="text-sm text-white font-medium truncate">{f.filterName}</div>
        <div className="flex items-center gap-1.5 mt-0.5">
          <span className={cn('text-[9px] font-bold px-1.5 py-0.5 rounded-full border', catColor)}>
            {f.filterType.replace(/_/g, ' ')}
          </span>
        </div>
      </div>

      {/* Phase badge */}
      <div className={cn(
        'text-[10px] px-2 py-0.5 rounded-full font-bold shrink-0 border',
        f.phase === 'PRE'
          ? 'bg-blue-500/15 text-blue-300 border-blue-500/20'
          : 'bg-purple-500/15 text-purple-300 border-purple-500/20',
      )}>
        {f.phase}
      </div>

      {/* Detach */}
      <button
        onClick={onDetach}
        title="Detach"
        className="opacity-0 group-hover:opacity-100 p-1.5 rounded-lg text-red-400 hover:bg-red-400/10 transition-all shrink-0"
      >
        <Trash2 className="w-3.5 h-3.5" />
      </button>
    </div>
  )
}

