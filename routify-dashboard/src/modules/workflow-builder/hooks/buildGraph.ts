/**
 * buildGraph.ts — Converts a RouteDto into the React Flow node/edge graph.
 *
 * Extracted from RouteFlowCanvas so both the legacy modal canvas and the
 * new full-page WorkflowBuilder share identical layout logic.
 */
import type { Node, Edge } from '@xyflow/react'
import type { RouteDto, RouteFilterRef } from '../../../types'
import { edgeStyle } from '../constants/nodeMetadata'
import { FLOW_COL, FLOW_ROW_GAP, FLOW_START_Y } from '../../routes/routeConstants'

// ─── Node data shapes (exported so custom node components can type their props) ─

export interface ClientNodeData   extends Record<string, unknown> { status: string }
export interface RouteNodeData    extends Record<string, unknown> {
  name: string; pathPattern: string; methods: string; status: string; version: number
  onSelect?: () => void
}
export interface FilterNodeData   extends Record<string, unknown> {
  filter: RouteFilterRef; phase: 'PRE' | 'POST'
  onDetach: (id: string) => void
  onSelect?: () => void
}
export interface UpstreamNodeData extends Record<string, unknown> {
  uri: string; stripPrefix?: string
  onSelect?: () => void
}
export interface ResponseNodeData extends Record<string, unknown> { status: string }
export interface LabelNodeData    extends Record<string, unknown> { label: string; sub?: string; color: string }

// ─── Graph builder ────────────────────────────────────────────────────────────

export function buildGraph(
  route: RouteDto,
  onDetach: (id: string) => void,
  onSelect?: (nodeId: string) => void,
): { nodes: Node[]; edges: Edge[] } {
  const nodes: Node[] = []
  const edges: Edge[] = []

  const pre  = [...(route.filters ?? [])].filter(f => f.phase === 'PRE').sort((a, b) => a.order - b.order)
  const post = [...(route.filters ?? [])].filter(f => f.phase === 'POST').sort((a, b) => a.order - b.order)
  const maxR = Math.max(pre.length, post.length, 1)
  const cy   = FLOW_START_Y + ((maxR - 1) * FLOW_ROW_GAP) / 2

  // ── Client ────────────────────────────────────────────────────────────────
  nodes.push({
    id: 'client',
    type: 'clientNode',
    position: { x: FLOW_COL.client, y: cy - 40 },
    data: { status: route.status } satisfies ClientNodeData,
    draggable: true,
  })

  // ── PRE label ─────────────────────────────────────────────────────────────
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

  // ── PRE filters ───────────────────────────────────────────────────────────
  if (pre.length === 0) {
    edges.push({ id: 'e-client-route', source: 'client', target: 'route', ...edgeStyle('pre') })
  } else {
    pre.forEach((f, i) => {
      const nid = `pre-${f.filterId}`
      nodes.push({
        id: nid,
        type: 'filterNode',
        position: { x: FLOW_COL.pre, y: FLOW_START_Y + i * FLOW_ROW_GAP },
        data: {
          filter: f,
          phase: 'PRE',
          onDetach,
          onSelect: onSelect ? () => onSelect(nid) : undefined,
        } satisfies FilterNodeData,
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

  // ── Route node ────────────────────────────────────────────────────────────
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
      onSelect: onSelect ? () => onSelect('route') : undefined,
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

  // ── Upstream ──────────────────────────────────────────────────────────────
  nodes.push({
    id: 'upstream',
    type: 'upstreamNode',
    position: { x: FLOW_COL.upstream, y: cy - 45 },
    data: {
      uri: route.upstreamUri,
      stripPrefix: route.stripPrefix,
      onSelect: onSelect ? () => onSelect('upstream') : undefined,
    } satisfies UpstreamNodeData,
    draggable: true,
  })

  // ── POST label ────────────────────────────────────────────────────────────
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

  // ── POST filters ──────────────────────────────────────────────────────────
  if (post.length === 0) {
    edges.push({ id: 'e-upstream-response', source: 'upstream', target: 'response', ...edgeStyle('post') })
  } else {
    post.forEach((f, i) => {
      const nid = `post-${f.filterId}`
      nodes.push({
        id: nid,
        type: 'filterNode',
        position: { x: FLOW_COL.post, y: FLOW_START_Y + i * FLOW_ROW_GAP },
        data: {
          filter: f,
          phase: 'POST',
          onDetach,
          onSelect: onSelect ? () => onSelect(nid) : undefined,
        } satisfies FilterNodeData,
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

  // ── Response ──────────────────────────────────────────────────────────────
  nodes.push({
    id: 'response',
    type: 'responseNode',
    position: { x: FLOW_COL.response, y: cy - 40 },
    data: { status: route.status } satisfies ResponseNodeData,
    draggable: true,
  })

  return { nodes, edges }
}

// ─── Empty-canvas graph builder ───────────────────────────────────────────────
/**
 * Build a graph with only the 4 structural skeleton nodes (no filter nodes).
 * Used when a newly-created route has no filters yet.
 */
export function buildEmptyGraph(route: RouteDto): { nodes: Node[]; edges: Edge[] } {
  const cy = FLOW_START_Y

  const nodes: Node[] = [
    {
      id: 'client',
      type: 'clientNode',
      position: { x: FLOW_COL.client, y: cy },
      data: { status: route.status } satisfies ClientNodeData,
      draggable: true,
    },
    {
      id: 'route',
      type: 'routeNode',
      position: { x: FLOW_COL.route, y: cy - 15 },
      data: {
        name: route.name,
        pathPattern: route.pathPattern,
        methods: route.methods,
        status: route.status,
        version: route.version,
      } satisfies RouteNodeData,
      draggable: true,
    },
    {
      id: 'upstream',
      type: 'upstreamNode',
      position: { x: FLOW_COL.upstream, y: cy - 5 },
      data: {
        uri: route.upstreamUri,
        stripPrefix: route.stripPrefix,
      } satisfies UpstreamNodeData,
      draggable: true,
    },
    {
      id: 'response',
      type: 'responseNode',
      position: { x: FLOW_COL.response, y: cy },
      data: { status: route.status } satisfies ResponseNodeData,
      draggable: true,
    },
  ]

  const edges: Edge[] = [
    { id: 'e-client-route',    source: 'client',   target: 'route',    ...edgeStyle('pre') },
    { id: 'e-route-upstream',  source: 'route',    target: 'upstream', ...edgeStyle('route'),
      label: 'forward', labelStyle: { fill: '#4b5563', fontSize: 10 }, labelBgStyle: { fill: '#0c0e14', fillOpacity: 0.8 } },
    { id: 'e-upstream-response', source: 'upstream', target: 'response', ...edgeStyle('post') },
  ]

  return { nodes, edges }
}

// ─── Execution order inference ────────────────────────────────────────────────
/**
 * Infer the filter execution order and phase from graph topology.
 *
 * Phase determination:
 *  - A filter node placed between 'client'…'route' (or connected before 'route')
 *    → PRE (runs before upstream)
 *  - A filter node placed between 'upstream'…'response' (or connected after 'upstream')
 *    → POST (runs after upstream)
 *  - Unconnected filter nodes fall back to position: left of upstream center → PRE, right → POST
 *
 * Execution order (within a phase) is derived from topological position (x-coordinate
 * as a tiebreaker when graph is acyclic with known structure).
 *
 * Returns an array of { nodeId, filterId, phase, order } sorted by phase then order.
 */
export interface InferredFilterExecution {
  nodeId: string
  filterId: string
  phase: 'PRE' | 'POST'
  order: number
}

export function inferExecutionOrder(
  nodes: Node[],
  edges: Edge[],
): InferredFilterExecution[] {
  // Build adjacency (source → targets)
  const adj: Record<string, string[]> = {}
  edges.forEach(e => { (adj[e.source] ??= []).push(e.target) })

  // BFS from 'client' — track traversal order
  const visitOrder: Record<string, number> = {}
  const queue: string[] = ['client']
  let order = 0
  const visited = new Set<string>()
  while (queue.length) {
    const cur = queue.shift()!
    if (visited.has(cur)) continue
    visited.add(cur)
    visitOrder[cur] = order++
    ;(adj[cur] ?? []).forEach(t => queue.push(t))
  }

  const filterNodes = nodes.filter(n => n.type === 'filterNode')
  const upstreamOrder = visitOrder['upstream'] ?? Infinity

  const result: InferredFilterExecution[] = filterNodes.map((n, i) => {
    const nodeVisitOrder = visitOrder[n.id] ?? i
    const phase: 'PRE' | 'POST' = nodeVisitOrder < upstreamOrder ? 'PRE' : 'POST'
    return {
      nodeId: n.id,
      filterId: (n.data as { filter: { filterId: string } }).filter.filterId,
      phase,
      order: nodeVisitOrder,
    }
  })

  // Sort: PRE first, then POST; within phase by visit order
  result.sort((a, b) => {
    if (a.phase !== b.phase) return a.phase === 'PRE' ? -1 : 1
    return a.order - b.order
  })

  // Re-number order sequentially within each phase (0, 10, 20, …)
  let preIdx = 0, postIdx = 0
  return result.map(r => ({
    ...r,
    order: r.phase === 'PRE' ? (preIdx++) * 10 : (postIdx++) * 10,
  }))
}

// ─── Flow completeness validator ──────────────────────────────────────────────

export function isFlowComplete(nodes: Node[], edges: Edge[]): boolean {
  const ids = new Set(nodes.map(n => n.id))
  if (!ids.has('client') || !ids.has('route') || !ids.has('upstream') || !ids.has('response')) return false
  const adj: Record<string, string[]> = {}
  edges.forEach(e => { (adj[e.source] ??= []).push(e.target) })
  const visited = new Set<string>()
  const queue = ['client']
  while (queue.length) {
    const cur = queue.shift()!
    if (visited.has(cur)) continue
    visited.add(cur)
    ;(adj[cur] ?? []).forEach(t => queue.push(t))
  }
  return visited.has('response')
}
