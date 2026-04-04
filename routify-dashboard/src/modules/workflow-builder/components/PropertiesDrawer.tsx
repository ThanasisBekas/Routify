/**
 * PropertiesDrawer.tsx — Right-side contextual panel.
 *
 * Opens when a node is selected on the canvas (selectedNodeId ≠ null in
 * workflowStore). Renders node-type-specific fields and fires the
 * appropriate routesApi mutation on save.
 *
 * Design intent:
 *  - Slides in from the right (CSS translate transition)
 *  - Uses the same glass-dark aesthetic as the rest of the builder
 *  - Each "section" is a labelled field group with clear separation
 *  - Validation errors surface inline, not as toast (allows fixing in-place)
 *
 * Filter nodes: phase and order are ALWAYS computed from the graph (read-only).
 */
import { useState, useEffect } from 'react'
import type { Node } from '@xyflow/react'
import { X, Save, RefreshCw, Globe, Server, Shield, Filter, Trash2, Info, Lock, ChevronDown, AlertTriangle } from 'lucide-react'
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { toast } from 'sonner'
import { cn, extractApiError } from '../../../lib/utils'
import { routesApi } from '../../../api/routesApi'
import { filtersApi } from '../../../api/filtersApi'
import { useWorkflowStore } from '../store/workflowStore'
import type { RouteDto } from '../../../types'
import { STATUS_CFG, getFilterMeta } from '../constants/nodeMetadata'
import type { RouteNodeData, UpstreamNodeData, FilterNodeData } from '../hooks/buildGraph'
import { inferExecutionOrder } from '../hooks/buildGraph'
import FilterConfigFields from '../../filters/FilterConfigFields'
import type { FilterConfig } from '../../filters/filterConfigConstants'

interface PropertiesDrawerProps {
  route: RouteDto
}

// ─── HTTP Method options ───────────────────────────────────────────────────────

const METHOD_OPTIONS = ['GET', 'POST', 'PUT', 'PATCH', 'DELETE', '*'] as const
const METHOD_STYLE: Record<string, string> = {
  GET:    'bg-blue-500/20 text-blue-300 border-blue-500/30',
  POST:   'bg-green-500/20 text-green-300 border-green-500/30',
  PUT:    'bg-amber-500/20 text-amber-300 border-amber-500/30',
  DELETE: 'bg-red-500/20 text-red-300 border-red-500/30',
  PATCH:  'bg-purple-500/20 text-purple-300 border-purple-500/30',
  '*':    'bg-gray-500/20 text-gray-300 border-gray-500/30',
}

// ─── Component ────────────────────────────────────────────────────────────────

export default function PropertiesDrawer({ route }: PropertiesDrawerProps) {
  const qc = useQueryClient()
  const { selectedNodeId, nodes, edges, selectNode, routeStatus } = useWorkflowStore()

  const selectedNode = nodes.find(n => n.id === selectedNodeId)
  const isOpen = !!selectedNodeId && !!selectedNode
  const isLocked = routeStatus === 'ACTIVE'

  // Close on Escape
  useEffect(() => {
    const onKey = (e: KeyboardEvent) => { if (e.key === 'Escape') selectNode(null) }
    window.addEventListener('keydown', onKey)
    return () => window.removeEventListener('keydown', onKey)
  }, [selectNode])

  if (!isOpen || !selectedNode) {
    return null
  }

  return (
    <div
      className={cn(
        'absolute top-0 right-0 h-full w-80 flex flex-col z-20',
        'bg-[#0d0f14] border-l border-white/[0.07] shadow-2xl shadow-black/50',
        'transition-transform duration-200',
        isOpen ? 'translate-x-0' : 'translate-x-full',
      )}
    >
      {/* ── Header ──────────────────────────────────────────────────────── */}
      <div className="flex items-center justify-between px-4 py-3.5 border-b border-white/[0.06] shrink-0">
        <div className="flex items-center gap-2.5">
          <NodeTypeIcon node={selectedNode} />
          <div>
            <div className="text-[10px] text-gray-500 font-medium uppercase tracking-widest">Properties</div>
            <div className="text-sm font-bold text-white leading-tight">{nodeTitle(selectedNode)}</div>
          </div>
        </div>
        <div className="flex items-center gap-1.5">
          {isLocked && (
            <span className="flex items-center gap-1 text-[9px] text-emerald-400 bg-emerald-400/10 border border-emerald-400/20 px-1.5 py-0.5 rounded-full">
              <Lock className="w-2.5 h-2.5" /> Read-only
            </span>
          )}
          <button
            onClick={() => selectNode(null)}
            className="p-1.5 rounded-lg text-gray-500 hover:text-white hover:bg-white/5 transition-colors"
          >
            <X className="w-4 h-4" />
          </button>
        </div>
      </div>

      {/* ── Body ────────────────────────────────────────────────────────── */}
      <div className="flex-1 overflow-y-auto">
        <NodePropertiesBody
          route={route}
          node={selectedNode as Node}
          nodes={nodes}
          edges={edges}
          onClose={() => selectNode(null)}
          qc={qc}
          isLocked={isLocked}
        />
      </div>
    </div>
  )
}

// ─── Body dispatcher ─────────────────────────────────────────────────────────

function NodePropertiesBody({
  route,
  node,
  nodes,
  edges,
  onClose,
  qc,
  isLocked,
}: {
  route: RouteDto
  node: Node
  nodes: Node[]
  edges: import('@xyflow/react').Edge[]
  onClose: () => void
  // eslint-disable-next-line @typescript-eslint/no-explicit-any
  qc: any
  isLocked: boolean
}) {
  switch (node.type) {
    case 'routeNode':
      return <RouteNodeProps route={route} data={node.data as RouteNodeData} onClose={onClose} qc={qc} isLocked={isLocked} />
    case 'upstreamNode':
      return <UpstreamNodeProps route={route} data={node.data as UpstreamNodeData} onClose={onClose} qc={qc} isLocked={isLocked} />
    case 'filterNode':
      return <FilterNodeProps route={route} node={node} nodes={nodes} edges={edges} data={node.data as FilterNodeData} onClose={onClose} qc={qc} isLocked={isLocked} />
    case 'clientNode':
      return <ReadOnlyPanel title="Client" description="Represents the incoming HTTP client request. No configuration required." />
    case 'responseNode':
      return <ReadOnlyPanel title="Response" description="Represents the final HTTP response returned to the client. No configuration required." />
    default:
      return <ReadOnlyPanel title={node.type ?? 'Node'} description="No configurable properties for this node type yet." />
  }
}

// ─── Route node properties ─────────────────────────────────────────────────────

function RouteNodeProps({
  route,
  data,
  onClose,
  qc,
  isLocked,
}: {
  route: RouteDto
  data: RouteNodeData
  onClose: () => void
  // eslint-disable-next-line @typescript-eslint/no-explicit-any
  qc: any
  isLocked: boolean
}) {
  const sc = STATUS_CFG[data.status as keyof typeof STATUS_CFG] ?? STATUS_CFG.DRAFT

  const [name, setName]               = useState(data.name)
  const [pathPattern, setPathPattern] = useState(data.pathPattern)
  const [description, setDescription] = useState(route.description ?? '')
  const [methods, setMethods]         = useState<string[]>(data.methods.split(',').map(m => m.trim()).filter(Boolean))

  const mutation = useMutation({
    mutationFn: () => routesApi.update(route.id, {
      name,
      pathPattern,
      description: description || undefined,
      methods: methods.join(','),
    }),
    onSuccess: () => {
      qc.invalidateQueries({ queryKey: ['route', route.id] })
      qc.invalidateQueries({ queryKey: ['routes'] })
      toast.success('Route updated', { description: 'Changes saved to Routify.' })
      onClose()
    },
    onError: (err) => toast.error('Save failed', { description: extractApiError(err) }),
  })

  const toggleMethod = (m: string) =>
    setMethods(prev =>
      prev.includes(m) ? prev.filter(x => x !== m) : [...prev, m],
    )

  return (
    <form
      onSubmit={(e) => { e.preventDefault(); if (!isLocked) mutation.mutate() }}
      className="p-4 space-y-5"
    >
      {/* Lock notice */}
      {isLocked && <LockedNotice />}

      {/* Status badge */}
      <div className={cn('flex items-center gap-1.5 text-xs font-semibold', sc.color)}>
        {sc.icon} {data.status}
        <span className="text-gray-600 font-normal ml-1">v{data.version}</span>
      </div>

      <Field label="Route Name">
        <input
          value={name}
          onChange={e => setName(e.target.value)}
          className={cn(inputClass, isLocked && 'opacity-50 cursor-not-allowed')}
          placeholder="e.g. User Auth Route"
          required
          disabled={isLocked}
        />
      </Field>

      <Field label="Path Pattern">
        <input
          value={pathPattern}
          onChange={e => setPathPattern(e.target.value)}
          className={cn(inputClass, 'font-mono text-indigo-300', isLocked && 'opacity-50 cursor-not-allowed')}
          placeholder="/api/v1/**"
          required
          disabled={isLocked}
        />
      </Field>

      <Field label="Methods">
        <div className="flex gap-1.5 flex-wrap">
          {METHOD_OPTIONS.map(m => (
            <button
              key={m}
              type="button"
              onClick={() => !isLocked && toggleMethod(m)}
              disabled={isLocked}
              className={cn(
                'text-[10px] px-2 py-1 rounded-lg font-mono font-bold border transition-all',
                methods.includes(m)
                  ? METHOD_STYLE[m]
                  : 'border-white/[0.08] text-gray-600 hover:text-gray-400',
                isLocked && 'cursor-not-allowed opacity-50',
              )}
            >
              {m}
            </button>
          ))}
        </div>
        {methods.length === 0 && (
          <p className="text-[10px] text-red-400 mt-1">Select at least one method</p>
        )}
      </Field>

      <Field label="Description">
        <textarea
          value={description}
          onChange={e => setDescription(e.target.value)}
          rows={3}
          className={cn(inputClass, 'resize-none', isLocked && 'opacity-50 cursor-not-allowed')}
          placeholder="Optional route description"
          disabled={isLocked}
        />
      </Field>

      <SaveButton loading={mutation.isPending} disabled={methods.length === 0 || isLocked} />
    </form>
  )
}

// ─── Upstream node properties ──────────────────────────────────────────────────

function UpstreamNodeProps({
  route,
  data,
  onClose,
  qc,
  isLocked,
}: {
  route: RouteDto
  data: UpstreamNodeData
  onClose: () => void
  // eslint-disable-next-line @typescript-eslint/no-explicit-any
  qc: any
  isLocked: boolean
}) {
  const [upstreamUri, setUpstreamUri] = useState(data.uri)
  const [stripPrefix, setStripPrefix] = useState(data.stripPrefix ?? '')

  const mutation = useMutation({
    mutationFn: () => routesApi.update(route.id, {
      upstreamUri,
      stripPrefix: stripPrefix || undefined,
    }),
    onSuccess: () => {
      qc.invalidateQueries({ queryKey: ['route', route.id] })
      qc.invalidateQueries({ queryKey: ['routes'] })
      toast.success('Upstream updated')
      onClose()
    },
    onError: (err) => toast.error('Save failed', { description: extractApiError(err) }),
  })

  return (
    <form onSubmit={(e) => { e.preventDefault(); if (!isLocked) mutation.mutate() }} className="p-4 space-y-5">
      {isLocked && <LockedNotice />}

      <Field label="Upstream URI">
        <input
          value={upstreamUri}
          onChange={e => setUpstreamUri(e.target.value)}
          className={cn(inputClass, 'font-mono text-emerald-300', isLocked && 'opacity-50 cursor-not-allowed')}
          placeholder="http://service:8080 or lb://service-name"
          required
          disabled={isLocked}
        />
        <p className="text-[10px] text-gray-600 mt-1">
          Use <code className="text-gray-400">lb://service-name</code> for service-discovery load balancing.
        </p>
      </Field>

      <Field label="Strip Prefix">
        <input
          value={stripPrefix}
          onChange={e => setStripPrefix(e.target.value)}
          className={cn(inputClass, 'font-mono', isLocked && 'opacity-50 cursor-not-allowed')}
          placeholder="/api/v1 (optional)"
          disabled={isLocked}
        />
        <p className="text-[10px] text-gray-600 mt-1">
          Path prefix stripped before forwarding to upstream.
        </p>
      </Field>

      <SaveButton loading={mutation.isPending} disabled={isLocked} />
    </form>
  )
}

// ─── Filter node properties ────────────────────────────────────────────────────

function FilterNodeProps({
  route,
  node,
  nodes,
  edges,
  data,
  onClose,
  qc,
  isLocked,
}: {
  route: RouteDto
  node: Node
  nodes: Node[]
  edges: import('@xyflow/react').Edge[]
  data: FilterNodeData
  onClose: () => void
  // eslint-disable-next-line @typescript-eslint/no-explicit-any
  qc: any
  isLocked: boolean
}) {
  const meta = getFilterMeta(data.filter.filterType)

  // ── Compute phase and order from graph topology (never from manual input) ──
  const inferred = inferExecutionOrder(nodes, edges)
  const entry = inferred.find(e => e.nodeId === node.id)
  const computedPhase = entry?.phase ?? 'PRE'
  const computedOrder = entry?.order ?? 0

  // ── Inline config editing ────────────────────────────────────────────────────
  const [configOpen, setConfigOpen] = useState(false)
  const [localConfig, setLocalConfig] = useState<FilterConfig>({})

  // Fetch the full filter definition to get its current config
  const { data: filterDef, isLoading: loadingDef } = useQuery({
    queryKey: ['filter', data.filter.filterId],
    queryFn: () => filtersApi.get(data.filter.filterId),
    enabled: configOpen,
  })

  // Sync localConfig when definition loads
  useEffect(() => {
    if (filterDef?.config) {
      setLocalConfig(filterDef.config as FilterConfig)
    }
  }, [filterDef])

  const configMutation = useMutation({
    mutationFn: () => filtersApi.update(data.filter.filterId, { config: localConfig }),
    onSuccess: () => {
      qc.invalidateQueries({ queryKey: ['filter', data.filter.filterId] })
      qc.invalidateQueries({ queryKey: ['filters'] })
      qc.invalidateQueries({ queryKey: ['route', route.id] })
      toast.success('Filter config updated', {
        description: `Changes to "${data.filter.filterName}" will take effect on next gateway reload.`,
      })
    },
    onError: (err) => toast.error('Config save failed', { description: extractApiError(err) }),
  })

  const detachMutation = useMutation({
    mutationFn: () => routesApi.detachFilter(route.id, data.filter.filterId),
    onSuccess: () => {
      qc.invalidateQueries({ queryKey: ['route', route.id] })
      toast.success('Filter detached')
      onClose()
    },
    onError: (err) => toast.error('Detach failed', { description: extractApiError(err) }),
  })

  return (
    <div className="p-4 space-y-5">
      {isLocked && <LockedNotice />}

      {/* Filter identity */}
      <div className={cn('flex items-center gap-3 p-3 rounded-xl border', meta.border, meta.bg)}>
        <span className={meta.color}>{meta.icon}</span>
        <div>
          <div className={cn('text-[10px] font-bold uppercase tracking-widest', meta.color)}>
            {data.filter.filterType.replace(/_/g, ' ')}
          </div>
          <div className="text-sm font-bold text-white">{data.filter.filterName}</div>
        </div>
      </div>

      {/* Computed phase — read-only */}
      <Field label="Phase (auto-computed)">
        <div className={cn(
          'flex items-center gap-2 px-3 py-2 rounded-lg border text-xs font-semibold',
          computedPhase === 'PRE'
            ? 'bg-blue-500/10 border-blue-500/20 text-blue-300'
            : 'bg-purple-500/10 border-purple-500/20 text-purple-300',
        )}>
          <Info className="w-3.5 h-3.5 shrink-0" />
          {computedPhase === 'PRE' ? '↑ PRE — before upstream' : '↓ POST — after upstream'}
        </div>
        <p className="text-[10px] text-gray-600 mt-1">
          Determined by graph position: nodes before Route → PRE, nodes after Upstream → POST.
        </p>
      </Field>

      {/* Computed execution order — read-only */}
      <Field label="Execution Order (auto-computed)">
        <div className="flex items-center gap-2 px-3 py-2 rounded-lg border border-white/[0.08] bg-white/[0.03] text-xs font-mono text-gray-300">
          <Info className="w-3.5 h-3.5 shrink-0 text-gray-500" />
          {computedOrder}
        </div>
        <p className="text-[10px] text-gray-600 mt-1">
          Derived from node connection order. Lower = runs first within the same phase.
        </p>
      </Field>

      {/* ── Inline config editor ─────────────────────────────────────────────── */}
      <div className="rounded-xl border border-white/[0.07] overflow-hidden">
        <button
          type="button"
          onClick={() => setConfigOpen(v => !v)}
          disabled={isLocked}
          className={cn(
            'w-full flex items-center justify-between px-3 py-2.5 text-left transition-colors',
            isLocked ? 'opacity-50 cursor-not-allowed' : 'hover:bg-white/[0.03]',
          )}
        >
          <div className="flex items-center gap-2">
            <span className="text-xs font-semibold text-gray-400">Edit Configuration</span>
            {isLocked && <Lock className="w-3 h-3 text-gray-600" />}
          </div>
          <ChevronDown className={cn('w-3.5 h-3.5 text-gray-600 transition-transform', configOpen && 'rotate-180')} />
        </button>

        {configOpen && !isLocked && (
          <div className="border-t border-white/[0.06] p-3 space-y-4">
            {/* Global edit warning */}
            <div className="flex items-start gap-2 px-3 py-2 rounded-lg bg-amber-500/[0.07] border border-amber-500/20 text-[11px] text-amber-400/90 leading-relaxed">
              <AlertTriangle className="w-3.5 h-3.5 shrink-0 mt-px" />
              <span>
                Saving will update <strong className="text-amber-300">"{data.filter.filterName}"</strong> globally —
                all routes that use this filter will be affected.
              </span>
            </div>

            {loadingDef ? (
              <div className="flex items-center justify-center py-6 gap-2">
                <div className="w-4 h-4 border-2 border-indigo-500/30 border-t-indigo-500 rounded-full animate-spin" />
                <span className="text-xs text-gray-500">Loading config…</span>
              </div>
            ) : (
              <FilterConfigFields
                filterType={data.filter.filterType}
                config={localConfig}
                onChange={setLocalConfig}
              />
            )}

            <button
              type="button"
              onClick={() => !configMutation.isPending && configMutation.mutate()}
              disabled={configMutation.isPending || loadingDef}
              className="w-full py-1.5 rounded-lg text-xs font-semibold text-white bg-indigo-600 hover:bg-indigo-500 disabled:opacity-50 transition-colors flex items-center justify-center gap-1.5"
            >
              {configMutation.isPending
                ? <><RefreshCw className="w-3.5 h-3.5 animate-spin" /> Saving…</>
                : <><Save className="w-3.5 h-3.5" /> Save Config</>
              }
            </button>
          </div>
        )}
      </div>

      {/* Detach action */}
      <div className="space-y-2 pt-1">
        <button
          type="button"
          onClick={() => { if (!isLocked) detachMutation.mutate() }}
          disabled={detachMutation.isPending || isLocked}
          className={cn(
            'w-full py-2 rounded-lg text-xs font-semibold border transition-colors flex items-center justify-center gap-1.5',
            isLocked
              ? 'text-gray-600 bg-transparent border-white/[0.06] cursor-not-allowed opacity-50'
              : 'text-red-400 bg-red-400/10 border-red-400/20 hover:bg-red-400/20 disabled:opacity-50',
          )}
          title={isLocked ? 'Pause the route to detach filters' : 'Detach this filter from the route'}
        >
          {detachMutation.isPending
            ? <RefreshCw className="w-3.5 h-3.5 animate-spin" />
            : <Trash2 className="w-3.5 h-3.5" />
          }
          {isLocked ? 'Detach (paused routes only)' : 'Detach Filter from Route'}
        </button>
      </div>

      <p className="text-[10px] text-gray-700 leading-relaxed">
        Phase and execution order are automatically derived from canvas connections.
        Reposition the node in the flow to change its phase or order.
      </p>
    </div>
  )
}

// ─── Lock notice ───────────────────────────────────────────────────────────────

function LockedNotice() {
  return (
    <div className="flex items-start gap-2 px-3 py-2.5 rounded-lg bg-emerald-500/[0.08] border border-emerald-500/20">
      <Lock className="w-3.5 h-3.5 text-emerald-400 shrink-0 mt-px" />
      <p className="text-[10px] text-emerald-300 leading-relaxed">
        This route is active. Pause it to edit properties or detach filters.
      </p>
    </div>
  )
}

// ─── Read-only fallback panel ──────────────────────────────────────────────────

function ReadOnlyPanel({ title, description }: { title: string; description: string }) {
  return (
    <div className="p-4 space-y-3">
      <p className="text-xs font-semibold text-gray-300">{title}</p>
      <p className="text-xs text-gray-600 leading-relaxed">{description}</p>
    </div>
  )
}

// ─── Shared field wrapper ──────────────────────────────────────────────────────

function Field({ label, children }: { label: string; children: React.ReactNode }) {
  return (
    <div className="space-y-1.5">
      <label className="block text-[10px] font-semibold text-gray-500 uppercase tracking-widest">{label}</label>
      {children}
    </div>
  )
}

// ─── Save button ───────────────────────────────────────────────────────────────

function SaveButton({ loading, disabled = false }: { loading: boolean; disabled?: boolean }) {
  return (
    <button
      type="submit"
      disabled={loading || disabled}
      className="w-full py-2 rounded-lg text-xs font-semibold text-white bg-indigo-600 hover:bg-indigo-500 disabled:opacity-50 transition-colors flex items-center justify-center gap-1.5 shadow-lg shadow-indigo-500/20"
    >
      {loading
        ? <><RefreshCw className="w-3.5 h-3.5 animate-spin" /> Saving…</>
        : <><Save className="w-3.5 h-3.5" /> Save Changes</>
      }
    </button>
  )
}

// ─── Node icon resolver ───────────────────────────────────────────────────────

function NodeTypeIcon({ node }: { node: { type?: string } }) {
  const cls = 'w-4 h-4'
  switch (node.type) {
    case 'routeNode':   return <Globe    className={cn(cls, 'text-indigo-400')} />
    case 'upstreamNode': return <Server   className={cn(cls, 'text-emerald-400')} />
    case 'filterNode':  return <Shield   className={cn(cls, 'text-blue-400')} />
    default:            return <Filter   className={cn(cls, 'text-gray-400')} />
  }
}

function nodeTitle(node: { type?: string; data: Record<string, unknown> }): string {
  switch (node.type) {
    case 'routeNode':   return (node.data as RouteNodeData).name
    case 'upstreamNode': return 'Upstream Config'
    case 'filterNode':  return (node.data as FilterNodeData).filter.filterName
    case 'clientNode':  return 'Client'
    case 'responseNode': return 'Response'
    default: return node.type ?? 'Node'
  }
}

// ─── Shared input style ───────────────────────────────────────────────────────

const inputClass =
  'w-full bg-white/[0.04] border border-white/[0.08] rounded-lg px-3 py-1.5 text-xs text-white placeholder-gray-600 focus:outline-none focus:border-indigo-500 transition-all'

