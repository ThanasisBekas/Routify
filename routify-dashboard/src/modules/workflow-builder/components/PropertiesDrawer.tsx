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
 */
import { useState, useEffect } from 'react'
import type { Node } from '@xyflow/react'
import { X, Save, RefreshCw, Globe, Server, Shield, Filter } from 'lucide-react'
import { useMutation, useQueryClient } from '@tanstack/react-query'
import { toast } from 'sonner'
import { cn, extractApiError } from '../../../lib/utils'
import { routesApi } from '../../../api/routesApi'
import { useWorkflowStore } from '../store/workflowStore'
import type { RouteDto } from '../../../types'
import { STATUS_CFG, getFilterMeta } from '../constants/nodeMetadata'
import type { RouteNodeData, UpstreamNodeData, FilterNodeData } from '../hooks/buildGraph'

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
  const { selectedNodeId, nodes, selectNode } = useWorkflowStore()

  const selectedNode = nodes.find(n => n.id === selectedNodeId)
  const isOpen = !!selectedNodeId && !!selectedNode

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
    /*
     * Absolutely positioned within the canvas wrapper — overlays the right
     * portion of the canvas without affecting the overall layout flex.
     * The translate transition provides a smooth slide-in.
     */
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
        <button
          onClick={() => selectNode(null)}
          className="p-1.5 rounded-lg text-gray-500 hover:text-white hover:bg-white/5 transition-colors"
        >
          <X className="w-4 h-4" />
        </button>
      </div>

      {/* ── Body ────────────────────────────────────────────────────────── */}
      <div className="flex-1 overflow-y-auto">
        <NodePropertiesBody route={route} node={selectedNode as Node} onClose={() => selectNode(null)} qc={qc} />
      </div>
    </div>
  )
}

// ─── Body dispatcher ─────────────────────────────────────────────────────────

function NodePropertiesBody({
  route,
  node,
  onClose,
  qc,
}: {
  route: RouteDto
  node: Node
  onClose: () => void
  // eslint-disable-next-line @typescript-eslint/no-explicit-any
  qc: any
}) {
  switch (node.type) {
    case 'routeNode':
      return <RouteNodeProps route={route} data={node.data as RouteNodeData} onClose={onClose} qc={qc} />
    case 'upstreamNode':
      return <UpstreamNodeProps route={route} data={node.data as UpstreamNodeData} onClose={onClose} qc={qc} />
    case 'filterNode':
      return <FilterNodeProps route={route} data={node.data as FilterNodeData} onClose={onClose} qc={qc} />
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
}: {
  route: RouteDto
  data: RouteNodeData
  onClose: () => void
  // eslint-disable-next-line @typescript-eslint/no-explicit-any
  qc: any
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
      onSubmit={(e) => { e.preventDefault(); mutation.mutate() }}
      className="p-4 space-y-5"
    >
      {/* Status badge */}
      <div className={cn('flex items-center gap-1.5 text-xs font-semibold', sc.color)}>
        {sc.icon} {data.status}
        <span className="text-gray-600 font-normal ml-1">v{data.version}</span>
      </div>

      <Field label="Route Name">
        <input
          value={name}
          onChange={e => setName(e.target.value)}
          className={inputClass}
          placeholder="e.g. User Auth Route"
          required
        />
      </Field>

      <Field label="Path Pattern">
        <input
          value={pathPattern}
          onChange={e => setPathPattern(e.target.value)}
          className={cn(inputClass, 'font-mono text-indigo-300')}
          placeholder="/api/v1/**"
          required
        />
      </Field>

      <Field label="Methods">
        <div className="flex gap-1.5 flex-wrap">
          {METHOD_OPTIONS.map(m => (
            <button
              key={m}
              type="button"
              onClick={() => toggleMethod(m)}
              className={cn(
                'text-[10px] px-2 py-1 rounded-lg font-mono font-bold border transition-all',
                methods.includes(m)
                  ? METHOD_STYLE[m]
                  : 'border-white/[0.08] text-gray-600 hover:text-gray-400',
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
          className={cn(inputClass, 'resize-none')}
          placeholder="Optional route description"
        />
      </Field>

      <SaveButton loading={mutation.isPending} disabled={methods.length === 0} />
    </form>
  )
}

// ─── Upstream node properties ──────────────────────────────────────────────────

function UpstreamNodeProps({
  route,
  data,
  onClose,
  qc,
}: {
  route: RouteDto
  data: UpstreamNodeData
  onClose: () => void
  // eslint-disable-next-line @typescript-eslint/no-explicit-any
  qc: any
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
    <form onSubmit={(e) => { e.preventDefault(); mutation.mutate() }} className="p-4 space-y-5">
      <Field label="Upstream URI">
        <input
          value={upstreamUri}
          onChange={e => setUpstreamUri(e.target.value)}
          className={cn(inputClass, 'font-mono text-emerald-300')}
          placeholder="http://service:8080 or lb://service-name"
          required
        />
        <p className="text-[10px] text-gray-600 mt-1">
          Use <code className="text-gray-400">lb://service-name</code> for service-discovery load balancing.
        </p>
      </Field>

      <Field label="Strip Prefix">
        <input
          value={stripPrefix}
          onChange={e => setStripPrefix(e.target.value)}
          className={cn(inputClass, 'font-mono')}
          placeholder="/api/v1 (optional)"
        />
        <p className="text-[10px] text-gray-600 mt-1">
          Path prefix stripped before forwarding to upstream.
        </p>
      </Field>

      <SaveButton loading={mutation.isPending} />
    </form>
  )
}

// ─── Filter node properties ────────────────────────────────────────────────────

function FilterNodeProps({
  route,
  data,
  onClose,
  qc,
}: {
  route: RouteDto
  data: FilterNodeData
  onClose: () => void
  // eslint-disable-next-line @typescript-eslint/no-explicit-any
  qc: any
}) {
  const meta = getFilterMeta(data.filter.filterType)
  const [phase, setPhase] = useState<'PRE' | 'POST'>(data.phase)
  const [order, setOrder] = useState(data.filter.order)

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

      {/* Phase picker */}
      <Field label="Phase">
        <div className="flex gap-2">
          {(['PRE', 'POST'] as const).map(p => (
            <button
              key={p}
              type="button"
              onClick={() => setPhase(p)}
              className={cn(
                'flex-1 py-1.5 rounded-lg text-xs font-semibold border transition-all',
                phase === p
                  ? p === 'PRE'
                    ? 'bg-blue-600/70 border-blue-500/40 text-white'
                    : 'bg-purple-600/70 border-purple-500/40 text-white'
                  : 'border-white/[0.08] text-gray-500 hover:text-gray-300',
              )}
            >
              {p === 'PRE' ? '↑ PRE — before upstream' : '↓ POST — after upstream'}
            </button>
          ))}
        </div>
      </Field>

      {/* Order */}
      <Field label="Execution Order">
        <input
          type="number"
          min={0}
          value={order}
          onChange={e => setOrder(Number(e.target.value))}
          className={cn(inputClass, 'font-mono')}
        />
        <p className="text-[10px] text-gray-600 mt-1">Lower numbers execute first within the same phase.</p>
      </Field>

      {/* Actions */}
      <div className="space-y-2 pt-1">
        <button
          type="button"
          onClick={() => detachMutation.mutate()}
          disabled={detachMutation.isPending}
          className="w-full py-2 rounded-lg text-xs font-semibold text-red-400 bg-red-400/10 border border-red-400/20 hover:bg-red-400/20 disabled:opacity-50 transition-colors flex items-center justify-center gap-1.5"
        >
          {detachMutation.isPending ? <RefreshCw className="w-3.5 h-3.5 animate-spin" /> : null}
          Detach Filter from Route
        </button>
      </div>

      {/* Note: Phase/Order editing requires re-attaching via the API. */}
      <p className="text-[10px] text-gray-700 leading-relaxed">
        To change phase or order, detach and re-attach the filter via the Add Filter panel on the canvas.
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

