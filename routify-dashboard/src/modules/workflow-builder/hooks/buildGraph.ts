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

