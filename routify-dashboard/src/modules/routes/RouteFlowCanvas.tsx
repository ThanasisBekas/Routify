/**
 * RouteFlowCanvas — Read-only React Flow canvas for the route detail modal.
 *
 * This canvas is a PREVIEW only — it shows the current filter chain for a route
 * but does NOT support adding/removing filters. All filter management must be
 * performed via the full Workflow Builder (/routes/:id/builder).
 *
 * Features:
 *  - Draggable nodes (layout only)
 *  - Flow-completeness validation: Client → … → Response must be reachable
 *  - onValidityChange callback so the parent can gate Save/Activate
 *  - "Open Builder" banner guides users to the full editor
 */
import { useCallback, useMemo, useEffect } from 'react'
import {
  ReactFlow,
  Background,
  Controls,
  MiniMap,
  Handle,
  Position,
  useNodesState,
  useEdgesState,
  type NodeProps,
  type Node,
  type Edge,
  MarkerType,
  BackgroundVariant,
  Panel,
  type ReactFlowInstance,
} from '@xyflow/react'
import '@xyflow/react/dist/style.css'
import {
  Globe,
  Server,
  Shield,
  Gauge,
  RefreshCw,
  Code2,
  GitBranch,
  ToggleLeft,
  Zap,
  AlertCircle,
  CheckCircle,
  Clock,
  Pause,
  Archive,
  Network,
  Trash2,
} from 'lucide-react'
import { useMutation, useQueryClient } from '@tanstack/react-query'
import { useNavigate } from 'react-router-dom'
import { routesApi } from '../../api/routesApi'
import type { RouteDto, RouteFilterRef } from '../../types'
import { cn } from '../../lib/utils'
import { FLOW_COL, FLOW_ROW_GAP, FLOW_START_Y } from './routeConstants'

// ─── Node Data Types ──────────────────────────────────────────────────────────

interface ClientNodeData extends Record<string, unknown> {
  status: string
}
interface RouteNodeData extends Record<string, unknown> {
  name: string
  pathPattern: string
  methods: string
  status: string
  version: number
}
interface FilterNodeData extends Record<string, unknown> {
  filter: RouteFilterRef
  phase: 'PRE' | 'POST'
  onDetach: (id: string) => void
}
interface UpstreamNodeData extends Record<string, unknown> {
  uri: string
  stripPrefix?: string
}
interface ResponseNodeData extends Record<string, unknown> {
  status: string
}
interface LabelNodeData extends Record<string, unknown> {
  label: string
  sub?: string
  color: string
}

// ─── Filter meta ──────────────────────────────────────────────────────────────

const FILTER_META: Record<string, { icon: React.ReactNode; color: string; bg: string; border: string }> = {
  AUTH_JWT: {
    icon: <Shield className="w-4 h-4" />,
    color: 'text-emerald-400',
    bg: 'bg-emerald-400/10',
    border: 'border-emerald-400/25',
  },
  AUTH_API_KEY: {
    icon: <Shield className="w-4 h-4" />,
    color: 'text-blue-400',
    bg: 'bg-blue-400/10',
    border: 'border-blue-400/25',
  },
  AUTH_BASIC: {
    icon: <Shield className="w-4 h-4" />,
    color: 'text-cyan-400',
    bg: 'bg-cyan-400/10',
    border: 'border-cyan-400/25',
  },
  AUTH_OAUTH2: {
    icon: <Shield className="w-4 h-4" />,
    color: 'text-teal-400',
    bg: 'bg-teal-400/10',
    border: 'border-teal-400/25',
  },
  AUTH_MTLS: {
    icon: <Shield className="w-4 h-4" />,
    color: 'text-indigo-400',
    bg: 'bg-indigo-400/10',
    border: 'border-indigo-400/25',
  },
  AUTH_CLIENT_ID: {
    icon: <Shield className="w-4 h-4" />,
    color: 'text-violet-400',
    bg: 'bg-violet-400/10',
    border: 'border-violet-400/25',
  },
  AUTH_NONE: {
    icon: <Shield className="w-4 h-4" />,
    color: 'text-gray-400',
    bg: 'bg-gray-400/10',
    border: 'border-gray-400/25',
  },
  RATE_LIMIT_TOKEN_BUCKET: {
    icon: <Gauge className="w-4 h-4" />,
    color: 'text-yellow-400',
    bg: 'bg-yellow-400/10',
    border: 'border-yellow-400/25',
  },
  RATE_LIMIT_FIXED_WINDOW: {
    icon: <Gauge className="w-4 h-4" />,
    color: 'text-orange-400',
    bg: 'bg-orange-400/10',
    border: 'border-orange-400/25',
  },
  RATE_LIMIT_SLIDING_WINDOW: {
    icon: <Gauge className="w-4 h-4" />,
    color: 'text-amber-400',
    bg: 'bg-amber-400/10',
    border: 'border-amber-400/25',
  },
  CIRCUIT_BREAKER: {
    icon: <RefreshCw className="w-4 h-4" />,
    color: 'text-orange-400',
    bg: 'bg-orange-400/10',
    border: 'border-orange-400/25',
  },
  RETRY: {
    icon: <RefreshCw className="w-4 h-4" />,
    color: 'text-amber-400',
    bg: 'bg-amber-400/10',
    border: 'border-amber-400/25',
  },
  TIMEOUT: {
    icon: <Clock className="w-4 h-4" />,
    color: 'text-rose-400',
    bg: 'bg-rose-400/10',
    border: 'border-rose-400/25',
  },
  BODY_JOLT_TRANSFORM: {
    icon: <Code2 className="w-4 h-4" />,
    color: 'text-purple-400',
    bg: 'bg-purple-400/10',
    border: 'border-purple-400/25',
  },
  BODY_JSONATA_TRANSFORM: {
    icon: <Code2 className="w-4 h-4" />,
    color: 'text-violet-400',
    bg: 'bg-violet-400/10',
    border: 'border-violet-400/25',
  },
  CONDITIONAL_ROUTE: {
    icon: <GitBranch className="w-4 h-4" />,
    color: 'text-indigo-400',
    bg: 'bg-indigo-400/10',
    border: 'border-indigo-400/25',
  },
  API_VERSIONING: {
    icon: <ToggleLeft className="w-4 h-4" />,
    color: 'text-cyan-400',
    bg: 'bg-cyan-400/10',
    border: 'border-cyan-400/25',
  },
}
const DEFAULT_META = {
  icon: <Zap className="w-4 h-4" />,
  color: 'text-gray-400',
  bg: 'bg-gray-400/10',
  border: 'border-gray-400/25',
}
const getFilterMeta = (type: string) => FILTER_META[type] ?? DEFAULT_META

const STATUS_CFG = {
  ACTIVE: { color: 'text-emerald-400', icon: <CheckCircle className="w-3.5 h-3.5" /> },
  DRAFT: { color: 'text-amber-400', icon: <Clock className="w-3.5 h-3.5" /> },
  DISABLED: { color: 'text-gray-400', icon: <Pause className="w-3.5 h-3.5" /> },
  ARCHIVED: { color: 'text-red-400', icon: <Archive className="w-3.5 h-3.5" /> },
} as const

// ─── Custom Nodes ──────────────────────────────────────────────────────────────

// eslint-disable-next-line @typescript-eslint/no-unused-vars
function ClientNode(_props: NodeProps) {
  return (
    <div className="flex flex-col items-center gap-2 px-4 py-3 bg-[#111318] border border-white/10 rounded-xl shadow-xl min-w-[100px]">
      <div className="w-10 h-10 rounded-full bg-indigo-500/15 border border-indigo-400/25 flex items-center justify-center">
        <Globe className="w-5 h-5 text-indigo-400" />
      </div>
      <div className="text-center">
        <div className="text-[11px] font-bold text-white tracking-wide">CLIENT</div>
        <div className="text-[10px] text-gray-500 mt-0.5">HTTP Request</div>
      </div>
      <Handle
        type="source"
        position={Position.Right}
        className="!w-2.5 !h-2.5 !bg-indigo-400 !border-0 !rounded-full"
      />
    </div>
  )
}

function RouteNode({ data }: NodeProps) {
  const d = data as RouteNodeData
  const sc = STATUS_CFG[d.status as keyof typeof STATUS_CFG] ?? STATUS_CFG.DRAFT
  const methods = d.methods.split(',').map((m: string) => m.trim())
  return (
    <div className="px-4 py-3.5 bg-[#111318] border-2 border-indigo-500/40 rounded-xl shadow-2xl shadow-indigo-500/10 min-w-[220px] relative overflow-hidden">
      <div className="absolute inset-0 bg-indigo-500/[0.04] rounded-xl pointer-events-none" />
      <Handle type="target" position={Position.Left} className="!w-2.5 !h-2.5 !bg-indigo-400 !border-0 !rounded-full" />
      <Handle
        type="source"
        position={Position.Right}
        className="!w-2.5 !h-2.5 !bg-indigo-400 !border-0 !rounded-full"
      />
      <div className="relative">
        <div className="flex items-start justify-between gap-2 mb-2.5">
          <div className="flex-1 min-w-0">
            <div className="text-[11px] font-bold text-indigo-400 uppercase tracking-widest mb-0.5">Route</div>
            <div className="text-sm font-bold text-white truncate">{d.name}</div>
          </div>
          <div className={cn('flex items-center gap-1 text-[10px] font-semibold shrink-0 mt-0.5', sc.color)}>
            {sc.icon} {d.status}
          </div>
        </div>
        <code className="block text-[11px] bg-white/5 border border-white/[0.07] px-2 py-1 rounded text-indigo-300 font-mono mb-2 truncate">
          {d.pathPattern}
        </code>
        <div className="flex gap-1 flex-wrap">
          {methods.map((m: string) => (
            <span
              key={m}
              className={cn(
                'text-[9px] px-1.5 py-0.5 rounded font-mono font-bold border',
                m === 'GET'
                  ? 'bg-blue-500/15 text-blue-300 border-blue-500/25'
                  : m === 'POST'
                    ? 'bg-green-500/15 text-green-300 border-green-500/25'
                    : m === 'PUT'
                      ? 'bg-amber-500/15 text-amber-300 border-amber-500/25'
                      : m === 'DELETE'
                        ? 'bg-red-500/15 text-red-300 border-red-500/25'
                        : m === 'PATCH'
                          ? 'bg-purple-500/15 text-purple-300 border-purple-500/25'
                          : 'bg-gray-500/15 text-gray-300 border-gray-500/25',
              )}
            >
              {m}
            </span>
          ))}
        </div>
        <div className="mt-2 text-[10px] text-gray-600 font-mono">v{d.version}</div>
      </div>
    </div>
  )
}

function FilterNode({ data }: NodeProps) {
  const d = data as FilterNodeData
  const meta = getFilterMeta(d.filter.filterType)
  return (
    <div className={cn('px-3 py-2.5 border rounded-xl shadow-lg min-w-[160px] bg-[#111318] group', meta.border)}>
      <Handle type="target" position={Position.Left} className="!w-2.5 !h-2.5 !bg-gray-500 !border-0 !rounded-full" />
      <Handle type="source" position={Position.Right} className="!w-2.5 !h-2.5 !bg-gray-500 !border-0 !rounded-full" />
      <div className="flex items-start gap-2">
        <div className={cn('mt-0.5 shrink-0 p-1.5 rounded-lg', meta.bg)}>
          <span className={meta.color}>{meta.icon}</span>
        </div>
        <div className="flex-1 min-w-0">
          <div className={cn('text-[9px] font-bold uppercase tracking-widest mb-0.5', meta.color)}>
            {d.filter.filterType.replace(/_/g, ' ')}
          </div>
          <div className="text-[11px] font-semibold text-white truncate">{d.filter.filterName}</div>
          <div className="flex items-center gap-1.5 mt-1">
            <span
              className={cn(
                'text-[9px] px-1.5 py-0.5 rounded-full font-bold',
                d.phase === 'PRE' ? 'bg-blue-500/20 text-blue-300' : 'bg-purple-500/20 text-purple-300',
              )}
            >
              {d.phase}
            </span>
            <span className="text-[9px] text-gray-600 font-mono">#{d.filter.order}</span>
          </div>
        </div>
        <button
          onClick={() => d.onDetach(d.filter.filterId)}
          className="opacity-0 group-hover:opacity-100 p-1 rounded text-red-400 hover:bg-red-400/10 transition-all shrink-0 mt-0.5"
          title="Detach filter"
        >
          <Trash2 className="w-3 h-3" />
        </button>
      </div>
    </div>
  )
}

function UpstreamNode({ data }: NodeProps) {
  const d = data as UpstreamNodeData
  const isLB = d.uri.startsWith('lb://')
  return (
    <div className="px-4 py-3.5 bg-[#111318] border border-emerald-500/30 rounded-xl shadow-xl shadow-emerald-500/5 min-w-[200px]">
      <Handle
        type="target"
        position={Position.Left}
        className="!w-2.5 !h-2.5 !bg-emerald-400 !border-0 !rounded-full"
      />
      <Handle
        type="source"
        position={Position.Right}
        className="!w-2.5 !h-2.5 !bg-emerald-400 !border-0 !rounded-full"
      />
      <div className="flex items-start gap-3">
        <div className="mt-0.5 p-2 rounded-lg bg-emerald-400/10 border border-emerald-400/20 shrink-0">
          <Server className="w-5 h-5 text-emerald-400" />
        </div>
        <div className="flex-1 min-w-0">
          <div className="text-[10px] font-bold text-emerald-400 uppercase tracking-widest mb-1">Upstream</div>
          <code className="text-[11px] text-gray-300 font-mono break-all leading-relaxed">{d.uri}</code>
          {isLB && (
            <div className="mt-1.5 text-[9px] text-emerald-400/70 bg-emerald-400/5 border border-emerald-400/15 rounded px-1.5 py-0.5 inline-block">
              Load Balanced
            </div>
          )}
          {d.stripPrefix && (
            <div className="mt-1 text-[10px] text-gray-500">
              Strip: <code className="text-gray-400 font-mono">{d.stripPrefix}</code>
            </div>
          )}
        </div>
      </div>
    </div>
  )
}

function ResponseNode({ data }: NodeProps) {
  const d = data as ResponseNodeData
  const isActive = d.status === 'ACTIVE'
  return (
    <div
      className={cn(
        'flex flex-col items-center gap-2 px-4 py-3 bg-[#111318] border rounded-xl shadow-xl min-w-[100px]',
        isActive ? 'border-emerald-500/25' : 'border-white/10',
      )}
    >
      <Handle type="target" position={Position.Left} className="!w-2.5 !h-2.5 !bg-gray-400 !border-0 !rounded-full" />
      <div
        className={cn(
          'w-10 h-10 rounded-full flex items-center justify-center',
          isActive ? 'bg-emerald-500/15 border border-emerald-400/25' : 'bg-gray-500/15 border border-gray-500/25',
        )}
      >
        {isActive ? (
          <CheckCircle className="w-5 h-5 text-emerald-400" />
        ) : (
          <AlertCircle className="w-5 h-5 text-gray-500" />
        )}
      </div>
      <div className="text-center">
        <div className={cn('text-[11px] font-bold tracking-wide', isActive ? 'text-emerald-400' : 'text-gray-500')}>
          RESPONSE
        </div>
        <div className="text-[10px] text-gray-600 mt-0.5">{isActive ? 'Live' : 'Inactive'}</div>
      </div>
    </div>
  )
}

function LabelNode({ data }: NodeProps) {
  const d = data as LabelNodeData
  return (
    <div className="flex flex-col items-center gap-1 pointer-events-none select-none">
      <div className={cn('text-[10px] font-bold uppercase tracking-widest px-2.5 py-1 rounded-full border', d.color)}>
        {d.label}
      </div>
      {d.sub && <div className="text-[9px] text-gray-600">{d.sub}</div>}
    </div>
  )
}

const NODE_TYPES = {
  clientNode: ClientNode,
  routeNode: RouteNode,
  filterNode: FilterNode,
  upstreamNode: UpstreamNode,
  responseNode: ResponseNode,
  labelNode: LabelNode,
}

// ─── Edge style ───────────────────────────────────────────────────────────────

function edgeStyle(type: 'pre' | 'post' | 'route' | 'default') {
  const c = { pre: '#6366f1', post: '#a855f7', route: '#10b981', default: '#374151' }[type]
  return {
    type: 'smoothstep' as const,
    animated: type !== 'default',
    style: { stroke: c, strokeWidth: 2 },
    markerEnd: { type: MarkerType.ArrowClosed, color: c, width: 14, height: 14 },
  }
}

// ─── Flow validation ──────────────────────────────────────────────────────────

function isFlowComplete(nodes: Node[], edges: Edge[]): boolean {
  const ids = new Set(nodes.map((n) => n.id))
  if (!ids.has('client') || !ids.has('route') || !ids.has('upstream') || !ids.has('response')) return false
  const adj: Record<string, string[]> = {}
  edges.forEach((e) => {
    ;(adj[e.source] ??= []).push(e.target)
  })
  const visited = new Set<string>()
  const queue = ['client']
  while (queue.length) {
    const cur = queue.shift()!
    if (visited.has(cur)) continue
    visited.add(cur)
    ;(adj[cur] ?? []).forEach((t) => queue.push(t))
  }
  return visited.has('response')
}

// ─── Layout builder ───────────────────────────────────────────────────────────

function buildGraph(route: RouteDto, onDetach: (id: string) => void): { nodes: Node[]; edges: Edge[] } {
  const nodes: Node[] = []
  const edges: Edge[] = []
  const pre = [...(route.filters ?? [])].filter((f) => f.phase === 'PRE').sort((a, b) => a.order - b.order)
  const post = [...(route.filters ?? [])].filter((f) => f.phase === 'POST').sort((a, b) => a.order - b.order)
  const maxR = Math.max(pre.length, post.length, 1)
  const cy = FLOW_START_Y + ((maxR - 1) * FLOW_ROW_GAP) / 2

  // Client
  nodes.push({
    id: 'client',
    type: 'clientNode',
    position: { x: FLOW_COL.client, y: cy - 40 },
    data: { status: route.status } satisfies ClientNodeData,
    draggable: true,
  })
  nodes.push({
    id: 'pre-label',
    type: 'labelNode',
    position: { x: FLOW_COL.preLabel, y: FLOW_START_Y - 80 },
    data: {
      label: 'Pre Filters',
      sub: 'Before Upstream',
      color: 'text-blue-300 bg-blue-500/10 border-blue-500/20',
    } satisfies LabelNodeData,
    draggable: false,
    selectable: false,
  })

  // PRE filters
  if (pre.length === 0) {
    edges.push({ id: 'e-client-route', source: 'client', target: 'route', ...edgeStyle('pre') })
  } else {
    pre.forEach((f, i) => {
      const nid = `pre-${f.filterId}`
      nodes.push({
        id: nid,
        type: 'filterNode',
        position: { x: FLOW_COL.pre, y: FLOW_START_Y + i * FLOW_ROW_GAP },
        data: { filter: f, phase: 'PRE', onDetach } satisfies FilterNodeData,
        draggable: true,
      })
      const prev = i === 0 ? 'client' : `pre-${pre[i - 1].filterId}`
      edges.push({ id: `e-${prev}-${nid}`, source: prev, target: nid, ...edgeStyle('pre') })
    })
    edges.push({
      id: 'e-pre-last-route',
      source: `pre-${pre[pre.length - 1].filterId}`,
      target: 'route',
      ...edgeStyle('pre'),
    })
  }

  // Route
  nodes.push({
    id: 'route',
    type: 'routeNode',
    position: { x: FLOW_COL.route, y: cy - 55 },
    data: {
      name: route.name,
      pathPattern: route.pathPattern,
      methods: route.methods,
      status: route.status,
      version: route.version,
    } satisfies RouteNodeData,
    draggable: true,
  })
  edges.push({
    id: 'e-route-upstream',
    source: 'route',
    target: 'upstream',
    label: 'forward',
    labelStyle: { fill: '#4b5563', fontSize: 10 },
    labelBgStyle: { fill: '#0c0e14', fillOpacity: 0.8 },
    ...edgeStyle('route'),
  })

  // Upstream
  nodes.push({
    id: 'upstream',
    type: 'upstreamNode',
    position: { x: FLOW_COL.upstream, y: cy - 45 },
    data: { uri: route.upstreamUri, stripPrefix: route.stripPrefix } satisfies UpstreamNodeData,
    draggable: true,
  })
  nodes.push({
    id: 'post-label',
    type: 'labelNode',
    position: { x: FLOW_COL.postLabel, y: FLOW_START_Y - 80 },
    data: {
      label: 'Post Filters',
      sub: 'After Upstream',
      color: 'text-purple-300 bg-purple-500/10 border-purple-500/20',
    } satisfies LabelNodeData,
    draggable: false,
    selectable: false,
  })

  // POST filters
  if (post.length === 0) {
    edges.push({ id: 'e-upstream-response', source: 'upstream', target: 'response', ...edgeStyle('post') })
  } else {
    post.forEach((f, i) => {
      const nid = `post-${f.filterId}`
      nodes.push({
        id: nid,
        type: 'filterNode',
        position: { x: FLOW_COL.post, y: FLOW_START_Y + i * FLOW_ROW_GAP },
        data: { filter: f, phase: 'POST', onDetach } satisfies FilterNodeData,
        draggable: true,
      })
      const prev = i === 0 ? 'upstream' : `post-${post[i - 1].filterId}`
      edges.push({ id: `e-${prev}-${nid}`, source: prev, target: nid, ...edgeStyle('post') })
    })
    edges.push({
      id: 'e-post-last-response',
      source: `post-${post[post.length - 1].filterId}`,
      target: 'response',
      ...edgeStyle('post'),
    })
  }

  // Response
  nodes.push({
    id: 'response',
    type: 'responseNode',
    position: { x: FLOW_COL.response, y: cy - 40 },
    data: { status: route.status } satisfies ResponseNodeData,
    draggable: true,
  })
  return { nodes, edges }
}

// ─── Validation banner ────────────────────────────────────────────────────────

function ValidationBanner({ valid }: { valid: boolean }) {
  if (valid) {
    return (
      <div className="flex items-center gap-1.5 px-3 py-1.5 rounded-lg bg-emerald-500/10 border border-emerald-500/20 text-xs text-emerald-400">
        <CheckCircle className="w-3.5 h-3.5 shrink-0" /> Flow complete — ready to save
      </div>
    )
  }
  return (
    <div className="flex items-center gap-1.5 px-3 py-1.5 rounded-lg bg-amber-500/10 border border-amber-500/20 text-xs text-amber-400">
      <AlertCircle className="w-3.5 h-3.5 shrink-0" /> Connect all nodes: Client → … → Response
    </div>
  )
}

// ─── Main component ────────────────────────────────────────────────────────────

interface RouteFlowCanvasProps {
  route: RouteDto
  height?: number
  onValidityChange?: (valid: boolean) => void
}

export default function RouteFlowCanvas({ route, height = 480, onValidityChange }: RouteFlowCanvasProps) {
  const qc = useQueryClient()
  const navigate = useNavigate()

  // No-op detach: read-only canvas still passes onDetach to filter nodes
  // (node hover buttons won't fire because elementsSelectable=false)
  const detachMutation = useMutation({
    mutationFn: (filterId: string) => routesApi.detachFilter(route.id, filterId),
    onSuccess: () => qc.invalidateQueries({ queryKey: ['route', route.id] }),
  })
  const onDetach = useCallback((id: string) => detachMutation.mutate(id), [detachMutation])

  const initialGraph = useMemo(
    () => buildGraph(route, onDetach),
    // eslint-disable-next-line react-hooks/exhaustive-deps
    [route.id, JSON.stringify(route.filters), route.status],
  )

  const [nodes, setNodes] = useNodesState(initialGraph.nodes)
  const [edges] = useEdgesState(initialGraph.edges)

  // Re-sync when route data changes
  useEffect(() => {
    const g = buildGraph(route, onDetach)
    setNodes(g.nodes)
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [route.id, JSON.stringify(route.filters), route.status])

  const valid = useMemo(() => isFlowComplete(nodes, edges), [nodes, edges])
  useEffect(() => {
    onValidityChange?.(valid)
  }, [valid, onValidityChange])

  const onInit = useCallback(
    (i: ReactFlowInstance) => setTimeout(() => i.fitView({ padding: 0.2, duration: 400 }), 50),
    [],
  )

  return (
    <div
      style={{ height }}
      className="relative w-full rounded-xl overflow-hidden border border-white/[0.06] bg-[#080a0f]"
    >
      <div className="absolute inset-0">
        <ReactFlow
          nodes={nodes}
          edges={edges}
          nodeTypes={NODE_TYPES}
          onInit={onInit}
          fitView
          fitViewOptions={{ padding: 0.2 }}
          minZoom={0.15}
          maxZoom={1.8}
          deleteKeyCode={null}
          proOptions={{ hideAttribution: true }}
          className="bg-[#080a0f]"
          connectionLineStyle={{ stroke: '#6366f1', strokeWidth: 2 }}
          defaultEdgeOptions={{ type: 'smoothstep' }}
          nodesDraggable={false}
          nodesConnectable={false}
          elementsSelectable={false}
        >
          <Background variant={BackgroundVariant.Dots} gap={24} size={1} color="rgba(255,255,255,0.04)" />
          <Controls className="!bg-[#111318] !border-white/10 !rounded-lg !shadow-xl" showInteractive={false} />
          <MiniMap
            className="!bg-[#0d0f14] !border-white/10 !rounded-lg"
            nodeColor="#1e2030"
            maskColor="rgba(0,0,0,0.4)"
          />

          <Panel position="top-right" className="flex items-center gap-2">
            <ValidationBanner valid={valid} />
            {/* Filters can only be managed via the full Workflow Builder */}
            <button
              onClick={() => navigate(`/routes/${route.id}/builder`)}
              className="flex items-center gap-1.5 px-3 py-1.5 bg-violet-600 hover:bg-violet-500 text-white text-xs font-semibold rounded-lg shadow-lg shadow-violet-500/25 transition-all"
            >
              <Network className="w-3.5 h-3.5" /> Edit in Builder
            </button>
          </Panel>

          <Panel position="bottom-center">
            <div className="text-[10px] text-gray-700 bg-[#080a0f]/80 px-3 py-1 rounded-full border border-white/[0.04]">
              Preview only — use <strong className="text-gray-500">Edit in Builder</strong> to add or remove filters
            </div>
          </Panel>
        </ReactFlow>
      </div>
    </div>
  )
}
